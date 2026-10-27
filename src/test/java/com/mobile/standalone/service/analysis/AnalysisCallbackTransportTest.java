package com.mobile.standalone.service.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.auth.OpenApiAuth;
import com.mobile.standalone.config.AnalysisBridgeProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AnalysisCallbackTransportTest {
    @Test void callbackUsesStableEventSignedRawBodyAndOnlyAcknowledges2xx() throws Exception {
        exercise(204,"DELIVERED");
        exercise(503,"FAILED");
    }
    private void exercise(int response,String expected) throws Exception {
        var payload=new AtomicReference<String>();
        var signature=new AtomicReference<String>();
        var timestamp=new AtomicReference<String>();
        var event=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/completed",exchange->{
            payload.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            signature.set(exchange.getRequestHeaders().getFirst("X-Analysis-Signature"));
            timestamp.set(exchange.getRequestHeaders().getFirst("X-Analysis-Timestamp"));
            event.set(exchange.getRequestHeaders().getFirst("X-Analysis-Event"));
            exchange.sendResponseHeaders(response,-1);exchange.close();
        });
        server.start();
        var jdbc=mock(JdbcTemplate.class);
        var props=new AnalysisBridgeProperties();props.setCallbackMaxAttempts(1);
        props.getCallbacks().put("testforge","http://127.0.0.1:"+server.getAddress().getPort()+"/completed");
        var auth=mock(OpenApiAuth.class);when(auth.keyOf("testforge")).thenReturn("unit-test-only-key");
        var service=new AnalysisJobService(jdbc,props,auth,mock(WikiRequirementIngestService.class),mock(AnalysisPreparationService.class));
        try {
            var job=new HashMap<String,Object>();
            job.put("id","job-1");job.put("client","testforge");job.put("external_id","request-1");
            job.put("requirement_key","PVP-13859");job.put("status","SUCCEEDED");
            job.put("result_hash","hash");job.put("result_source","manual");job.put("callback_event_id","event-1");
            job.put("callback_attempts",0);job.put("request_hash","request-hash");
            job.put("request_json","{\"businessGroup\":\"PVP\",\"materialVersion\":\"v1\",\"expectedReportRevision\":\"r1\"}");
            var method=AnalysisJobService.class.getDeclaredMethod("sendCallback",Map.class);method.setAccessible(true);method.invoke(service,job);
            var body=new ObjectMapper().readTree(payload.get());
            assertTrue(body.path("resultReady").asBoolean());
            assertEquals("/api/open/v1/analysis-jobs/job-1/result",body.path("resultUrl").asText());
            assertEquals("r1",body.path("expectedReportRevision").asText());assertEquals("event-1",event.get());
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec("unit-test-only-key".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            assertEquals(HexFormat.of().formatHex(mac.doFinal((timestamp.get()+"\n"+event.get()+"\n"+payload.get()).getBytes(StandardCharsets.UTF_8))),signature.get());
            verify(jdbc).update(startsWith("UPDATE analysis_bridge_jobs SET callback_state=?"),eq(expected),eq(1),eq(response),anyInt(),eq("job-1"),eq("event-1"));
        } finally {service.close();server.stop(0);}
    }
}
