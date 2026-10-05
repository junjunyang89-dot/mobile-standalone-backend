package com.mobile.standalone.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class RawValueTextTest {

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("JSON 在服务端格式化，长整型 id 不丢精度")
    void json不丢精度() {
        RawValueText.View v = RawValueText.of(utf8("{\"id\":1234567890123456789,\"amt\":0.10}"));
        assertThat(v.format()).isEqualTo(RawValueText.JSON);
        assertThat(v.text()).contains("1234567890123456789").contains("0.10").contains("\n");
        assertThat(v.editable()).isTrue();
    }

    @Test
    @DisplayName("普通文本原样给，可编辑")
    void 文本() {
        RawValueText.View v = RawValueText.of(utf8("token-abc 中文"));
        assertThat(v.format()).isEqualTo(RawValueText.TEXT);
        assertThat(v.text()).isEqualTo("token-abc 中文");
        assertThat(v.editable()).isTrue();
    }

    @Test
    @DisplayName("Java 序列化：抠出类名和字符串字段，不能当文本改")
    void java序列化() throws Exception {
        HashMap<String, String> m = new HashMap<>();
        m.put("nickname", "zhangsan");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(m);
        }
        RawValueText.View v = RawValueText.of(bos.toByteArray());
        assertThat(v.format()).isEqualTo(RawValueText.JAVA_SERIALIZED);
        assertThat(v.text()).contains("java.util.HashMap").contains("nickname").contains("zhangsan");
        assertThat(v.editable()).isFalse();
    }

    @Test
    @DisplayName("gzip 压缩的 JSON 解开再看，但不能当文本改回去")
    void gzip() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(utf8("{\"a\":1}"));
        }
        RawValueText.View v = RawValueText.of(bos.toByteArray());
        assertThat(v.gzip()).isTrue();
        assertThat(v.format()).isEqualTo(RawValueText.JSON);
        assertThat(v.editable()).isFalse();
    }

    @Test
    @DisplayName("二进制给十六进制预览")
    void 二进制() {
        RawValueText.View v = RawValueText.of(new byte[]{0, 1, 2, (byte) 0xff, 3, 4});
        assertThat(v.format()).isEqualTo(RawValueText.BINARY);
        assertThat(v.text()).startsWith("00 01 02 ff");
    }

    @Test
    @DisplayName("截断正好切在中文中间，仍按文本显示，但不可编辑")
    void 截断中文() {
        byte[] full = utf8("缓存值缓存值");
        byte[] cut = Arrays.copyOf(full, full.length - 1);
        RawValueText.View v = RawValueText.of(cut, full.length, true);
        assertThat(v.format()).isEqualTo(RawValueText.TEXT);
        assertThat(v.text()).startsWith("缓存值缓存");
        assertThat(v.truncated()).isTrue();
        assertThat(v.editable()).isFalse();
    }
}
