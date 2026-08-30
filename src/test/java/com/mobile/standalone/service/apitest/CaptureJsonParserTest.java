package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CaptureJsonParserTest {

    @Test
    void 解析响应体顶层字段和样本值() {
        String resp = "{\"code\":0,\"msg\":\"ok\",\"data\":{\"rank\":12}}";
        var sample = CaptureJsonParser.parseBodies(null, resp);

        assertThat(sample.fieldNames()).contains("code", "msg", "data");
        assertThat(sample.topLevelSamples()).containsEntry("code", 0);
        assertThat(sample.topLevelSamples()).containsEntry("msg", "ok");
        // 嵌套对象不断言值
        assertThat(sample.topLevelSamples()).doesNotContainKey("data");
    }

    @Test
    void 非json响应不抛错() {
        var sample = CaptureJsonParser.parseBodies("{}", "<html>error</html>");
        assertThat(sample.fieldNames()).isEmpty();
    }
}
