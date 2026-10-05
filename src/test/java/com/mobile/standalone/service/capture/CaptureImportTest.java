package com.mobile.standalone.service.capture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CaptureImportTest {

    private static final String TEXT = """
            Request

            GET https://gwapi.example.cn/eventcenter/app/csgo/event/getMatchList?matchTime=2026-10-02+00:00:00 HTTP/1.1
            Host: gwapi.example.cn
            token: SECRET-TOKEN
            appversion: 4.1.8
            platform: h5_ios



            --------------------------------------------------------------------------------
            Response

            HTTP/1.1 200
            pwrd-request-id: trace-123
            Content-Type: application/json;charset=UTF-8
            X-Cache: HIT

            {"code":0,"message":"success","result":{"list":[{"id":1}]}}
            """;

    @Test
    void 文本格式_一段请求和响应() {
        List<CaptureImportParser.Flow> flows = CaptureImportParser.parse(TEXT);

        assertThat(flows).hasSize(1);
        var f = flows.get(0);
        assertThat(f.method()).isEqualTo("GET");
        assertThat(f.url()).startsWith("https://gwapi.example.cn/eventcenter/app/csgo/event/getMatchList?");
        assertThat(f.reqHeaders()).containsEntry("appversion", "4.1.8");
        assertThat(f.reqBody()).isEmpty();
        assertThat(f.status()).isEqualTo(200);
        assertThat(f.respHeaders()).containsEntry("pwrd-request-id", "trace-123");
        assertThat(f.respBody()).isEqualTo("{\"code\":0,\"message\":\"success\",\"result\":{\"list\":[{\"id\":1}]}}");
    }

    @Test
    void 文本格式_多段_带请求体_相对路径() {
        String two = TEXT + """

                Request

                POST /user/update HTTP/1.1
                Host: api.example.cn
                Content-Type: application/json

                {"nick":"a"}
                ----------
                Response

                HTTP/1.1 500 Internal Server Error
                Content-Type: application/json

                {"code":1}
                """;
        List<CaptureImportParser.Flow> flows = CaptureImportParser.parse(two);

        assertThat(flows).hasSize(2);
        var p = flows.get(1);
        assertThat(p.method()).isEqualTo("POST");
        assertThat(p.url()).isEqualTo("https://api.example.cn/user/update");
        assertThat(p.reqBody()).isEqualTo("{\"nick\":\"a\"}");
        assertThat(p.status()).isEqualTo(500);
        assertThat(p.reason()).isEqualTo("Internal Server Error");
        assertThat(p.respBody()).isEqualTo("{\"code\":1}");
        assertThat(flows.get(0).respBody()).endsWith("}}");
    }

    @Test
    void HAR_含base64响应和伪头() {
        String body = Base64.getEncoder().encodeToString("{\"code\":0}".getBytes(StandardCharsets.UTF_8));
        String har = """
                {"log":{"entries":[{
                  "startedDateTime":"2026-10-02T10:00:00.000+08:00","time":123.4,
                  "request":{"method":"get","url":"https://a.example.cn/x/list?page=1","httpVersion":"HTTP/2",
                    "headers":[{"name":":authority","value":"a.example.cn"},{"name":"token","value":"T"}]},
                  "response":{"status":200,"statusText":"OK",
                    "headers":[{"name":"content-type","value":"application/json"}],
                    "content":{"mimeType":"application/json","encoding":"base64","text":"%s"}}
                }]}}
                """.formatted(body);
        List<CaptureImportParser.Flow> flows = CaptureImportParser.parse(har);

        assertThat(flows).hasSize(1);
        var f = flows.get(0);
        assertThat(f.method()).isEqualTo("GET");
        assertThat(f.reqHeaders()).containsOnlyKeys("token");
        assertThat(f.respBody()).isEqualTo("{\"code\":0}");
        assertThat(f.durationMs()).isEqualTo(123L);
        assertThat(f.at()).isNotNull();
    }

    @Test
    void 认不出请求就报错() {
        CaptureImportService svc = service(tempStore());
        assertThatThrownBy(() -> svc.importContent("hello world", "a.txt", "PROD"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 导入落盘格式和抓包一致_token打码_记环境() throws Exception {
        CaptureStore store = tempStore();
        var r = service(store).importContent(TEXT, "request.txt", "PROD");

        assertThat(r.flows()).isEqualTo(1);
        assertThat(r.env()).isEqualTo("PROD");
        Path out = store.flowFileOf(r.sessionId());
        ObjectMapper om = new ObjectMapper();
        JsonNode row = om.readTree(Files.readAllLines(out).get(0));
        assertThat(row.path("seq").asInt()).isEqualTo(1);
        assertThat(row.path("host").asText()).isEqualTo("gwapi.example.cn");
        assertThat(row.path("path").asText()).isEqualTo("/eventcenter/app/csgo/event/getMatchList");
        assertThat(row.path("query").asText()).isEqualTo("matchTime=2026-10-02+00:00:00");
        assertThat(row.path("traceId").asText()).isEqualTo("trace-123");
        assertThat(row.path("respKind").asText()).isEqualTo("application/json");
        assertThat(row.path("hasDetail").asBoolean()).isTrue();

        String detail = Files.readString(Path.of(out + ".details", "1.json"));
        assertThat(detail).doesNotContain("SECRET-TOKEN");
        assertThat(om.readTree(detail).path("reqHeaders").path("token").asText()).isEqualTo("***");
        assertThat(om.readTree(detail).path("respBody").asText()).contains("\"code\":0");
        assertThat(Files.readString(Path.of(out + ".env"))).isEqualTo("PROD");
        assertThat(Files.readString(Path.of(out + ".hosts"))).contains("gwapi.example.cn");
    }

    private static CaptureStore tempStore() {
        CaptureStore store = new CaptureStore(mock(JdbcTemplate.class));
        try {
            ReflectionTestUtils.setField(store, "storeDirRoot",
                    Files.createTempDirectory("capture-import-test").toString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return store;
    }

    private static CaptureImportService service(CaptureStore store) {
        return new CaptureImportService(store);
    }
}
