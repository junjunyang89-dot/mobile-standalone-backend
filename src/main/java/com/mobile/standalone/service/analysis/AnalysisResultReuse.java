package com.mobile.standalone.service.analysis;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
/** Explicit reuse is a reference to an existing snapshot, never a claim of fresh analysis. */
public final class AnalysisResultReuse {
 private AnalysisResultReuse(){}
 public static void validate(JsonNode request,JsonNode source,String status) {
  if(!"SUCCEEDED".equals(status))throw AnalysisContract.invalid("指定报告尚未分析完成，未触发新的分析");
  for(String field:List.of("requirementKey","businessGroup"))if(!request.path(field).asText().equals(source.path(field).asText()))throw AnalysisContract.invalid("只能复用同一业务组、同一 JIRA 的报告");
  for(String field:List.of("materials","repositories"))if(request.has(field)&&request.path(field).size()>0&&!AnalysisContract.hash(request.path(field)).equals(AnalysisContract.hash(source.path(field))))throw AnalysisContract.invalid("复用请求的"+field+"与原报告材料不一致；请仅提交复用引用，或另行明确发起新分析");
  if(request.hasNonNull("changeNotes")&&!request.path("changeNotes").asText().isBlank()&&!request.path("changeNotes").asText().equals(source.path("changeNotes").asText()))throw AnalysisContract.invalid("复用请求包含新的说明，未触发分析；请确认范围");
  if(request.has("previousJobId")||request.has("preparationId"))throw AnalysisContract.invalid("复用已有结果不能同时指定增量分析或重新采集材料");
 }
 public static ObjectNode copy(JsonNode result,String sourceId,String sourceHash) {
  var out=(ObjectNode)result.deepCopy();var provenance=out.putObject("resultReuse");
  provenance.put("sourceJobId",sourceId).put("sourceResultHash",sourceHash).put("mode","EXPLICIT_SNAPSHOT").put("newAnalysisPerformed",false);
  if(result.has("analysisContext"))provenance.set("sourceAnalysisContext",result.path("analysisContext").deepCopy());
  return out;
 }
}
