package com.mobile.standalone.service.analysis;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.auth.OpenApiAuth;
import com.mobile.standalone.config.AnalysisBridgeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class AnalysisDefaultReuseTest {
 @Test void repeatedRequirementReturnsCompletedJobBeforePreparationOrInsert()throws Exception {
  var jdbc=mock(JdbcTemplate.class);var prep=mock(AnalysisPreparationService.class);
  var props=new AnalysisBridgeProperties();props.setAllowedClients(List.of("testforge"));
  var service=spy(new AnalysisJobService(jdbc,props,mock(OpenApiAuth.class),mock(WikiRequirementIngestService.class),prep));
  var mapper=new ObjectMapper();var input=mapper.readTree("{\"contractVersion\":\"2\",\"externalId\":\"new-request\",\"requirementKey\":\"APP-9227\",\"materialVersion\":\"different\"}");
  when(jdbc.queryForList(startsWith("SELECT id,status,request_json"),eq("testforge"),eq("APP-9227"))).thenReturn(List.of(Map.of("id","existing","status","SUCCEEDED","request_json","{\"businessGroup\":\"APP\"}")));
  doReturn(Map.of("id","existing","status","SUCCEEDED")).when(service).detail("testforge","existing");
  var output=service.create("testforge",input);assertEquals("existing",output.get("id"));assertEquals(true,output.get("reused"));assertEquals(false,output.get("newAnalysisPerformed"));
  verifyNoInteractions(prep);verify(jdbc,never()).update(startsWith("INSERT INTO analysis_bridge_jobs"),any(Object[].class));service.close();
 }
 @Test void unchangedSupplementDoesNotStartSdk()throws Exception {
  var jdbc=mock(JdbcTemplate.class);var props=new AnalysisBridgeProperties();props.getRepositories().put("pvp-galaxy","/configured");
  var service=new AnalysisJobService(jdbc,props,mock(OpenApiAuth.class),mock(WikiRequirementIngestService.class),mock(AnalysisPreparationService.class));
  when(jdbc.queryForList(anyString(),any(Object[].class))).thenAnswer(call->{String sql=call.getArgument(0);if(sql.startsWith("SELECT id FROM"))return List.of();return List.of(Map.of("id","source","status","SUCCEEDED","requirement_key","APP-9227","request_json","{\"repositories\":[{\"projectId\":\"pvp-galaxy\",\"targetRef\":\"feature\"}]}"));});
  var additions=new ObjectMapper().readTree("{\"repositories\":[{\"projectId\":\"pvp-galaxy\",\"targetRef\":\"feature\"}]}");
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.supplementSources("testforge","source",additions));
  verify(jdbc,never()).update(startsWith("INSERT INTO analysis_bridge_jobs"),any(Object[].class));service.close();
 }
}
