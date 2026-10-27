package com.mobile.standalone.service.analysis;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.mobile.standalone.config.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JiraMaterialFlowTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    @TempDir Path temp;
    @Test void parserOnlyIncludesThisIssuesExplicitMappedProjectsAndNeverGuessesBaseline() throws Exception {
        var props=new AnalysisBridgeProperties();props.getRepositories().put("app-engine","/server/engine");props.getRepositories().put("unused","/server/unused");props.getRepositoryAliases().put("engine","app-engine");
        JsonNode issue=JSON.readTree("{\"fields\":{\"summary\":\"反馈优化\",\"description\":\"engine: feature/feedback\\nunknown: dev/branch\\nhttps://wiki.shdev.net/pages/viewpage.action?pageId=119717563\"},\"analysisComments\":[]}");
        var draft=JiraAnalysisParser.parse("PVP-1","PVP",issue,props,"https://jira.example");
        assertEquals(1,draft.path("repositories").size());var repo=draft.path("repositories").get(0);assertEquals("app-engine",repo.path("projectId").asText());assertEquals("feature/feedback",repo.path("targetRef").asText());assertEquals("",repo.path("baseRef").asText());assertTrue(draft.path("discoveryGaps").toString().contains("unknown"));
        assertTrue(draft.path("materials").toString().contains("119717563"));
    }
    @Test void commentsStaySeparateWithProvenanceAndConfiguredBaseline()throws Exception{
        var props=new AnalysisBridgeProperties();props.getRepositories().put("engine","/engine");props.getRepositoryBaselines().put("engine","release/v1");
        var issue=JSON.readTree("{\"fields\":{\"description\":\"验收条件\"},\"analysisComments\":[{\"id\":\"c1\",\"body\":\"engine: dev/next\",\"author\":{\"displayName\":\"开发\"}}]}");
        var result=JiraAnalysisParser.parse("PVP-2","PVP",issue,props,"https://jira.example");
        assertEquals("requirement",result.path("materials").get(0).path("type").asText());assertEquals("changeNote",result.path("materials").get(1).path("type").asText());assertEquals("release/v1",result.path("repositories").get(0).path("baseRef").asText());
    }
    @Test void mimeDetectsOctetStreamPngAndQuotedUtf8Html(){
        String mime="MIME-Version: 1.0\r\nContent-Type: multipart/related; boundary=\"part\"\r\n\r\n--part\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\n<p>=E9=9C=80=E6=B1=82</p>\r\n--part\r\nContent-Type: application/octet-stream\r\nContent-Location: file:///C:/asset1\r\nContent-Transfer-Encoding: base64\r\n\r\niVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aH1cAAAAASUVORK5CYII=\r\n--part--\r\n";
        var parsed=WikiMimeExportParser.parse(mime.getBytes(StandardCharsets.US_ASCII));assertTrue(parsed.html.contains("需求"));assertEquals(1,parsed.images.size());assertEquals((byte)0x89,parsed.images.get("asset1")[0]);
    }
    @Test void tablesCodeAndDemoScopeArePreservedAndPlaceholdersExcluded(){
        String view="<h1>人工智能DEMO</h1><h2>子章节</h2><style>bad-style</style><table><tr><td>A</td><td></td></tr></table><pre>if (x) {\n  run();\n}</pre><img src=\"/placeholder-medium-file.png\"/>";
        var snap=WikiRequirementParser.snapshot("123",JSON.createObjectNode(),"",view,List.of(),List.of(),"rest");
        JsonNode section=snap.path("sections").get(2);assertTrue(section.path("unconfirmedDemo").asBoolean());assertEquals(2,section.path("tables").get(0).get(0).size());assertTrue(section.path("codeBlocks").get(0).asText().contains("\n"));assertFalse(section.path("text").asText().contains("bad-style"));assertTrue(snap.path("images").get(0).path("placeholder").asBoolean());
    }
    @Test void figmaRequiresExplicitNodeAndTrustedHost(){
        var node=FigmaRequirementIngestService.nodeRef("https://www.figma.com/design/abcDEF123/test?node-id=14212-33");assertEquals("14212:33",node.node());
        assertThrows(IllegalArgumentException.class,()->FigmaRequirementIngestService.nodeRef("https://evil.example/design/abc/test?node-id=1-2"));
        assertThrows(IllegalArgumentException.class,()->FigmaRequirementIngestService.nodeRef("https://www.figma.com/design/abc/test"));
    }
    @Test void jiraReadsEveryCommentPage()throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger pages=new AtomicInteger();
        server.createContext("/rest/api/2/issue/PVP-1",exchange->{
            String response;
            if(exchange.getRequestURI().getPath().endsWith("/comment")){
                pages.incrementAndGet();int start=exchange.getRequestURI().getQuery().contains("startAt=100")?100:exchange.getRequestURI().getQuery().contains("startAt=50")?50:0;
                ObjectNode data=JSON.createObjectNode();data.put("total",101);ArrayNode arr=data.putArray("comments");for(int i=start;i<Math.min(start+50,101);i++)arr.addObject().put("id",i);response=data.toString();
            }else response="{\"key\":\"PVP-1\",\"fields\":{}}";
            byte[] data=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);exchange.close();
        });server.start();
        try{var props=new ReleaseDocProperties();props.setJiraBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());props.setUsername("test-user");props.setPassword("test-only");var result=new JiraRequirementClient(props).issue("PVP-1");assertEquals(101,result.path("analysisComments").size());assertEquals(3,pages.get());}
        finally{server.stop(0);}
    }
    @Test void preparationRejectsAnotherClientJiraGroupOrMaterialVersion()throws Exception{
        var props=new AnalysisBridgeProperties();props.setWorkspace(temp.toString());var service=new AnalysisPreparationService(mock(JiraRequirementClient.class),mock(WikiRequirementIngestService.class),mock(FigmaRequirementIngestService.class),props);
        String id=UUID.randomUUID().toString();Path dir=temp.resolve("preparations").resolve(id);Files.createDirectories(dir);Files.writeString(dir.resolve("state.json"),"{\"status\":\"READY\",\"client\":\"testforge\",\"requirementKey\":\"PVP-1\",\"businessGroup\":\"PVP\",\"draft\":{\"materialVersion\":\"v1\"}}");
        ObjectNode body=JSON.createObjectNode();body.put("preparationId",id);body.put("requirementKey","PVP-1");body.put("businessGroup","PVP");body.put("materialVersion","v1");assertDoesNotThrow(()->service.validate("testforge",body));
        assertThrows(Exception.class,()->service.validate("another",body));body.put("requirementKey","PVP-2");assertThrows(Exception.class,()->service.validate("testforge",body));body.put("requirementKey","PVP-1");body.put("materialVersion","v2");assertThrows(Exception.class,()->service.validate("testforge",body));service.close();
    }
    @Test void authenticationCannotBeSentToAnotherOrigin(){assertFalse(JiraRequirementClient.sameOrigin(URI.create("https://wiki.example"),URI.create("https://evil.example/image.png")));assertFalse(JiraRequirementClient.sameOrigin(URI.create("https://wiki.example"),URI.create("http://wiki.example/image.png")));assertFalse(JiraRequirementClient.sameOrigin(URI.create("https://wiki.example"),URI.create("https://wiki.example:444/image.png")));}
}
