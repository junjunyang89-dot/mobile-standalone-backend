package com.mobile.standalone.service.harmony;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HdcClientListTargetsTest {

    @Test
    void parseVerboseOfflineLine() {
        var t = HdcClient.parseTargetLine("6UNBB26604017338\t\tUSB\tOffline\tlocalhost");
        assertThat(t).isNotNull();
        assertThat(t.serial()).isEqualTo("6UNBB26604017338");
        assertThat(t.offline()).isTrue();
        assertThat(t.authorized()).isFalse();
    }

    @Test
    void parseVerboseConnectedLine() {
        var t = HdcClient.parseTargetLine("6UNBB26604017338\t\tUSB\tConnected\tlocalhost");
        assertThat(t).isNotNull();
        assertThat(t.authorized()).isTrue();
        assertThat(t.offline()).isFalse();
    }

    @Test
    void parsePlainSerialIsAuthorized() {
        var t = HdcClient.parseTargetLine("FMR0223A30004438");
        assertThat(t).isNotNull();
        assertThat(t.authorized()).isTrue();
    }

    @Test
    void parseUnauthorized() {
        var t = HdcClient.parseTargetLine("ABC123 Unauthorized");
        assertThat(t).isNotNull();
        assertThat(t.unauthorized()).isTrue();
        assertThat(t.authorized()).isFalse();
    }

    @Test
    void skipEmptyMarker() {
        assertThat(HdcClient.parseTargetLine("[Empty]")).isNull();
        assertThat(HdcClient.blankOrEmptyTargets("[Empty]\n")).isTrue();
        assertThat(HdcClient.blankOrEmptyTargets("6UNBB\tUSB\tConnected\tlocalhost")).isFalse();
    }
}
