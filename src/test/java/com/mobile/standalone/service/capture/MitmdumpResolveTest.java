package com.mobile.standalone.service.capture;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * mitmdump 的定位。
 * <p>
 * 直接依赖 PATH 是不行的：从 IDE 或 launchd 起的后端拿不到登录 shell 的 PATH，
 * {@code /opt/homebrew/bin} 常常不在里面，于是明明装了却报
 * 「Cannot run program "mitmdump": error=2」。
 */
class MitmdumpResolveTest {

    private final PacketCaptureService service = new PacketCaptureService(mock(CaptureStore.class));

    private String resolve() throws Exception {
        try {
            return (String) ReflectionTestUtils.invokeMethod(service, "resolveMitmdump");
        } catch (RuntimeException e) {
            // ReflectionTestUtils 把受检异常包一层，剥出来好断言
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }

    private static Path fakeExecutable() throws Exception {
        Path f = Files.createTempFile("mitmdump-fake", "");
        Files.setPosixFilePermissions(f, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        return f;
    }

    /** 配了绝对路径就直接用——给装在非常规位置的人留的出口 */
    @Test
    void usesConfiguredAbsolutePath() throws Exception {
        Path bin = fakeExecutable();
        ReflectionTestUtils.setField(service, "mitmdumpBin", bin.toString());

        assertThat(resolve()).isEqualTo(bin.toString());
    }

    /** 配了路径但不可执行，要当场说清楚是哪个配置项的问题 */
    @Test
    void rejectsConfiguredPathThatIsNotExecutable() {
        ReflectionTestUtils.setField(service, "mitmdumpBin", "/definitely/not/here/mitmdump");

        assertThatThrownBy(this::resolve)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("不可执行")
                .hasMessageContaining("app.capture.mitmdump");
    }

    /**
     * 找不到时给的必须是能照着做的话。
     * 原来抛的是 ProcessBuilder 的「Cannot run program ... error=2」，
     * 那句话到了页面上没人知道该干什么。
     * <p>
     * 用一个自定义名字而不是默认的 mitmdump：内置候选路径只对默认名生效，
     * 这样断言就不受本机装没装 mitmproxy 影响。这条测试原先正是因为
     * 本机后来装上了 mitmproxy 而挂掉的。
     */
    @Test
    void failureMessageTellsHowToInstall() {
        ReflectionTestUtils.setField(service, "mitmdumpBin", "mitmdump-does-not-exist-xyz");

        assertThatThrownBy(this::resolve)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("brew install mitmproxy")
                .hasMessageContaining("app.capture.mitmdump");
    }

    /**
     * 配了自定义名字就不该被内置候选顶替。
     * <p>
     * 原实现只要配的不是绝对路径就直接去查候选路径，于是本机装了 mitmproxy 之后，
     * 无论配什么名字都会解析成 /opt/homebrew/bin/mitmdump——人配的东西被无声忽略了。
     */
    @Test
    void customBinaryNameIsNotSilentlyReplacedByBundledCandidates() {
        ReflectionTestUtils.setField(service, "mitmdumpBin", "my-custom-dump-xyz");

        assertThatThrownBy(this::resolve)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("没找到 mitmdump");
    }
}
