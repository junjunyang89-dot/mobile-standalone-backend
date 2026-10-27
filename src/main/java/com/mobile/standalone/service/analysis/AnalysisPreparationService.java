package com.mobile.standalone.service.analysis;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.mobile.standalone.config.AnalysisBridgeProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
public class AnalysisPreparationService {
    private final JiraRequirementClient jira;
    private final WikiRequirementIngestService wiki;
    private final FigmaRequirementIngestService figma;
    private final AnalysisBridgeProperties props;
    private static final ObjectMapper JSON=new ObjectMapper();
    private final ExecutorService executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(10),r->{Thread t=new Thread(r,"analysis-materials");t.setDaemon(true);return t;});
    public boolean configured(){return jira.configured();}
    public ObjectNode start(String client,JsonNode body){
        String raw=AnalysisContract.required(body,"requirementKey",300),key=raw.trim().toUpperCase(Locale.ROOT);
        if(key.contains("/"))key=key.substring(key.lastIndexOf('/')+1).split("[?#]",2)[0];
        if(!key.matches("[A-Z][A-Z0-9_]*-\\d+"))throw AnalysisContract.invalid("请填写 JIRA 单号或单子链接");
        String group=body.path("businessGroup").asText("");if(group.isBlank())group=key.substring(0,key.lastIndexOf('-'));
        if(!Set.of("APP","WEB","PVP").contains(group))throw AnalysisContract.invalid("无法确定业务组，请选择 APP、WEB 或 PVP");
        String id=UUID.randomUUID().toString();ObjectNode state=JSON.createObjectNode();state.put("id",id);state.put("client",client);state.put("requirementKey",key);state.put("businessGroup",group);state.put("status","PREPARING");state.put("createdAt",Instant.now().toString());
        try{Path dir=directory(id);Files.createDirectories(dir);try{Files.setPosixFilePermissions(dir,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));}catch(UnsupportedOperationException ignored){}save(dir,state);String issueKey=key;String businessGroup=group;executor.submit(()->collect(id,client,issueKey,businessGroup));return state;}
        catch(RejectedExecutionException e){state.put("status","FAILED");state.put("error","材料读取队列已满，请稍后重试");try{save(directory(id),state);}catch(Exception ignored){}throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"材料读取队列已满");}
        catch(java.io.IOException e){throw new IllegalStateException("材料目录无法写入");}
    }
    public ObjectNode get(String client,String id){
        try{Path file=directory(id).resolve("state.json");if(!Files.isRegularFile(file))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"材料读取任务不存在");ObjectNode state=(ObjectNode)JSON.readTree(Files.readString(file));
            if(!client.equals(state.path("client").asText()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"材料读取任务不存在");
            return state;
        }catch(java.io.IOException e){throw new IllegalStateException("材料读取状态不可用");}
    }
    public void validate(String client,JsonNode input){
        if(!input.has("preparationId"))return;
        ObjectNode state=get(client,input.path("preparationId").asText());
        if(!state.path("status").asText().equals("READY"))throw AnalysisContract.invalid("请等待材料读取完成");
        if(!state.path("requirementKey").asText().equals(input.path("requirementKey").asText())||!state.path("businessGroup").asText().equals(input.path("businessGroup").asText()))throw AnalysisContract.invalid("材料包与本单业务组、JIRA 不一致，请重新读取");
        if(!state.path("draft").path("materialVersion").asText().equals(input.path("materialVersion").asText()))throw AnalysisContract.invalid("材料版本与读取快照不一致，请重新读取");
    }
    public void attach(String client,ObjectNode input,Path jobDir)throws Exception{
        validate(client,input);if(!input.has("preparationId"))return;
        Path dir=directory(input.path("preparationId").asText());
        ObjectNode saved=(ObjectNode)JSON.readTree(Files.readString(dir.resolve("collected.json")));
        Set<String> selected=new HashSet<>();for(JsonNode m:input.path("materials"))if(m.has("url"))selected.add(m.path("url").asText());
        ArrayNode refs=input.putArray("materialSnapshots");
        for(JsonNode source:saved.path("sources"))if(selected.contains(source.path("url").asText()))refs.add(source.deepCopy());
        input.set("discoveryGaps",saved.path("discoveryGaps").deepCopy());

    }
    private void collect(String id,String client,String key,String group){
        ObjectNode state=get(client,id);Path dir=directory(id);
        try{
            JsonNode issue=jira.issue(key);Files.writeString(dir.resolve("jira.json"),issue.toString());
            ObjectNode draft=JiraAnalysisParser.parse(key,group,issue,props,jira.base());
            ArrayNode materials=(ArrayNode)draft.path("materials"),gaps=(ArrayNode)draft.path("discoveryGaps");
            for(JsonNode m:materials)if(m.has("content")&&m.path("content").asText().isBlank())((ObjectNode)m).remove("content");
            ArrayNode sources=JSON.createArrayNode(),summary=state.putArray("sources");
            ObjectNode jiraStatus=summary.addObject();jiraStatus.put("type","jira");jiraStatus.put("title",draft.path("summary").asText());jiraStatus.put("status","ready");jiraStatus.put("url",jira.base()+"/browse/"+key);
            int count=0;
            for(JsonNode m:materials){
                String url=m.path("url").asText("");if(url.isBlank())continue;
                boolean isWiki=WikiPageIds.extract(url).isPresent()&&sameHost(url,wiki.baseUrl());boolean isFigma=isFigma(url);
                if(!isWiki&&!isFigma){
                    if(sameHost(url,wiki.baseUrl())){var row=summary.addObject();row.put("url",url);row.put("type","wiki");row.put("status","failed");row.put("title","Wiki 链接尚未解析");row.putArray("gaps").add("请使用包含 pageId 的页面链接");gaps.add("Wiki 链接未获取，请补充 pageId："+url);}
                    continue;
                }
                if(++count>12){gaps.add("关联文档超过 12 份，超出部分未读取，请缩小范围");break;}
                ObjectNode source=sources.addObject();source.put("url",url);source.put("type",isWiki?"wiki":"figma");
                ObjectNode snap;
                if(isWiki){Path pageDir=dir.resolve("wiki").resolve(WikiPageIds.extract(url).orElseThrow());Files.createDirectories(pageDir);try{snap=wiki.fetch(WikiPageIds.extract(url).orElseThrow(),pageDir);}catch(Exception e){snap=JSON.createObjectNode();snap.putArray("gaps").add("Wiki 获取失败：请检查配置、权限、网络和证书");snap.put("status","failed");}}
                else snap=figma.fetch(url,dir.resolve("figma").resolve(AnalysisContract.digest(url).substring(0,16)));
                String status=snap.path("status").asText(snap.path("gaps").isEmpty()?"ready":"partial");source.put("status",status);
                Path snapshot=dir.resolve("source-"+count+".json");Files.writeString(snapshot,snap.toString());source.put("snapshotPath",snapshot.toString());source.put("snapshotHash",AnalysisContract.hash(snap));
                var row=summary.addObject();row.put("url",url);row.put("type",isWiki?"wiki":"figma");row.put("title",isWiki?snap.path("page").path("title").asText("Wiki 需求"):snap.path("name").asText("Figma 设计"));row.put("status",status);row.set("gaps",snap.path("gaps"));
                for(String field:List.of("tableCount","codeBlockCount"))if(snap.has(field))row.set(field,snap.get(field));
                row.put("imageCount",isWiki?snap.path("images").size():(snap.has("imagePath")?1:0));row.put("commentCount",snap.path("comments").size());row.put("attachmentCount",snap.path("attachments").size());
                // SQL attachments only. Binary JIRA attachments remain explicitly unparsed.
            }
            for(JsonNode attachment:issue.path("fields").path("attachment")){
                String name=attachment.path("filename").asText();String url=attachment.path("content").asText();
                if(name.toLowerCase(Locale.ROOT).endsWith(".sql")||name.toLowerCase(Locale.ROOT).endsWith(".txt")){
                    if(materials.size()>=50){gaps.add("JIRA 文本附件超出材料上限，未读取："+name);continue;}
                    try{String content=new String(jira.bytes(url,500_000),StandardCharsets.UTF_8);var m=materials.addObject();m.put("id","jira-attachment-"+attachment.path("id").asText());m.put("type",name.toLowerCase(Locale.ROOT).endsWith(".sql")?"migration":"requirement");m.put("title",name);m.put("content",content);m.put("url",url);}
                    catch(Exception e){gaps.add("JIRA 文本附件未读取："+name);}
                }else gaps.add("JIRA 附件尚未解析："+name+"（保留于附件清单）");
            }
            if(draft.toString().length()>1_800_000)gaps.add("本单材料较大，可能超过任务提交上限；请精简无关评论或将长文档改为关联链接");
            ObjectNode collected=JSON.createObjectNode();collected.set("sources",sources);collected.set("discoveryGaps",gaps);collected.set("jiraAttachments",draft.path("jiraAttachments"));Files.writeString(dir.resolve("collected.json"),collected.toString());
            state.set("draft",draft);state.put("status","READY");state.put("finishedAt",Instant.now().toString());save(dir,state);
        }catch(Exception e){state.put("status","FAILED");state.put("error",e instanceof IllegalStateException||e instanceof ResponseStatusException?Objects.toString(e.getMessage(),"材料读取失败"):"材料读取失败，请检查配置、权限、网络或证书");try{save(dir,state);}catch(Exception ignored){}}
    }
    private static boolean sameHost(String url,String base){try{return Objects.equals(java.net.URI.create(url).getHost(),java.net.URI.create(base).getHost());}catch(Exception e){return false;}}
    private static boolean isFigma(String url){try{return Set.of("www.figma.com","figma.com","app.figma.com").contains(java.net.URI.create(url).getHost());}catch(Exception e){return false;}}
    private Path directory(String id){try{UUID.fromString(id);}catch(Exception e){throw AnalysisContract.invalid("材料包 ID 不正确");}return Path.of(props.getWorkspace()).toAbsolutePath().normalize().resolve("preparations").resolve(id);}
    private static void save(Path dir,ObjectNode value)throws java.io.IOException{Path tmp=dir.resolve("state.tmp");Files.writeString(tmp,value.toString());Files.move(tmp,dir.resolve("state.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
    @PreDestroy public void close(){executor.shutdownNow();}
}
