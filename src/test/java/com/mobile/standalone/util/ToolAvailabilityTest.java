package com.mobile.standalone.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具可用性判定的单测。
 *
 * <p>这组测试守的核心是一句话:<b>「没装」和「装了但后端看不见」不能混成同一个结论。</b>
 * 混了的话,人会拿着"去装一下"的建议去装一个已经装好的东西,
 * 装完发现还是不行,然后才开始怀疑 PATH——而那已经耗掉半天了。
 */
class ToolAvailabilityTest {

    @Test
    @DisplayName("PATH 里有就是 OK_ON_PATH,不需要提示")
    void 在PATH里() {
        var r = ToolAvailability.judge("adb", "/usr/local/bin/adb", null, "下 Android SDK");
        assertThat(r.status()).isEqualTo(ToolAvailability.Status.OK_ON_PATH);
        assertThat(r.resolvedPath()).isEqualTo("/usr/local/bin/adb");
        assertThat(r.hint()).as("一切正常时不该有提示，否则健康检查全是黄字没人看").isNull();
        assertThat(r.usable()).isTrue();
    }

    @Test
    @DisplayName("PATH 里没有但装了 —— 必须说清是 PATH 问题,不是没装")
    void 装了但不在PATH() {
        var r = ToolAvailability.judge("adb", null,
                "/Users/x/Library/Android/sdk/platform-tools/adb", "下 Android SDK");
        assertThat(r.status()).isEqualTo(ToolAvailability.Status.OK_OFF_PATH);
        assertThat(r.usable()).as("能用，只是要靠绝对路径").isTrue();
        assertThat(r.hint())
                .as("提示必须指向 PATH，而不是让人再去装一遍")
                .contains("PATH")
                .contains("launchd");
        assertThat(r.hint()).doesNotContain("装法");
    }

    @Test
    @DisplayName("两边都没有 —— 才是没装,并且要给装法")
    void 没装() {
        var r = ToolAvailability.judge("mitmdump", null, null, "brew install mitmproxy");
        assertThat(r.status()).isEqualTo(ToolAvailability.Status.MISSING);
        assertThat(r.usable()).isFalse();
        assertThat(r.resolvedPath()).isNull();
        assertThat(r.hint()).contains("brew install mitmproxy");
    }

    @Test
    @DisplayName("没给装法也不能崩，提示里就不带装法")
    void 没装且没给装法() {
        var r = ToolAvailability.judge("foo", null, null, null);
        assertThat(r.status()).isEqualTo(ToolAvailability.Status.MISSING);
        assertThat(r.hint()).contains("找不到 foo").doesNotContain("装法");
    }

    @Test
    @DisplayName("空白字符串等于没找到 —— which 失败时常返回空串而不是 null")
    void 空白当成没找到() {
        var r = ToolAvailability.judge("adb", "   ", "", "下 SDK");
        assertThat(r.status()).isEqualTo(ToolAvailability.Status.MISSING);
    }

    @Test
    @DisplayName("PATH 优先于候选路径 —— 两个都有时用 PATH 里那个")
    void PATH优先() {
        var r = ToolAvailability.judge("adb", "/usr/local/bin/adb", "/opt/sdk/adb", null);
        assertThat(r.status()).isEqualTo(ToolAvailability.Status.OK_ON_PATH);
        assertThat(r.resolvedPath()).isEqualTo("/usr/local/bin/adb");
    }

    @Test
    @DisplayName("路径两边的空白要去掉，否则拼进命令行会出错")
    void 去空白() {
        var r = ToolAvailability.judge("adb", "  /usr/local/bin/adb \n", null, null);
        assertThat(r.resolvedPath()).isEqualTo("/usr/local/bin/adb");
    }

    // ---------- 汇总 ----------

    @Test
    @DisplayName("必需工具缺了要明确说「会静默不可用」")
    void 必需工具缺失的汇总() {
        var required = List.of(
                ToolAvailability.judge("adb", null, null, "下 SDK"),
                ToolAvailability.judge("java", "/usr/bin/java", null, null));
        assertThat(ToolAvailability.allRequiredUsable(required)).isFalse();
        assertThat(ToolAvailability.summarize(required, List.of()))
                .contains("1 个必需工具找不到")
                .contains("静默不可用");
    }

    @Test
    @DisplayName("「现在能用但不在 PATH」要单独报出来 —— 那是换个启动方式就坏的状态")
    void 不在PATH的汇总() {
        var required = List.of(
                ToolAvailability.judge("adb", null, "/opt/sdk/adb", null),
                ToolAvailability.judge("java", "/usr/bin/java", null, null));
        assertThat(ToolAvailability.allRequiredUsable(required))
                .as("能用就是能用，不该因为不在 PATH 就报整体不可用").isTrue();
        assertThat(ToolAvailability.summarize(required, List.of()))
                .contains("不在 PATH")
                .contains("直接调命令名");
    }

    @Test
    @DisplayName("可选工具没装不能算红灯 —— 混在一起人就不看了")
    void 可选工具不算红灯() {
        var required = List.of(ToolAvailability.judge("adb", "/usr/local/bin/adb", null, null));
        var optional = List.of(
                ToolAvailability.judge("solox", null, null, "pip install solox"),
                ToolAvailability.judge("tidevice", null, null, "pip install tidevice"));
        assertThat(ToolAvailability.allRequiredUsable(required))
                .as("可选工具不该影响必需工具的结论").isTrue();
        String sum = ToolAvailability.summarize(required, optional);
        assertThat(sum).contains("必需工具齐了").contains("2 个可选工具没装");
        assertThat(sum).doesNotContain("静默不可用");
    }

    @Test
    @DisplayName("全好的时候给一句干脆的结论")
    void 全部就位() {
        var required = List.of(ToolAvailability.judge("adb", "/usr/local/bin/adb", null, null));
        var optional = List.of(ToolAvailability.judge("solox", "/usr/bin/solox", null, null));
        assertThat(ToolAvailability.summarize(required, optional)).isEqualTo("全部工具就位");
    }

    @Test
    @DisplayName("必需工具缺失优先于可选工具 —— 先说最要紧的那句")
    void 必需优先于可选() {
        var required = List.of(ToolAvailability.judge("adb", null, null, "下 SDK"));
        var optional = List.of(ToolAvailability.judge("solox", null, null, "pip install solox"));
        assertThat(ToolAvailability.summarize(required, optional)).contains("必需工具找不到");
    }

    @Test
    @DisplayName("空集合不炸")
    void 空集合() {
        assertThat(ToolAvailability.allRequiredUsable(null)).isTrue();
        assertThat(ToolAvailability.allRequiredUsable(List.of())).isTrue();
        assertThat(ToolAvailability.summarize(null, null)).isEqualTo("全部工具就位");
    }
}
