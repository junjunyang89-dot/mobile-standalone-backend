package com.mobile.standalone.service.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RespClientTest {

    private static RespClient client(String serverReplies, ByteArrayOutputStream sent) {
        return new RespClient(null, new ByteArrayInputStream(serverReplies.getBytes(StandardCharsets.UTF_8)), sent);
    }

    @Test
    @DisplayName("命令按 RESP 数组发出去，中文按 UTF-8 字节数算长度")
    void 发送格式() throws Exception {
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        client("+OK\r\n", sent).call("SET", "用户:1", 30);
        assertThat(sent.toString(StandardCharsets.UTF_8))
                .isEqualTo("*3\r\n$3\r\nSET\r\n$8\r\n用户:1\r\n$2\r\n30\r\n");
    }

    @Test
    @DisplayName("各种回包类型：字符串、整数、空值、嵌套数组")
    void 回包类型() throws Exception {
        String replies = "+PONG\r\n:42\r\n$-1\r\n*2\r\n$1\r\n0\r\n*2\r\n$3\r\na:1\r\n$3\r\na:2\r\n";
        RespClient c = client(replies, new ByteArrayOutputStream());
        assertThat(c.call("PING")).isEqualTo("PONG");
        assertThat(c.call("DBSIZE")).isEqualTo(42L);
        assertThat(c.call("GET", "x")).isNull();
        List<?> scan = (List<?>) c.call("SCAN", "0");
        assertThat(RespClient.str(scan.get(0))).isEqualTo("0");
        assertThat(((List<?>) scan.get(1)).stream().map(RespClient::str)).containsExactly("a:1", "a:2");
    }

    @Test
    @DisplayName("值里带 \\r\\n 也能按长度收完整，不会被当成行尾")
    void 值里有换行() throws Exception {
        RespClient c = client("$4\r\na\r\nb\r\n", new ByteArrayOutputStream());
        assertThat(RespClient.str(c.call("GET", "k"))).isEqualTo("a\r\nb");
    }

    @Test
    @DisplayName("顶层错误抛出来；批量里的错误留在原位，不影响后面的回包")
    void 错误回包() throws Exception {
        RespClient c = client("-WRONGTYPE Operation against a key\r\n", new ByteArrayOutputStream());
        assertThatThrownBy(() -> c.call("GET", "h")).isInstanceOf(RespClient.RespError.class)
                .hasMessageStartingWith("WRONGTYPE");

        RespClient p = client("-ERR boom\r\n:7\r\n", new ByteArrayOutputStream());
        List<Object> r = p.pipeline(List.of(new Object[]{"X"}, new Object[]{"PTTL", "k"}));
        assertThat(r.get(0)).isInstanceOf(RespClient.RespError.class);
        assertThat(r.get(1)).isEqualTo(7L);
    }

    @Test
    @DisplayName("集群 MOVED 能取出目标节点地址")
    void moved() {
        assertThat(new RespClient.RespError("MOVED 3999 10.0.0.2:6380").movedTo()).isEqualTo("10.0.0.2:6380");
        assertThat(new RespClient.RespError("ERR other").movedTo()).isNull();
    }
}
