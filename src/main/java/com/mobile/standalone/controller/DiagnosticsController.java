package com.mobile.standalone.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 部署自检。
 *
 * <p><b>为什么不放在 {@code /health} 下</b>:{@code /health} 是<b>免鉴权</b>前缀
 * (为了让健康检查能分清「服务挂了」和「令牌过期」)。
 * 而这里返回后端进程的完整 PATH、SDK 绝对路径这些环境细节,
 * 挂在免鉴权路径上等于把部署机的目录结构和用户名对内网所有人敞开。
 *
 * <p>放在这里意味着要登录才能看。而「还没建账号」的首次部署阶段
 * {@code AuthFilter} 本来就整体放行,所以刚装完机器时照样 curl 得到——
 * 恰好是需要它的时候能用,建完账号之后收紧。
 */
@RestController
@RequestMapping("/diagnostics")
public class DiagnosticsController {

    /**
     * 外部命令行工具自检。
     *
     * <p><b>存在的理由</b>:代码里有上百处直接 {@code new ProcessBuilder("adb", ...)}。
     * 后端进程的 PATH 里没有 adb 时,这些地方全部失败,而表现是
     * 「设备列表空着」「抓包一条都没有」「元素点选没反应」——
     * 六个看起来互不相关的故障,同一个根因,而且<b>一条报错都没有</b>。
     *
     * <p>换机器部署时这几乎必然发生一次:从登录 shell 跑起来的后端能看到 adb,
     * 而 launchd 开机自启拉起来的后端 PATH 只有 {@code /usr/bin:/bin:/usr/sbin:/sbin}。
     *
     * <p>所以这个接口是给"部署完第一件事"用的:一次看清所有工具的状态,
     * 而不是等六个功能分别报障再一个个查。
     */
    @GetMapping("/tools")
    public ResponseEntity<Map<String, Object>> toolsDiagnostic() {
        java.util.List<com.mobile.standalone.util.ToolAvailability.Result> required =
                new java.util.ArrayList<>();
        java.util.List<com.mobile.standalone.util.ToolAvailability.Result> optional =
                new java.util.ArrayList<>();

        String androidHome = firstDir(System.getenv("ANDROID_HOME"),
                System.getenv("ANDROID_SDK_ROOT"),
                System.getProperty("user.home") + "/Library/Android/sdk");

        // 必需:少了就有整块功能静默不可用
        required.add(probe("adb", "下 Android SDK platform-tools，并把它加进 PATH",
                androidHome == null ? null : androidHome + "/platform-tools/adb"));
        required.add(probe("java", "装 JDK 17", null));

        // 可选:没装只是对应功能降级,不该和上面混成同一个红灯
        optional.add(probe("mitmdump", "brew install mitmproxy",
                "/opt/homebrew/bin/mitmdump", "/usr/local/bin/mitmdump",
                System.getProperty("user.home") + "/.local/bin/mitmdump"));
        optional.add(probe("appium", "npm i -g appium", null));
        optional.add(probe("idevice_id", "brew install libimobiledevice（仅 iOS 需要）", null));
        optional.add(probe("xcrun", "装 Xcode Command Line Tools（仅 iOS 需要）", null));
        optional.add(probe("python3", "brew install python（性能旁路采集用）", null));
        optional.add(probe("node", "brew install node（H5 测试用）", null));
        optional.add(probe("emulator", "Android SDK 自带；需要模拟器时才用",
                androidHome == null ? null : androidHome + "/emulator/emulator"));

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("summary", com.mobile.standalone.util.ToolAvailability.summarize(required, optional));
        out.put("allRequiredUsable",
                com.mobile.standalone.util.ToolAvailability.allRequiredUsable(required));
        out.put("required", required);
        out.put("optional", optional);
        out.put("androidHome", androidHome);
        // 把后端进程<b>自己看到的</b> PATH 一起返回。
        // 人对着它和终端里 echo $PATH 一比,就知道是不是启动方式的问题——
        // 这比任何文字说明都直接
        out.put("backendProcessPath", System.getenv("PATH"));
        return ResponseEntity.ok(out);
    }

    private static com.mobile.standalone.util.ToolAvailability.Result probe(
            String name, String installHint, String... candidates) {
        return com.mobile.standalone.util.ToolAvailability.judge(
                name, which(name), firstExecutable(candidates), installHint);
    }

    /** PATH 查找。用 {@code command -v} 而不是 {@code which}:后者在某些精简环境里压根没装 */
    private static String which(String name) {
        try {
            Process p = new ProcessBuilder("/bin/sh", "-c", "command -v " + name)
                    .redirectErrorStream(true).start();
            String out;
            try (var in = p.getInputStream()) {
                out = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            }
            // 必须带超时:自检接口卡住比返回"查不到"糟得多
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return out.isBlank() ? null : out.lines().findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstExecutable(String... paths) {
        if (paths == null) {
            return null;
        }
        for (String candidate : paths) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            java.nio.file.Path path = java.nio.file.Path.of(candidate);
            if (java.nio.file.Files.isRegularFile(path) && java.nio.file.Files.isExecutable(path)) {
                return path.toString();
            }
        }
        return null;
    }

    private static String firstDir(String... paths) {
        if (paths == null) {
            return null;
        }
        for (String candidate : paths) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            java.nio.file.Path path = java.nio.file.Path.of(candidate.trim());
            if (java.nio.file.Files.isDirectory(path)) {
                return path.toString();
            }
        }
        return null;
    }
}
