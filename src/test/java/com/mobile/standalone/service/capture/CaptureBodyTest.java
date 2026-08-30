package com.mobile.standalone.service.capture;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 看响应体。
 * <p>
 * 这是抓包的头号用途——原来要在手机上翻抓包工具再把响应复制出来。
 * body 只在临时目录里躺着、不进库，所以这条读取路径断了不会有任何报错，
 * 只会变成一个点不开的按钮。
 */
class CaptureBodyTest {

    private final PacketCaptureService service = new PacketCaptureService();

    @Test
    void 读得出插件落盘的请求和响应体() throws Exception {
        Path out = Files.createTempFile("cap-", ".jsonl");
        Path dir = Path.of(out.toAbsolutePath() + ".details");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("7.json"), """
                {"reqHeaders":{"host":"gateway-qa.arca42.com"},"reqBody":"",
                 "respHeaders":{"content-type":"application/json"},
                 "respBody":"{\\"code\\":0,\\"data\\":{\\"rank\\":12}}"}
                """, StandardCharsets.UTF_8);

        var n = service.body(sessionOf(out), 7);

        assertThat(n).isNotNull();
        assertThat(n.path("respBody").asText()).contains("\"rank\":12");
        assertThat(n.path("respHeaders").path("content-type").asText())
                .isEqualTo("application/json");
    }

    @Test
    void 详情文件不在时返回空而不是报错() throws Exception {
        Path out = Files.createTempFile("cap-", ".jsonl");
        // 临时文件被系统清掉就是这个状态，点开时要给出解释而不是抛异常
        assertThat(service.body(sessionOf(out), 3)).isNull();
    }

    @Test
    void 没有任何抓包记录时不炸() {
        assertThat(service.body("last", 1)).isNull();
    }

    /** 会话早就结束了，所以走 {@code last} 这条路径——这正是实际用法 */
    private String sessionOf(Path out) throws Exception {
        var f = PacketCaptureService.class.getDeclaredField("lastOut");
        f.setAccessible(true);
        f.set(service, out);
        return "last";
    }
}
