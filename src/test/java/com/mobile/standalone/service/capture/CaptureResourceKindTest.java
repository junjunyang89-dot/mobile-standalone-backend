package com.mobile.standalone.service.capture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CaptureResourceKindTest {

    @Test
    void 按路径后缀归类截图里那几类() {
        assertThat(CaptureResourceKind.of("/v3/deadlock-match.html", "text/html")).isEqualTo("page");
        assertThat(CaptureResourceKind.of("/v3/assets/deadlock-match-96d52ca7.js", "")).isEqualTo("js");
        assertThat(CaptureResourceKind.of("/v3/assets/swiper-4dede262.css", "")).isEqualTo("css");
        assertThat(CaptureResourceKind.of("/cdn/hero.png", "")).isEqualTo("image");
        assertThat(CaptureResourceKind.of("/test", "application/json")).isEqualTo("api");
        assertThat(CaptureResourceKind.of("/gld/deadlock/match/detail", "")).isEqualTo("api");
    }

    @Test
    void 没后缀时看ContentType() {
        assertThat(CaptureResourceKind.of("/image/proxy", "image/png")).isEqualTo("image");
        assertThat(CaptureResourceKind.of("/static/app", "application/javascript")).isEqualTo("js");
        // 接口 404 的 text/html 不算页面——要有 .html 后缀才算
        assertThat(CaptureResourceKind.of("/api/v1/setting/getGeTuiTag", "text/html; charset=utf-8"))
                .isEqualTo("api");
        assertThat(CaptureResourceKind.of("/page", "text/html; charset=utf-8")).isEqualTo("api");
    }

    @Test
    void json文件算接口() {
        assertThat(CaptureResourceKind.of("/manifest.json", "application/json")).isEqualTo("api");
    }
}
