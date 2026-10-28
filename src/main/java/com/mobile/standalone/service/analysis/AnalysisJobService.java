package com.mobile.standalone.service.analysis;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mobile.standalone.auth.OpenApiAuth;
import com.mobile.standalone.config.AnalysisBridgeProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisJobService {
    private final JdbcTemplate jdbc;
    private final AnalysisBridgeProperties props;
    private final OpenApiAuth auth;
    private final WikiRequirementIngestService wikiIngest;
    private final AnalysisPreparationService preparation;
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private volatile boolean ready;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean callbackBusy = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r,"codex-analysis"); t.setDaemon(true); return t; });
    private final ExecutorService callbacks = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r,"analysis-callback"); t.setDaemon(true); return t; });
    private final Map<String, Process> running = new ConcurrentHashMap<>();
    private final Map<String,String> activeJobs = new ConcurrentHashMap<>();

    public synchronized void ensureTable() {
        if (ready) return;
        jdbc.execute("""
          CREATE TABLE IF NOT EXISTS analysis_bridge_jobs (
            id VARCHAR(64) PRIMARY KEY, client VARCHAR(100) NOT NULL, external_id VARCHAR(160) NOT NULL,
            requirement_key VARCHAR(80) NOT NULL, request_hash CHAR(64) NOT NULL, request_json LONGTEXT NOT NULL,
            status VARCHAR(24) NOT NULL, error_code VARCHAR(80) DEFAULT NULL, attempt_id VARCHAR(64) DEFAULT NULL,
            heartbeat_at DATETIME DEFAULT NULL, result_json LONGTEXT, result_hash CHAR(64) DEFAULT NULL,
            result_source VARCHAR(24) DEFAULT NULL, callback_event_id VARCHAR(64) DEFAULT NULL,
            callback_state VARCHAR(24) NOT NULL DEFAULT 'NONE', callback_attempts INT NOT NULL DEFAULT 0,
            callback_next_at DATETIME DEFAULT NULL, callback_http_status INT DEFAULT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            finished_at DATETIME DEFAULT NULL, UNIQUE KEY uk_client_external(client,external_id),
            INDEX idx_status(status,created_at), INDEX idx_callback(callback_state,callback_next_at)
          ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
          """);
        ready = true;
    }
    public void allowClient(String client) {
        if (!props.getAllowedClients().contains(client)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"该调用方未启用代码分析");
    }
    public Map<String,Object> config() {
        return Map.of("executorEnabled",props.isEnabled(),"scriptPresent",Files.isRegularFile(Path.of(props.getScript())),
            "repositoryProjects",props.getRepositories().keySet(),"repositoryGroups",props.getRepositoryGroups(),"callbackClients",props.getCallbacks().entrySet().stream().filter(e->e.getValue()!=null && !e.getValue().isBlank()).map(Map.Entry::getKey).toList(),
            "wikiConfigured", wikiIngest != null && wikiIngest.configured(),
            "figmaConfigured",!props.getFigmaToken().isBlank(),
            "jiraConfigured",preparation.configured(),
            "hint",props.isEnabled()?"已启用执行端；SDK、模型登录及材料工具仍需可用":"未启用 Codex 执行端，可接收任务并人工导入结果");
    }
    public synchronized Map<String,Object> create(String client, JsonNode input) {
        allowClient(client); AnalysisContract.validateRequest(input); ensureTable();
        // Existing requirement reports win over external request ids and new snapshots.
        var reusable=existingRequirementJob(client,input);
        if(reusable!=null)return reusable;
        preparation.validate(client,input);
        for(JsonNode repo:input.path("repositories")){
            String group=props.getRepositoryGroups().get(repo.path("projectId").asText());
            if(group!=null && !Set.of(input.path("businessGroup").asText(),"SHARED").contains(group))throw AnalysisContract.invalid("项目不属于本单业务组："+repo.path("projectId").asText());
        }
        ObjectNode body = input.deepCopy();
        Set<String> excludedSources=new HashSet<>();
        for(JsonNode material:body.path("materials"))if(Set.of("EXCLUDED","SUPERSEDED").contains(material.path("reviewStatus").asText()))excludedSources.add(material.path("id").asText());
        if(body.path("materials").isArray()) {
            var selected=(com.fasterxml.jackson.databind.node.ArrayNode)body.path("materials");
            for(int i=selected.size()-1;i>=0;i--){JsonNode material=selected.get(i);if(excludedSources.contains(material.path("id").asText())||excludedSources.contains(material.path("sourceId").asText())||"DUPLICATE".equals(material.path("reviewStatus").asText())||(material.has("analysisIncluded")&&!material.path("analysisIncluded").asBoolean()))selected.remove(i);}
        }

        body.put("externalId",input.path("externalId").asText().trim());
        body.put("requirementKey",input.path("requirementKey").asText().trim());
        if(body.has("previousJobId")) {
            Map<String,Object> previous=row(client,body.path("previousJobId").asText());
            try { AnalysisContract.validatePrevious(body,previous.get("requirement_key").toString(),JSON.readTree(previous.get("request_json").toString()),previous.get("status").toString()); }
            catch(java.io.IOException e){throw AnalysisContract.invalid("历史材料不可读取");}
        }
        String external=body.path("externalId").asText(), hash=AnalysisContract.hash(body);
        var existing=jdbc.queryForList("SELECT id,request_hash FROM analysis_bridge_jobs WHERE client=? AND external_id=?",client,external);
        if (!existing.isEmpty()) return duplicate(client,existing.get(0),hash);
        if(body.has("reuseResultFromJobId"))return createReused(client,body,hash);
        String id=UUID.randomUUID().toString();
        String status=props.isEnabled()?"QUEUED":"BLOCKED";
        try { jdbc.update("INSERT INTO analysis_bridge_jobs(id,client,external_id,requirement_key,request_hash,request_json,status,error_code) VALUES(?,?,?,?,?,?,?,?)", id,client,external,body.path("requirementKey").asText(),hash,body.toString(),status,props.isEnabled()?null:"EXECUTOR_DISABLED"); }
        catch (DuplicateKeyException e) {
            var row=jdbc.queryForList("SELECT id,request_hash FROM analysis_bridge_jobs WHERE client=? AND external_id=?",client,external);
            if(row.isEmpty())throw e; return duplicate(client,row.get(0),hash);
        }
        Map<String,Object> out=new LinkedHashMap<>(detail(client,id)); out.put("duplicate",false);return out;
    }
    private Map<String,Object> existingRequirementJob(String client,JsonNode input) {
        String key=input.path("requirementKey").asText().trim().toUpperCase(Locale.ROOT);
        String group=input.path("businessGroup").asText(key.substring(0,key.lastIndexOf('-')));
        var candidates=jdbc.queryForList("SELECT id,status,request_json FROM analysis_bridge_jobs WHERE client=? AND requirement_key=? AND (status='SUCCEEDED' AND result_json IS NOT NULL OR status IN ('RUNNING','QUEUED')) ORDER BY CASE WHEN status='SUCCEEDED' THEN 0 ELSE 1 END,finished_at DESC,created_at DESC",client,key);
        for(var candidate:candidates) {
            try {
                JsonNode original=JSON.readTree(Objects.toString(candidate.get("request_json"),"{}"));
                String originalGroup=original.path("businessGroup").asText(key.substring(0,key.lastIndexOf('-')));
                if(!group.equals(originalGroup))continue;
                // Explicit references must still point to this requirement, even though no new record is created.
                if(input.has("reuseResultFromJobId")) {
                    var selected=row(client,input.path("reuseResultFromJobId").asText());
                    if(!key.equals(selected.get("requirement_key"))||!"SUCCEEDED".equals(selected.get("status")))throw AnalysisContract.invalid("复用报告不属于当前已完成需求");
                    candidate=selected;
                }
                var out=new LinkedHashMap<>(detail(client,candidate.get("id").toString()));
                out.put("duplicate",true);out.put("reused","SUCCEEDED".equals(candidate.get("status")));out.put("newAnalysisPerformed",false);out.put("existingRequirement",true);
                out.put("requestedExternalId",input.path("externalId").asText());
                out.put("message","SUCCEEDED".equals(candidate.get("status"))?"已返回本单已有报告，未创建任务、未重新分析":"本单已有分析任务，未重复创建");
                return out;
            }catch(java.io.IOException e){throw AnalysisContract.invalid("已有任务材料不可读取，未创建新分析");}
        }
        return null;
    }
    private ObjectNode reusedResult(String client,ObjectNode request)throws java.io.IOException {
        String sourceId=request.path("reuseResultFromJobId").asText();Map<String,Object> source=row(client,sourceId);
        AnalysisResultReuse.validate(request,JSON.readTree(source.get("request_json").toString()),source.get("status").toString());
        return AnalysisResultReuse.copy(result(client,sourceId),sourceId,Objects.toString(source.get("result_hash"),""));
    }
    private Map<String,Object> createReused(String client,ObjectNode request,String hash) {
        try {
            ObjectNode value=reusedResult(client,request);String id=UUID.randomUUID().toString(),attempt=UUID.randomUUID().toString();
            try{jdbc.update("INSERT INTO analysis_bridge_jobs(id,client,external_id,requirement_key,request_hash,request_json,status,attempt_id,heartbeat_at) VALUES(?,?,?,?,?,?,'RUNNING',?,NOW())",id,client,request.path("externalId").asText(),request.path("requirementKey").asText(),hash,request.toString(),attempt);}
            catch(DuplicateKeyException e){var rows=jdbc.queryForList("SELECT id,request_hash FROM analysis_bridge_jobs WHERE client=? AND external_id=?",client,request.path("externalId").asText());if(rows.isEmpty())throw e;return duplicate(client,rows.get(0),hash);}
            try{finish(id,attempt,value,"reused");}catch(Exception e){jdbc.update("UPDATE analysis_bridge_jobs SET status='FAILED',error_code='RESULT_REUSE_FAILED',updated_at=NOW() WHERE id=? AND attempt_id=? AND status='RUNNING'",id,attempt);throw e;}
            var response=new LinkedHashMap<>(detail(client,id));response.put("duplicate",false);response.put("reused",true);response.put("reusedFromJobId",request.path("reuseResultFromJobId").asText());return response;
        }catch(java.io.IOException e){throw AnalysisContract.invalid("原报告材料不可读取；未触发新分析");}
    }
    private Map<String,Object> duplicate(String client,Map<String,Object> row,String hash) {
        if(!hash.equals(row.get("request_hash")))throw new ResponseStatusException(HttpStatus.CONFLICT,"externalId 已用于不同内容，请使用新的请求 ID");
        Map<String,Object> out=new LinkedHashMap<>(detail(client,row.get("id").toString()));out.put("duplicate",true);return out;
    }
    public List<Map<String,Object>> list(String client,String externalId) {
        ensureTable();
        String columns="id,client,external_id,requirement_key,status,error_code,result_source,callback_state,callback_attempts,created_at,updated_at,finished_at,request_json";
        if(client==null)return summaries(jdbc.queryForList("SELECT "+columns+" FROM analysis_bridge_jobs ORDER BY created_at DESC,id DESC LIMIT 100"));
        if(externalId!=null&&!externalId.isBlank())return summaries(jdbc.queryForList("SELECT "+columns+" FROM analysis_bridge_jobs WHERE client=? AND external_id=? ORDER BY created_at DESC LIMIT 100",client,externalId));
        return summaries(jdbc.queryForList("SELECT "+columns+" FROM analysis_bridge_jobs WHERE client=? ORDER BY created_at DESC,id DESC LIMIT 100",client));
    }
    private List<Map<String,Object>> summaries(List<Map<String,Object>> rows) {
        return rows.stream().map(this::summary).toList();
    }
    private Map<String,Object> summary(Map<String,Object> row) {
        Map<String,Object> out=new LinkedHashMap<>(row);
        try {
            JsonNode request=JSON.readTree(Objects.toString(out.remove("request_json"),"{}"));
            out.put("businessGroup",request.path("businessGroup").asText("未分组"));
            out.put("materialVersion",request.path("materialVersion").asText("legacy"));
            out.putAll(AnalysisDeliveryStatus.describe(Objects.toString(row.get("id"),""),Objects.toString(row.get("status"),"")));
        } catch(Exception e){out.remove("request_json");}
        return out;
    }
    private Map<String,Object> row(String client,String id) {
        ensureTable();
        var rows=client==null?jdbc.queryForList("SELECT * FROM analysis_bridge_jobs WHERE id=?",id):jdbc.queryForList("SELECT * FROM analysis_bridge_jobs WHERE id=? AND client=?",id,client);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"分析任务不存在");return rows.get(0);
    }
    public Map<String,Object> detail(String client,String id) {
        Map<String,Object> source=row(client,id);
        Map<String,Object> out=summary(source);
        try { out.put("analysisInput",JSON.readTree(Objects.toString(source.get("request_json"),"{}"))); }
        catch(Exception e){throw new IllegalStateException("任务材料读取失败");}
        String attempt=Objects.toString(source.get("attempt_id"),"");
        if(id.matches("[a-f0-9-]{36}")&&attempt.matches("[a-f0-9-]{36}")) {
            Path workerLog=Path.of(props.getWorkspace()).toAbsolutePath().resolve(id).resolve(attempt).resolve("worker.log");
            if(Files.isRegularFile(workerLog))try(var lines=Files.lines(workerLog)){
                String progress=lines.filter(line->line.startsWith("{\"event\":\"analysis_progress\"")).reduce((first,last)->last).orElse("");
                if(!progress.isBlank()){JsonNode event=JSON.readTree(progress);out.put("analysisStage",event.path("stage").asText());out.put("analysisProgressAt",event.path("at").asText());}
            }catch(Exception ignored){}
        }
        out.remove("request_json");out.remove("result_json");out.remove("request_hash");
        return out;
    }
    public JsonNode result(String client,String id) {
        Map<String,Object> row=row(client,id);
        if(!"SUCCEEDED".equals(row.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"结果尚未就绪");
        try { return JSON.readTree(row.get("result_json").toString()); }catch(Exception e){throw new IllegalStateException("结果读取失败");}
    }
    public Map<String,Object> cancel(String client,String id) {
        row(client,id);
        jdbc.update("UPDATE analysis_bridge_jobs SET status='CANCELLED',finished_at=NOW(),updated_at=NOW() WHERE id=? AND status IN ('QUEUED','RUNNING','BLOCKED','FAILED')",id);
        Process p=running.get(id);if(p!=null)stop(p);
        return detail(client,id);
    }
    public Map<String,Object> retry(String id) {
        var current=row(null,id);
        try {
            var input=JSON.readTree(current.get("request_json").toString());
            var existing=existingRequirementJob(current.get("client").toString(),input);
            if(existing!=null&&!id.equals(existing.get("id")))return existing;
        }catch(java.io.IOException e){throw AnalysisContract.invalid("任务材料不可读取");}
        int changed=jdbc.update("UPDATE analysis_bridge_jobs SET status=?,error_code=?,updated_at=NOW(),finished_at=NULL,attempt_id=NULL WHERE id=? AND status IN ('FAILED','BLOCKED','CANCELLED')",props.isEnabled()?"QUEUED":"BLOCKED",props.isEnabled()?null:"EXECUTOR_DISABLED",id);
        if(changed==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"仅失败、阻塞或已取消任务可重新分析");return detail(null,id);
    }
    // Imported results are visibly labelled manual and never replace a completed or running result.
    public Map<String,Object> importResult(String id,JsonNode value) {
        Map<String,Object> row=row(null,id);AnalysisContract.validateResult(value,row.get("requirement_key").toString());
        String attempt=UUID.randomUUID().toString();
        int changed=jdbc.update("UPDATE analysis_bridge_jobs SET status='RUNNING',attempt_id=?,heartbeat_at=NOW(),updated_at=NOW() WHERE id=? AND status IN ('BLOCKED','FAILED','QUEUED')",attempt,id);
        if(changed==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"只能为未执行或失败的任务导入结果");
        finish(id,attempt,value,"manual");return detail(null,id);
    }
    // Publish additive review content as a new immutable result, preserving the previous task.
    public Map<String,Object> publishResultRevision(String sourceId, JsonNode value) {
        Map<String,Object> source=row(null,sourceId);
        if(!"SUCCEEDED".equals(source.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"只能补充已完成报告");
        AnalysisContract.validateResult(value,source.get("requirement_key").toString());
        try {
            String external="revision-"+sourceId+"-"+AnalysisContract.hash(value).substring(0,20);
            String client=source.get("client").toString();
            var existing=jdbc.queryForList("SELECT id FROM analysis_bridge_jobs WHERE client=? AND external_id=?",client,external);
            if(!existing.isEmpty())return detail(client,existing.get(0).get("id").toString());
            ObjectNode request=(ObjectNode)JSON.readTree(source.get("request_json").toString());
            request.put("externalId",external);request.put("previousJobId",sourceId);
            String id=UUID.randomUUID().toString();
            try {
                jdbc.update("INSERT INTO analysis_bridge_jobs(id,client,external_id,requirement_key,request_hash,request_json,status,error_code) VALUES(?,?,?,?,?,?,?,?)",id,client,external,source.get("requirement_key"),AnalysisContract.hash(request),request.toString(),"BLOCKED","MANUAL_RESULT_REVISION");
            } catch(DuplicateKeyException e) {
                var raced=jdbc.queryForList("SELECT id FROM analysis_bridge_jobs WHERE client=? AND external_id=?",client,external);
                if(raced.isEmpty())throw e;return detail(client,raced.get(0).get("id").toString());
            }
            return importResult(id,value);
        }catch(java.io.IOException e){throw new IllegalStateException("历史任务材料读取失败");}
    }
    private void finish(String id,String attempt,JsonNode result,String source) {
        Map<String,Object> job=row(null,id);
        ObjectNode published=(ObjectNode)result.deepCopy();
        try {
            JsonNode request=JSON.readTree(job.get("request_json").toString());
            ObjectNode context=JSON.createObjectNode();
            context.put("jobId",id);context.put("client",job.get("client").toString());
            context.put("requirementKey",job.get("requirement_key").toString());
            context.put("requestHash",job.get("request_hash").toString());
            for(String field:List.of("businessGroup","materialVersion","previousJobId","expectedReportRevision")) context.set(field,request.path(field));
            var manifest=JSON.createArrayNode();
            for(JsonNode material:request.path("materials")) {
                ObjectNode entry=JSON.createObjectNode();
                for(String field:List.of("id","type","title","version","url")) if(material.has(field)) entry.set(field,material.get(field));
                entry.put("materialHash",AnalysisContract.hash(material));manifest.add(entry);
            }
            context.set("materialManifest",manifest);
            published.set("analysisContext",context);
        }catch(Exception e){throw new IllegalStateException("结果上下文生成失败");}
        published.set("presentation",AnalysisPresentation.build(published));
        String event=UUID.randomUUID().toString();
        jdbc.update("UPDATE analysis_bridge_jobs SET status='SUCCEEDED',result_json=?,result_hash=?,result_source=?,error_code=NULL,finished_at=NOW(),updated_at=NOW(),callback_event_id=?,callback_state='PENDING',callback_next_at=NOW() WHERE id=? AND attempt_id=? AND status='RUNNING'",published.toString(),AnalysisContract.hash(published),source,event,id,attempt);
    }
    public Map<String,Object> retryCallback(String client,String id) {
        row(client,id);
        retryCallback(id);
        return detail(client,id);
    }
    public Map<String,Object> retryCallback(String id) {
        row(null,id);
        if(jdbc.update("UPDATE analysis_bridge_jobs SET callback_state='PENDING',callback_attempts=0,callback_next_at=NOW(),updated_at=NOW() WHERE id=? AND status='SUCCEEDED' AND callback_state IN ('FAILED','NOT_CONFIGURED')",id)==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"当前结果无需重试回传");
        return detail(null,id);
    }
    @Scheduled(fixedDelayString="${analysis-bridge.poll-ms:5000}")
    public void dispatch() {
        if(!props.isEnabled())return;
        try {
            ensureTable();
            for(var active:activeJobs.entrySet())jdbc.update("UPDATE analysis_bridge_jobs SET heartbeat_at=NOW() WHERE id=? AND attempt_id=? AND status='RUNNING'",active.getKey(),active.getValue());
            jdbc.update("UPDATE analysis_bridge_jobs SET status='FAILED',error_code='WORKER_INTERRUPTED',updated_at=NOW() WHERE status='RUNNING' AND heartbeat_at < DATE_SUB(NOW(), INTERVAL 120 SECOND)");
            if(!busy.compareAndSet(false,true))return;
            var jobs=jdbc.queryForList("SELECT id FROM analysis_bridge_jobs WHERE status='QUEUED' ORDER BY created_at LIMIT 1");
            if(jobs.isEmpty()){busy.set(false);return;}
            String id=jobs.get(0).get("id").toString(),attempt=UUID.randomUUID().toString();
            if(jdbc.update("UPDATE analysis_bridge_jobs SET status='RUNNING',attempt_id=?,heartbeat_at=NOW(),updated_at=NOW() WHERE id=? AND status='QUEUED'",attempt,id)==0){busy.set(false);return;}
            activeJobs.put(id,attempt);
            executor.submit(()->{try{execute(id,attempt);}finally{activeJobs.remove(id,attempt);busy.set(false);}});
        } catch(Exception e){busy.set(false);log.warn("[analysis] 调度暂不可用: {}",e.getClass().getSimpleName());}
    }
    private void execute(String id,String attempt) {
        try {
            Map<String,Object> job=row(null,id);
            ObjectNode input=(ObjectNode)JSON.readTree(job.get("request_json").toString());
            if(input.has("reuseResultFromJobId")){finish(id,attempt,reusedResult(job.get("client").toString(),input),"reused");return;}
            ObjectNode paths=JSON.createObjectNode();
            for(JsonNode repo:input.path("repositories")) {
                String project=repo.path("projectId").asText();
                String baseRef=repo.path("baseRef").asText();
                String targetRef=repo.path("targetRef").asText();
                Path real=ensureLocalRepo(project,(ObjectNode)repo,input);
                paths.put(project,real.toString());
            }
            input.set("resolvedRepositories",paths);
            if(input.has("previousJobId")) input.set("previousResult",result(job.get("client").toString(),input.path("previousJobId").asText()));
            Path dir=Path.of(props.getWorkspace()).toAbsolutePath().resolve(id).resolve(attempt);Files.createDirectories(dir);
            try { Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")); } catch (UnsupportedOperationException ignored) {}
            preparation.attach(job.get("client").toString(),input,dir);
            if (!input.has("preparationId") && wikiIngest != null) wikiIngest.attachToJob(input, dir);
            BrowserCaptureStore.attach(Path.of(props.getWorkspace()),input,dir);
            Path in=dir.resolve("input.json"),out=dir.resolve("result.json");Files.writeString(in,input.toString());
            Path script=Path.of(props.getScript()).toAbsolutePath();if(!Files.isRegularFile(script))throw new Blocked("WORKER_SCRIPT_MISSING");
            Process p=new ProcessBuilder(props.getNode(),script.toString(),in.toString(),out.toString()).redirectErrorStream(true).redirectOutput(dir.resolve("worker.log").toFile()).start();
            running.put(id,p);
            if(!"RUNNING".equals(row(null,id).get("status")))stop(p);
            if(!p.waitFor(Math.max(10,props.getTimeoutSeconds()),TimeUnit.SECONDS)){stop(p);throw new Blocked("ANALYSIS_TIMEOUT");}
            if(p.exitValue()!=0)throw new Blocked(AnalysisWorkerDiagnostics.failureCode(Files.readString(dir.resolve("worker.log"))));
            if(!Files.isRegularFile(out)||Files.size(out)>8_000_000)throw new Blocked("INVALID_RESULT_SIZE");
            JsonNode result=JSON.readTree(Files.readString(out));AnalysisContract.validateResult(result,job.get("requirement_key").toString());
            finish(id,attempt,result,"codex");
        }catch(Exception e){String code=e instanceof Blocked?e.getMessage():"ANALYSIS_FAILED";jdbc.update("UPDATE analysis_bridge_jobs SET status=?,error_code=?,updated_at=NOW(),finished_at=NOW() WHERE id=? AND attempt_id=? AND status='RUNNING'",e instanceof Blocked?"BLOCKED":"FAILED",code,id,attempt);log.warn("[analysis] job={} code={}",id,code);}
        finally{Process p=running.remove(id);if(p!=null&&p.isAlive())stop(p);}
    }
    @Scheduled(fixedDelayString="${analysis-bridge.callback-poll-ms:10000}")
    public void sendCallbacks() {
        if(!ready||!callbackBusy.compareAndSet(false,true))return;
        callbacks.submit(()->{try {
            for (String client : props.getAllowedClients()) {
                String destination=props.getCallbacks().get(client);
                if(destination!=null && !destination.isBlank() && auth.keyOf(client)!=null)
                    jdbc.update("UPDATE analysis_bridge_jobs SET callback_state='PENDING',callback_next_at=NOW(),updated_at=NOW() WHERE client=? AND status='SUCCEEDED' AND callback_state='NOT_CONFIGURED'",client);
            }
            var rows=jdbc.queryForList("SELECT * FROM analysis_bridge_jobs WHERE status='SUCCEEDED' AND callback_state='PENDING' AND callback_next_at<=NOW() ORDER BY callback_next_at LIMIT 10");
            for(var job:rows)sendCallback(job);
        }catch(Exception e){log.warn("[analysis] 回传调度暂不可用");}finally{callbackBusy.set(false);}});
    }
    private void sendCallback(Map<String,Object> job) {
        String id=job.get("id").toString(),client=job.get("client").toString(),url=props.getCallbacks().get(client),key=auth.keyOf(client);
        if(url==null||url.isBlank()||key==null){jdbc.update("UPDATE analysis_bridge_jobs SET callback_state='NOT_CONFIGURED' WHERE id=? AND callback_state='PENDING'",id);return;}
        int attempts=((Number)job.get("callback_attempts")).intValue()+1;
        try {
            URI uri=URI.create(url);
            if(!Set.of("https","http").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)throw new IllegalArgumentException();
            Map<String,Object> body=new LinkedHashMap<>();
            for(String k:List.of("id","external_id","requirement_key","status","result_hash","result_source","callback_event_id"))body.put(k,job.get(k));
            JsonNode request=JSON.readTree(job.get("request_json").toString());body.put("expectedReportRevision",request.path("expectedReportRevision").asText(""));body.put("schemaVersion","1");body.put("businessGroup",request.path("businessGroup").asText(""));body.put("materialVersion",request.path("materialVersion").asText(""));body.put("requestHash",job.get("request_hash"));body.put("resultReady",true);body.put("resultUrl","/api/open/v1/analysis-jobs/"+id+"/result");
            String json=JSON.writeValueAsString(body),timestamp=Long.toString(Instant.now().getEpochSecond()),event=job.get("callback_event_id").toString();
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            String signature=HexFormat.of().formatHex(mac.doFinal((timestamp+"\n"+event+"\n"+json).getBytes(StandardCharsets.UTF_8)));
            HttpRequest req=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json").header("X-Analysis-Timestamp",timestamp).header("X-Analysis-Event",event).header("X-Analysis-Signature",signature).POST(HttpRequest.BodyPublishers.ofString(json)).build();
            int status=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build().send(req,HttpResponse.BodyHandlers.discarding()).statusCode();
            boolean done=status>=200&&status<300;
            jdbc.update("UPDATE analysis_bridge_jobs SET callback_state=?,callback_attempts=?,callback_http_status=?,callback_next_at=DATE_ADD(NOW(), INTERVAL ? SECOND),updated_at=NOW() WHERE id=? AND callback_event_id=? AND callback_state='PENDING'",done?"DELIVERED":attempts>=props.getCallbackMaxAttempts()?"FAILED":"PENDING",attempts,status,Math.min(3600,30*(1<<Math.min(attempts,6))),id,event);
        } catch(Exception e){jdbc.update("UPDATE analysis_bridge_jobs SET callback_state=?,callback_attempts=?,callback_next_at=DATE_ADD(NOW(), INTERVAL 60 SECOND) WHERE id=? AND callback_event_id=? AND callback_state='PENDING'",attempts>=props.getCallbackMaxAttempts()?"FAILED":"PENDING",attempts,id,job.get("callback_event_id"));}
    }
    /** Fetch into private refs; never checkout, reset, or force a user's local branch. */
    private Path ensureLocalRepo(String project,ObjectNode repo,ObjectNode input) throws Exception {
        String configured=props.getRepositories().get(project),url=props.getRepositoryUrls().get(project);
        if(configured==null||configured.isBlank()){
            if(url==null||url.isBlank())throw new Blocked("REPOSITORY_NOT_CONFIGURED");
            configured=Path.of(System.getProperty("user.home"),"mobile-standalone-repos",AnalysisContract.digest(project).substring(0,20)).toString();
        }
        Path root=Path.of(configured).toAbsolutePath().normalize();
        if(!Files.exists(root)){
            if(url==null||!url.matches("(https://|ssh://|git@).+"))throw new Blocked("REPOSITORY_UNAVAILABLE");
            Files.createDirectories(root.getParent());Path staging=root.resolveSibling(root.getFileName()+"-clone-"+UUID.randomUUID());
            try{git(root.getParent(),180,"clone","--no-checkout","--filter=blob:none","--no-tags","--",url,staging.toString());Files.move(staging,root);}
            catch(Exception e){throw new Blocked("REPOSITORY_CLONE_FAILED");}
        }
        if(!Files.exists(root.resolve(".git")))throw new Blocked("REPOSITORY_UNAVAILABLE");
        for(String field:List.of("baseRef","targetRef")){
            String ref=repo.path(field).asText();String normalized=ref.replaceFirst("^(refs/heads/|origin/)","");
            if(ref.matches("[a-fA-F0-9]{40}"))continue;
            String privateRef="refs/qa-analysis/"+AnalysisContract.digest(project+"\n"+normalized).substring(0,32);
            try{
                git(root,180,"fetch","--no-tags","origin","+refs/heads/"+normalized+":"+privateRef);
                repo.put(field.equals("baseRef")?"resolvedBaseRef":"resolvedTargetRef",privateRef);
                if(field.equals("baseRef")&&normalized.equals("master")){repo.put("latestMasterVerified",true);repo.put("baselineFetchedAt",Instant.now().toString());}
            }catch(Exception e){
                if(field.equals("baseRef")&&normalized.equals("master"))throw new Blocked("LATEST_MASTER_UNAVAILABLE");
                input.withArray("materialGaps").add("项目 "+project+" 的 "+ref+" 未同步成功，使用已有本地引用；无法确认是远程最新提交");
            }
        }
        return root.toRealPath();
    }
    private static void git(Path dir,long timeoutSec,String...args)throws Exception{
        List<String> cmd=new ArrayList<>();cmd.add("git");cmd.addAll(Arrays.asList(args));
        Path output=Files.createTempFile("analysis-git-",".log");
        ProcessBuilder pb=new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        Map<String,String> env=pb.environment();env.put("GIT_TERMINAL_PROMPT","0");env.put("GIT_SSH_COMMAND","ssh -oBatchMode=yes -oConnectTimeout=15");
        env.put("NO_PROXY","*.shdev.net,gitlab.shdev.net,127.0.0.1,localhost");env.put("no_proxy",env.get("NO_PROXY"));
        for(String name:List.of("http_proxy","https_proxy","HTTP_PROXY","HTTPS_PROXY","ALL_PROXY","all_proxy"))env.remove(name);
        Process proc=null;
        try{proc=pb.start();if(!proc.waitFor(timeoutSec,TimeUnit.SECONDS)){stop(proc);throw new IllegalStateException("Git 操作超时");}if(proc.exitValue()!=0)throw new IllegalStateException("Git 操作失败，请检查远程权限、分支和网络");}
        finally{if(proc!=null&&proc.isAlive())stop(proc);Files.deleteIfExists(output);}
    }

    private static void stop(Process p){p.descendants().forEach(ProcessHandle::destroy);p.destroy();try{if(!p.waitFor(2,TimeUnit.SECONDS)){p.descendants().forEach(ProcessHandle::destroyForcibly);p.destroyForcibly();}}catch(InterruptedException e){Thread.currentThread().interrupt();p.destroyForcibly();}}
    @PreDestroy public void close(){running.values().forEach(AnalysisJobService::stop);executor.shutdownNow();callbacks.shutdownNow();}
    private static class Blocked extends RuntimeException { Blocked(String code){super(code);} }
}
