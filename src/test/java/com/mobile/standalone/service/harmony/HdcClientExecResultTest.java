package com.mobile.standalone.service.harmony;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HdcClientExecResultTest {

    @Test
    void okIsFalseWhenStdoutReportsInstallErrorDespiteExitZero() {
        var r = new HdcClient.ExecResult(0,
                "[Info]App install path:x.hap msg:error: failed to install bundle. code:9568289\n",
                "");
        assertThat(r.ok()).isFalse();
    }

    @Test
    void okIsTrueOnCleanExit() {
        var r = new HdcClient.ExecResult(0, "AppMod finish\n", "");
        assertThat(r.ok()).isTrue();
    }
}
