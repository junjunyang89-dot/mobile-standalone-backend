package com.mobile.standalone.service.analysis;

import com.fasterxml.jackson.databind.*;
import com.mobile.standalone.auth.OpenApiAuth;
import com.mobile.standalone.config.AnalysisBridgeProperties;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AnalysisBridgeTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private JsonNode input(String external) throws Exception {return JSON.readTree("{\"externalId\":\""+external+"\",\"requirementKey\":\"APP-9119\",\"repositories\":[]}");}
    private JsonNode result() throws Exception {return JSON.readTree("{\"schemaVersion\":\"1\",\"requirementKey\":\"APP-9119\",\"sources\":[],\"findings\":[],\"cases\":[],\"apis\":[]}");}
    @Test void callbackRetryCannotTargetAnotherClientsJob() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        AnalysisJobService service=new AnalysisJobService(jdbc,new AnalysisBridgeProperties(),mock(OpenApiAuth.class),mock(WikiRequirementIngestService.class),mock(AnalysisPreparationService.class));
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of());
        var error=assertThrows(ResponseStatusException.class,()->service.retryCallback("testforge","other-job"));
        assertEquals(404,error.getStatusCode().value());
        verify(jdbc,never()).update(startsWith("UPDATE analysis_bridge_jobs SET callback_state"),any(Object[].class));
        service.close();
    }
    @Test void structuredContractChecksEvidenceAndFlowReferences() throws Exception {
        var body=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("docs/pvp-13859-result-format-1.1.example.json")));
        var valid=body;assertDoesNotThrow(()->AnalysisContract.validateResult(valid,"PVP-13859"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)body.path("findings").get(0).path("codeEvidence").get(0)).put("commitSha","master");
        var badEvidence=body;assertThrows(ResponseStatusException.class,()->AnalysisContract.validateResult(badEvidence,"PVP-13859"));
        body=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("docs/pvp-13859-result-format-1.1.example.json")));
        ((com.fasterxml.jackson.databind.node.ArrayNode)body.path("businessFlow").path("steps").get(0).path("apiIds")).add("missing-api");
        var invalid=body;
        assertThrows(ResponseStatusException.class,()->AnalysisContract.validateResult(invalid,"PVP-13859"));
    }
    @Test void revisionCannotPublishForUnfinishedTask() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        AnalysisJobService service=new AnalysisJobService(jdbc,new AnalysisBridgeProperties(),mock(OpenApiAuth.class),mock(WikiRequirementIngestService.class),mock(AnalysisPreparationService.class));
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(Map.of("status","RUNNING")));
        assertThrows(ResponseStatusException.class,()->service.publishResultRevision("source",JSON.createObjectNode()));
        verify(jdbc,never()).update(startsWith("INSERT INTO analysis_bridge_jobs"),any(Object[].class));service.close();
    }
    @Test void canonicalHashIgnoresObjectOrderButNotInputChanges() throws Exception {
        assertEquals(AnalysisContract.hash(JSON.readTree("{\"a\":1,\"b\":2}")),AnalysisContract.hash(JSON.readTree("{\"b\":2,\"a\":1}")));
        assertNotEquals(AnalysisContract.hash(input("a")),AnalysisContract.hash(input("b")));
    }
    @Test void rejectsCommandsHostPathsAndArbitraryCallbacks() throws Exception {
        for(String field:List.of("command","workspace","callbackUrl")) {
            var body=input("a").deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)body).put(field,"untrusted");
            assertThrows(ResponseStatusException.class,()->AnalysisContract.validateRequest(body));
        }
        var body=JSON.readTree("{\"externalId\":\"a\",\"requirementKey\":\"APP-1\",\"repositories\":[{\"projectId\":\"p\",\"baseRef\":\"master\",\"targetRef\":\"f\",\"localPath\":\"/private\"}]}");
        assertThrows(ResponseStatusException.class,()->AnalysisContract.validateRequest(body));
    }
    @Test void resultRejectsWrongRequirementDuplicateIdsAndDanglingReferences() throws Exception {
        assertThrows(ResponseStatusException.class,()->AnalysisContract.validateResult(result(),"APP-2"));
        JsonNode bad=JSON.readTree("{\"schemaVersion\":\"1\",\"requirementKey\":\"APP-9119\",\"sources\":[],\"findings\":[],\"apis\":[],\"cases\":[{\"id\":\"TC1\",\"title\":\"t\",\"apiIds\":[\"missing\"]}]}");
        assertThrows(ResponseStatusException.class,()->AnalysisContract.validateResult(bad,"APP-9119"));
        assertDoesNotThrow(()->AnalysisContract.validateResult(result(),"APP-9119"));
    }
    @Test void unconfiguredExecutorCreatesBlockedJobInsteadOfFakeSuccess() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);AnalysisBridgeProperties props=new AnalysisBridgeProperties();
        AnalysisJobService service=new AnalysisJobService(jdbc,props,mock(OpenApiAuth.class),mock(WikiRequirementIngestService.class),mock(AnalysisPreparationService.class));
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenAnswer(call->{String sql=call.getArgument(0);return sql.contains("SELECT *")?List.of(new HashMap<>(Map.of("id","job","status","BLOCKED","request_json","{}"))):List.of();});
        var created=service.create("testforge",input("a"));
        assertEquals("BLOCKED",created.get("status"));assertEquals(false,created.get("duplicate"));
        verify(jdbc).update(startsWith("INSERT INTO analysis_bridge_jobs"),any(Object[].class));service.close();
    }
    @Test void duplicateIdWithDifferentBodyConflicts() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);AnalysisJobService service=new AnalysisJobService(jdbc,new AnalysisBridgeProperties(),mock(OpenApiAuth.class),mock(WikiRequirementIngestService.class),mock(AnalysisPreparationService.class));
        when(jdbc.queryForList(startsWith("SELECT id,request_hash"),any(Object[].class))).thenReturn(List.of(Map.of("id","j","request_hash","different")));
        ResponseStatusException e=assertThrows(ResponseStatusException.class,()->service.create("testforge",input("a")));
        assertEquals(409,e.getStatusCode().value());service.close();
    }
    @Test void callerMustBeAllowedAndCannotReadOtherClientsJob() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);AnalysisJobService service=new AnalysisJobService(jdbc,new AnalysisBridgeProperties(),mock(OpenApiAuth.class),mock(WikiRequirementIngestService.class),mock(AnalysisPreparationService.class));
        assertEquals(403,assertThrows(ResponseStatusException.class,()->service.allowClient("other")).getStatusCode().value());
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->service.detail("testforge","other-job")).getStatusCode().value());
        verify(jdbc).queryForList("SELECT * FROM analysis_bridge_jobs WHERE id=? AND client=?","other-job","testforge");service.close();
    }
    @Test void importCannotOverwriteCancelledOrCompletedJob() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);AnalysisJobService service=new AnalysisJobService(jdbc,new AnalysisBridgeProperties(),mock(OpenApiAuth.class),mock(WikiRequirementIngestService.class),mock(AnalysisPreparationService.class));
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(Map.of("requirement_key","APP-9119","status","CANCELLED")));
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(0);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.importResult("j",result())).getStatusCode().value());service.close();
    }

    private com.fasterxml.jackson.databind.node.ObjectNode v2() throws Exception {
        var body=(com.fasterxml.jackson.databind.node.ObjectNode)input("snapshot-a");
        body.put("contractVersion","2");body.put("businessGroup","APP");body.put("materialVersion","v1");return body;
    }
    @Test void structuredMaterialsAcceptSchemaAndMigrationButRejectForeignRequirement() throws Exception {
        var body=v2();
        var m=body.putArray("materials").addObject();m.put("id","sql1");m.put("title","订单结构");m.put("type","schema");m.put("content","CREATE TABLE orders(id BIGINT PRIMARY KEY);");
        assertDoesNotThrow(()->AnalysisContract.validateRequest(body));
        m.put("requirementKey","APP-99");assertThrows(ResponseStatusException.class,()->AnalysisContract.validateRequest(body));
    }
    @Test void newContractRequiresGroupVersionAndUniqueMaterialIds() throws Exception {
        var body=v2();body.remove("businessGroup");assertThrows(ResponseStatusException.class,()->AnalysisContract.validateRequest(body));
        body.put("businessGroup","OTHER");assertThrows(ResponseStatusException.class,()->AnalysisContract.validateRequest(body));
        body.put("businessGroup","APP");body.remove("materialVersion");assertThrows(ResponseStatusException.class,()->AnalysisContract.validateRequest(body));
        body.put("materialVersion","v1");
        var arr=body.putArray("materials");
        for(int i=0;i<2;i++){var m=arr.addObject();m.put("id","same");m.put("title","需求");m.put("type","requirement");m.put("content","正文");}
        assertThrows(ResponseStatusException.class,()->AnalysisContract.validateRequest(body));
    }
    @Test void rejectsMaterialLocalPathsCredentialsAndInjectedHistory() throws Exception {
        var body=v2();var m=body.putArray("materials").addObject();m.put("id","d1");m.put("title","设计");m.put("type","design");m.put("url","https://user:secret@example.com/design");
        assertThrows(ResponseStatusException.class,()->AnalysisContract.validateRequest(body));
        m.put("url","https://example.com/design");m.put("localPath","/private/file");assertThrows(ResponseStatusException.class,()->AnalysisContract.validateRequest(body));
        m.remove("localPath");body.putObject("previousResult");assertThrows(ResponseStatusException.class,()->AnalysisContract.validateRequest(body));
    }
    @Test void historyCannotCrossRequirementOrGroupAndMustBeCompleted() throws Exception {
        var body=v2();
        assertDoesNotThrow(()->AnalysisContract.validatePrevious(body,"APP-9119",body,"SUCCEEDED"));
        assertThrows(ResponseStatusException.class,()->AnalysisContract.validatePrevious(body,"APP-22",body,"SUCCEEDED"));
        var other=body.deepCopy();other.put("businessGroup","WEB");
        assertThrows(ResponseStatusException.class,()->AnalysisContract.validatePrevious(body,"APP-9119",other,"SUCCEEDED"));
        assertThrows(ResponseStatusException.class,()->AnalysisContract.validatePrevious(body,"APP-9119",body,"RUNNING"));
    }
    @Test void serviceRejectsCrossJiraHistoryBeforeInsertingJob() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);AnalysisJobService service=new AnalysisJobService(jdbc,new AnalysisBridgeProperties(),mock(OpenApiAuth.class),mock(WikiRequirementIngestService.class),mock(AnalysisPreparationService.class));
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(Map.of("requirement_key","APP-22","request_json",v2().toString(),"status","SUCCEEDED")));
        var body=v2();body.put("previousJobId","other-job");
        assertThrows(ResponseStatusException.class,()->service.create("testforge",body));
        verify(jdbc,never()).update(startsWith("INSERT INTO"),any(Object[].class));service.close();
    }
    @Test void materialsAndVersionsParticipateInIdempotencyHash() throws Exception {
        var a=v2();var changed=a.deepCopy();changed.put("materialVersion","v2");assertNotEquals(AnalysisContract.hash(a),AnalysisContract.hash(changed));
        changed=a.deepCopy();changed.putArray("materials").addObject().put("content","SQL changed");assertNotEquals(AnalysisContract.hash(a),AnalysisContract.hash(changed));
    }
}
