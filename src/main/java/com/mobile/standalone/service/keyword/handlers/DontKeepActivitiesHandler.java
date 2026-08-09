package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 开发者选项「不保留活动」开关。
 * <p>
 * 找生命周期崩溃里<b>性价比最高</b>的一招：开着之后每离开一个页面 Activity 立即销毁，
 * 再返回就要重建。没保存的状态、静态引用、异步回调回来发现宿主已死——全会在这一轮暴露。
 * <p>
 * 它的价值在于<b>不需要新用例</b>：把已经写好的流程原样再跑一遍就行。
 * 一条流程写一次，正常跑一遍 + 开着这个跑一遍，等于两种场景，边际成本几乎为零。
 * <p>
 * <b>用完必须关掉。</b>忘了关的话这台手机后面所有测试都在这个模式下跑，
 * 会冒出一堆跟本次改动无关的失败，而且没人会想到是几天前某个流程留下的。
 * 所以流程末尾要配一步关掉，执行器在流程结束时也会兜底还原。
 */
@Component
public class DontKeepActivitiesHandler implements KeywordHandler {

    /** 开发者选项里那个开关对应的全局设置 */
    static final String SETTING = "always_finish_activities";

    @Override
    public String code() {
        return "DONT_KEEP_ACTIVITIES";
    }

    @Override
    public String nameZh() {
        return "不保留活动";
    }

    @Override
    public String category() {
        return "DEVICE";
    }

    @Override
    public String description() {
        return "稳定性加压：开启后每离开一个页面立即销毁 Activity，返回时重建，"
                + "把生命周期问题集中暴露出来。<b>用完记得关</b>——留在设备上会影响后续所有测试。仅 Android";
    }

    @Override
    public int sortOrder() {
        return 7;
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.NONE;
    }

    @Override
    public String shortLabel() {
        return "不保留活动";
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.bool("enabled", "开启", true)
                        .withHelp("流程开头开、结尾关。别只开不关——后面这台机跑什么都会带着这个设置")
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        if (ctx.isIos()) {
            throw ctx.fail("不保留活动", "仅 Android 支持", null,
                    "iOS 没有对应开关；相近的加压手段是「杀进程恢复」（iOS 上用冷启动近似）");
        }
        boolean on = ctx.boolParam("enabled", true);
        apply(ctx.getDeviceId(), on);
        ctx.ok("不保留活动", on
                ? "已开启——接下来每离开一个页面都会立即销毁 Activity。流程结尾记得关"
                : "已关闭，设备恢复正常");
    }

    /** 供执行器在流程结束时兜底还原，不用等流程里那一步 */
    public static void apply(String deviceId, boolean on) {
        adb(deviceId, "settings", "put", "global", SETTING, on ? "1" : "0");
    }

    /** 当前是不是开着——流程结束时据此决定要不要还原 */
    public static boolean isOn(String deviceId) {
        String v = adb(deviceId, "settings", "get", "global", SETTING);
        return v != null && v.trim().startsWith("1");
    }

    private static String adb(String deviceId, String... args) {
        String[] cmd = new String[args.length + 3];
        cmd[0] = "adb";
        cmd[1] = "-s";
        cmd[2] = deviceId;
        System.arraycopy(args, 0, cmd, 3, args.length);
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null && sb.length() < 4096) {
                    sb.append(line).append('\n');
                }
            }
            p.waitFor(15, TimeUnit.SECONDS);
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }
}
