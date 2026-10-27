package com.mobile.standalone.service.analysis;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class AnalysisContract {
    private static final ObjectMapper JSON = new ObjectMapper();
    private AnalysisContract() {}
    public static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    public static String required(JsonNode body, String key, int max) {
        if (body == null || !body.path(key).isTextual()) throw invalid("请填写 " + key);
        String value = body.path(key).asText().trim();
        if (value.isEmpty() || value.length() > max) throw invalid(key + " 为空或过长");
        return value;
    }
    public static void validateRequest(JsonNode body) {
        if (body == null || !body.isObject() || body.toString().length() > 2_000_000) throw invalid("任务必须为 JSON 对象，且不超过 2MB");
        required(body, "externalId", 160);
        String key = required(body, "requirementKey", 80);
        if (!key.matches("[A-Za-z][A-Za-z0-9_]*-\\d+")) throw invalid("requirementKey 必须是需求单号");
        if (body.has("callbackUrl") || body.has("command") || body.has("workspace")) throw invalid("任务不能指定执行命令、工作目录或回调地址");
        boolean v2 = body.has("contractVersion");
        if (v2 && !"2".equals(required(body,"contractVersion",10))) throw invalid("仅支持 contractVersion=2");
        if(v2 && !key.equals(key.toUpperCase(Locale.ROOT))) throw invalid("requirementKey 请使用大写 JIRA 单号");
        if(body.has("expectedReportRevision")) required(body,"expectedReportRevision",160);
        if (v2 || body.has("businessGroup")) {
            if (!Set.of("APP","WEB","PVP").contains(required(body,"businessGroup",10))) throw invalid("businessGroup 必须为 APP、WEB 或 PVP");
        }
        if (v2 || body.has("materialVersion")) required(body,"materialVersion",160);
        if (body.has("preparationId")) required(body,"preparationId",64);
        if (body.has("previousJobId")) required(body,"previousJobId",64);
        if (body.has("previousResult") || body.has("resolvedRepositories") || body.has("analysisContext") || body.has("wikiSnapshots") || body.has("materialSnapshots") || body.has("materialCollectionPath") || body.has("discoveryGaps")) throw invalid("历史结果及执行上下文只能由服务端注入");
        JsonNode materials=body.path("materials");
        if (!materials.isMissingNode()) {
            if (!materials.isArray() || materials.size()>50) throw invalid("materials 必须为数组，最多 50 项");
            Set<String> materialIds=new HashSet<>();
            for (JsonNode m:materials) {
                String type=required(m,"type",40);
                if (v2) {
                    if (!materialIds.add(required(m,"id",160))) throw invalid("材料 ID 重复");
                    required(m,"title",300);
                    if (!Set.of("requirement","design","schema","migration","sampleData","apiSpec","configuration","changeNote").contains(type)) throw invalid("不支持的材料类型");
                    if (m.has("requirementKey") && !key.equals(required(m,"requirementKey",80))) throw invalid("材料需求单号不一致");
                }
                if (m.has("content")) required(m,"content",500_000);
                if (m.has("url")) {
                    String url=required(m,"url",4000);
                    try { var u=java.net.URI.create(url); if (!Set.of("http","https").contains(u.getScheme()) || u.getHost()==null || u.getUserInfo()!=null) throw new IllegalArgumentException(); }
                    catch(Exception e) { throw invalid("材料链接必须为不含账号密码的 HTTP(S) 地址"); }
                }
                if (!m.has("content") && !m.has("url")) throw invalid("材料需要 content 或 url");
                if (m.has("localPath") || m.has("command")) throw invalid("材料不能指定本地路径或命令");
            }
        }
        JsonNode repos = body.path("repositories");
        if (!repos.isMissingNode() && (!repos.isArray() || repos.size() > 30)) throw invalid("repositories 必须为数组，最多 30 个仓库");
        Set<String> identities = new HashSet<>();
        if (repos.isArray()) for (JsonNode r : repos) {
            String project = required(r, "projectId", 160), base = required(r, "baseRef", 240), head = required(r, "targetRef", 240);
            for (String shaField:List.of("baseSha","targetSha","previousTargetSha")) if(r.has(shaField) && !required(r,shaField,40).matches("[a-fA-F0-9]{40}")) throw invalid(shaField+" 必须为完整提交 SHA");
            if (r.has("localPath") || r.has("command") || r.has("resolvedBaseRef") || r.has("resolvedTargetRef")) throw invalid("仓库路径由服务端配置，不能由外部任务指定");
            if (base.startsWith("-") || head.startsWith("-") || base.chars().anyMatch(Character::isISOControl) || head.chars().anyMatch(Character::isISOControl)) throw invalid("分支格式错误");
            if (!identities.add(project + "\n" + head)) throw invalid("项目与提测分支重复");
        }
    }
    public static void validatePrevious(JsonNode request,String previousKey,JsonNode previousRequest,String previousStatus) {
        if (!request.path("requirementKey").asText().equals(previousKey) || !request.path("businessGroup").asText("").equals(previousRequest.path("businessGroup").asText(""))) throw invalid("历史任务必须属于同一业务组、同一 JIRA 单");
        if (!"SUCCEEDED".equals(previousStatus)) throw invalid("只能引用已完成的历史分析");
    }
    public static void validateResult(JsonNode result, String requirementKey) {
        if (result == null || !result.isObject() || result.toString().length() > 8_000_000) throw invalid("结果必须为 JSON 对象且不超过 8MB");
        required(result, "schemaVersion", 30);
        if (!requirementKey.equals(required(result, "requirementKey", 80))) throw invalid("结果需求单号与任务不一致");
        Map<String, Set<String>> ids = new HashMap<>();
        for (String type : List.of("findings", "cases", "apis", "sources")) {
            JsonNode arr = result.path(type);
            if (!arr.isArray()) throw invalid("结果缺少数组 " + type);
            Set<String> seen = new HashSet<>();
            for (JsonNode item : arr) if (!seen.add(required(item, "id", 160))) throw invalid(type + " 存在重复 ID");
            ids.put(type, seen);
        }
        for (JsonNode item : result.path("cases")) {
            required(item, "title", 1000);
            checkRefs(item, "findingIds", ids.get("findings"));
            checkRefs(item, "apiIds", ids.get("apis"));
        }
        for (JsonNode item : result.path("findings")) {
            required(item, "title", 1000);
            checkRefs(item, "relatedCaseIds", ids.get("cases"));
        }
        for (JsonNode item : result.path("apis")) checkRefs(item, "relatedCaseIds", ids.get("cases"));
        if(result.has("resultFormatVersion")) validateStructured(result,ids);

    }
    private static void validateStructured(JsonNode result, Map<String,Set<String>> ids) {
        if(!"1.1".equals(result.path("resultFormatVersion").asText()))throw invalid("不支持的结果格式版本");
        JsonNode flow=result.path("businessFlow");
        if(!flow.isObject() || !flow.path("steps").isArray() || !flow.path("branches").isArray())throw invalid("缺少结构化业务链路");
        Set<String> steps=new HashSet<>();
        for(JsonNode step:flow.path("steps")) {
            if(!steps.add(required(step,"id",160)))throw invalid("业务链路步骤ID重复");
            required(step,"title",1000);checkRefs(step,"apiIds",ids.get("apis"));checkRefs(step,"evidenceIds",ids.get("sources"));
        }
        for(JsonNode branch:flow.path("branches")) {
            checkRefs(branch,"findingIds",ids.get("findings"));checkRefs(branch,"caseIds",ids.get("cases"));
        }
        for(JsonNode finding:result.path("findings")) {
            required(finding,"recommendation",100000);required(finding,"rationale",100000);
            if(!finding.path("verification").isObject() || !finding.path("codeEvidence").isArray())throw invalid("问题缺少验证方法或代码证据数组");
            checkRefs(finding,"sourceIds",ids.get("sources"));
            for(JsonNode evidence:finding.path("codeEvidence")) {
                required(evidence,"projectId",160);
                if(!required(evidence,"commitSha",40).matches("[a-fA-F0-9]{40}"))throw invalid("代码证据必须指定完整提交SHA");
                String file=required(evidence,"filePath",1000);
                if(file.startsWith("/") || file.contains("..") || file.contains("\\"))throw invalid("代码证据必须使用仓库相对路径");
                if(!evidence.path("startLine").isIntegralNumber() || !evidence.path("endLine").isIntegralNumber() || evidence.path("startLine").asInt()<1 || evidence.path("endLine").asInt()<evidence.path("startLine").asInt())throw invalid("代码证据行号错误");
                required(evidence,"snippet",100000);checkRefs(evidence,"sourceIds",ids.get("sources"));
            }
        }
    }
    private static void checkRefs(JsonNode item, String key, Set<String> ids) {
        if (!item.has(key)) return;
        if (!item.path(key).isArray()) throw invalid(key + " 必须为数组");
        for (JsonNode id : item.path(key)) if (!id.isTextual() || !ids.contains(id.asText())) throw invalid(key + " 引用了不存在的 ID");
    }
    public static String hash(JsonNode value) { return digest(canonical(value).toString()); }
    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode out = JSON.createObjectNode(); List<String> keys = new ArrayList<>(); value.fieldNames().forEachRemaining(keys::add); Collections.sort(keys);
            keys.forEach(k -> out.set(k, canonical(value.get(k)))); return out;
        }
        if (value.isArray()) { ArrayNode out = JSON.createArrayNode(); value.forEach(v -> out.add(canonical(v))); return out; }
        return value;
    }
    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
