package com.mobile.standalone.service.asr;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 本地语音转文字：{@code ffmpeg} 转码 + {@code whisper-cli} 识别。
 *
 * <p><b>为什么走本地而不是云端 ASR</b>：
 * <ul>
 *   <li>用例描述里会带账号、包名、业务细节，甚至 debug 包的登录手机号——
 *       说一句话就发到外部服务上，这个口子不如不开。本地跑，音频不出这台机器。</li>
 *   <li>阿里云那条「录音文件识别」要先把音频放到<b>公网可访问的 URL</b> 再异步轮询。
 *       主控机上的录音没有这样的落点，为了它单独搭个对象存储不划算。</li>
 *   <li>Apple Silicon 上 whisper.cpp 的 small 模型约十倍实时——说十秒，一秒内出结果，
 *       比等一个云端往返还快。</li>
 * </ul>
 *
 * <p>形态跟 adb / emulator 一致：<b>shell 出去调 CLI</b>，不引第三方 SDK。
 * 装没装、模型在不在，都当成可探测的状态报出来，而不是等调用时抛一个看不懂的异常。
 */
@Service
@Slf4j
public class LocalAsrService {

    @Value("${app.asr.enabled:true}")
    private boolean enabled;

    /** whisper.cpp 的命令行。brew 装出来叫 whisper-cli */
    @Value("${app.asr.bin:whisper-cli}")
    private String bin;

    @Value("${app.asr.ffmpeg:ffmpeg}")
    private String ffmpeg;

    @Value("${app.asr.model:${user.home}/.mobile-standalone/models/ggml-small.bin}")
    private String modelPath;

    /** 识别语言。auto 让它自己判，但固定成 zh 在中英混说时更稳 */
    @Value("${app.asr.language:zh}")
    private String language;

    /**
     * 音频时长上限（秒）。
     * <p>不是怕慢，是怕有人把一段会议录音传上来——主控机的 CPU 还要跑用例，
     * 一次几十分钟的转写会把正在跑的自动化一起拖住。
     */
    @Value("${app.asr.max-seconds:120}")
    private int maxSeconds;

    @Value("${app.asr.timeout-seconds:120}")
    private int timeoutSeconds;

    /** 能不能用，以及为什么不能 */
    public record Readiness(boolean ready, String reason) {
    }

    public record Result(String text, long millis) {
    }

    public Readiness readiness() {
        if (!enabled) {
            return new Readiness(false, "语音识别已关闭（app.asr.enabled=false）");
        }
        if (!which(bin)) {
            return new Readiness(false, "主控机没装 whisper-cpp。执行：brew install whisper-cpp");
        }
        if (!which(ffmpeg)) {
            return new Readiness(false, "主控机没装 ffmpeg。执行：brew install ffmpeg");
        }
        Path model = Path.of(modelPath);
        if (!Files.isRegularFile(model)) {
            return new Readiness(false, "模型文件不在：" + modelPath
                    + "。下载 ggml-small.bin 放到这个路径，或改配置 app.asr.model");
        }
        return new Readiness(true, null);
    }

    /**
     * @param audio    前端录的原始音频（webm/opus/m4a/wav 都行，交给 ffmpeg 统一）
     * @param filename 原始文件名，只用来取扩展名
     */
    public Result transcribe(byte[] audio, String filename) throws Exception {
        Readiness r = readiness();
        if (!r.ready()) {
            throw new IllegalStateException(r.reason());
        }
        if (audio == null || audio.length == 0) {
            throw new IllegalArgumentException("没收到音频");
        }
        long started = System.currentTimeMillis();
        Path dir = Files.createTempDirectory("asr-");
        Path raw = dir.resolve("in" + ext(filename));
        Path wav = dir.resolve("in.wav");
        try {
            Files.write(raw, audio);

            // whisper.cpp 只吃 16kHz 单声道 wav。浏览器录出来多半是 webm/opus，必须转
            run(List.of(ffmpeg, "-y", "-i", raw.toString(),
                            "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le",
                            "-t", String.valueOf(Math.max(1, maxSeconds)),
                            wav.toString()),
                    "ffmpeg 转码");

            if (!Files.isRegularFile(wav) || Files.size(wav) < 1024) {
                throw new IllegalStateException("转码后没有有效音频——可能根本没录到声音");
            }

            String out = run(List.of(bin, "-m", modelPath, "-f", wav.toString(),
                            "-l", language, "-nt", "-np", "--output-txt", "-of", dir.resolve("out").toString()),
                    "whisper 识别");

            String text = readTextOutput(dir.resolve("out.txt"), out);
            if (text.isBlank()) {
                throw new IllegalStateException("没识别出内容——说话声音太小，或者录到的是静音");
            }
            return new Result(text, System.currentTimeMillis() - started);
        } finally {
            // 语音里可能带账号密码，转完就删，不在磁盘上留
            deleteQuietly(dir);
        }
    }

    // ==================== 内部 ====================

    /**
     * whisper.cpp 的文字结果优先读 {@code -of} 产出的 .txt。
     * <p>直接解析 stdout 也能拿到，但不同版本的 stdout 会夹带时间戳和进度行，
     * 靠正则剥它们是个跟着版本走的负担。
     */
    private static String readTextOutput(Path txt, String stdout) throws IOException {
        if (Files.isRegularFile(txt)) {
            return Files.readString(txt, StandardCharsets.UTF_8).trim();
        }
        StringBuilder sb = new StringBuilder();
        for (String line : stdout.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("[") || t.startsWith("whisper_")) {
                continue;
            }
            sb.append(t).append(' ');
        }
        return sb.toString().trim();
    }

    private String run(List<String> cmd, String what) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        Thread drain = new Thread(() -> {
            try (var in = p.getInputStream()) {
                out.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // 超时杀进程时流会断
            }
        }, "asr-drain");
        drain.setDaemon(true);
        drain.start();
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException(what + "超时（" + timeoutSeconds + " 秒）");
        }
        drain.join(1000);
        if (p.exitValue() != 0) {
            throw new IllegalStateException(what + "失败：" + tail(out.toString()));
        }
        return out.toString();
    }

    private static boolean which(String cmd) {
        try {
            Process p = new ProcessBuilder("which", cmd).redirectErrorStream(true).start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    static String ext(String filename) {
        if (filename == null) {
            return ".bin";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return ".bin";
        }
        String e = filename.substring(dot).toLowerCase(Locale.ROOT);
        // 文件名是前端给的，直接往命令行拼会把路径和参数带进来
        return e.matches("\\.[a-z0-9]{1,6}") ? e : ".bin";
    }

    /** 报错只给最后几行：前面全是模型加载的刷屏，对定位没帮助 */
    static String tail(String s) {
        if (s == null || s.isBlank()) {
            return "(无输出)";
        }
        String[] lines = s.strip().split("\\R");
        List<String> keep = new ArrayList<>();
        for (int i = Math.max(0, lines.length - 5); i < lines.length; i++) {
            keep.add(lines[i].trim());
        }
        return String.join(" | ", keep);
    }

    private static void deleteQuietly(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 删不掉就算了，临时目录本来也会被系统回收
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
