package com.mobile.standalone.service.analysis;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AnalysisResultReuseTest {
 private final ObjectMapper json=new ObjectMapper();
 @Test void reuseKeepsOriginalEvidenceAndRejectsNewScope(){
  var original=json.createObjectNode().put("requirementKey","APP-9227").put("businessGroup","APP");
  original.putArray("repositories").addObject().put("projectId","app-engine").put("baseRef","master").put("targetRef","feature");
  var request=json.createObjectNode().put("requirementKey","APP-9227").put("businessGroup","APP");
  assertDoesNotThrow(()->AnalysisResultReuse.validate(request,original,"SUCCEEDED"));
  assertThrows(Exception.class,()->AnalysisResultReuse.validate(request,original,"RUNNING"));
  request.put("requirementKey","APP-9344");assertThrows(Exception.class,()->AnalysisResultReuse.validate(request,original,"SUCCEEDED"));request.put("requirementKey","APP-9227");
  request.putArray("repositories").addObject().put("projectId","app-engine").put("targetRef","different");assertThrows(Exception.class,()->AnalysisResultReuse.validate(request,original,"SUCCEEDED"));request.remove("repositories");
  request.put("changeNotes","新需求");assertThrows(Exception.class,()->AnalysisResultReuse.validate(request,original,"SUCCEEDED"));request.remove("changeNotes");
  var result=json.createObjectNode().put("requirementKey","APP-9227");result.putArray("repositorySnapshots").addObject().put("targetSha","abc");result.putObject("analysisContext").put("jobId","source");
  var copy=AnalysisResultReuse.copy(result,"source","hash");assertEquals(result.path("repositorySnapshots"),copy.path("repositorySnapshots"));assertFalse(copy.path("resultReuse").path("newAnalysisPerformed").asBoolean(true));assertEquals("source",copy.path("resultReuse").path("sourceAnalysisContext").path("jobId").asText());assertFalse(result.has("resultReuse"));
 }
}
