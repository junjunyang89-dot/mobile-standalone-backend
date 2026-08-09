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
 * 杀进程恢复：模拟系统低内存回收，再从最近任务恢复现场。
 * <p>
 * 这是<b>人工几乎测不到</b>的一类崩溃。你没法精确制造「进程被回收、但任务栈还在」这个状态——
 * 而系统真会这么干，用户在后台放一会儿再切回来就是这个路径。
 * 系统尝试恢复现场时，那些没做 {@code onSaveInstanceState} 的页面、
 * 回调回来发现宿主已死的异步任务，会集中在这里炸。
 * <p>
 * <b>关键是用 {@code am kill} 而不是 {@code force-stop}</b>：
 * <ul>
 *   <li>{@code am kill} 只杀进程，任务栈保留 → 再进去走的是<b>恢复</b>路径，这才是要测的</li>
 *   <li>{@code force-stop} 连任务栈一起清 → 再进去是冷启动，恢复路径根本走不到</li>
 * </ul>
 * 这两个命令看着差不多，测的却是完全不同的东西。
 */
@Component
public class KillAndRestoreHandler implements KeywordHandler {

    @Override
    public String code() {
        return "KILL_AND_RESTORE";
    }

    @Override
    public String nameZh() {
        return "杀进程恢复";
    }

    @Override
    public String category() {
        return "DEVICE";
    }

    @Override
    public String description() {
        return "稳定性加压：模拟系统低内存回收（am kill，保留任务栈），再从最近任务恢复现场。"
                + "测的是「后台放久了切回来」这条路径上的崩溃，人工很难稳定复现。仅 Android";
    }

    @Override
    public int sortOrder() {
        return 6;
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.NONE;
    }

    @Override
    public String shortLabel() {
        return "杀进程恢复";
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.string("packageName", "包名")
                        .withHelp("留空则取当前流程的应用包名"),
                ParamDef.number("waitMs", "回收后等待毫秒", 2000)
                        .withHelp("等系统把进程真正回收掉再恢复；太短会恢复到一个还没死透的进程，测不出东西"),
                ParamDef.bool("assertAlive", "恢复后校验没崩", true)
                        .withHelp("恢复后确认应用仍在前台。关掉的话崩了也不会当场变红，只能靠事后翻日志")
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        if (ctx.isIos()) {
            throw ctx.fail("杀进程恢复", "仅 Android 支持", null,
                    "iOS 无法从外部模拟系统回收；对应场景请用「冷启动」或人工后台挂起");
        }
        String pkg = ctx.packageName();
        if (pkg == null || pkg.isBlank()) {
            throw ctx.fail("杀进程恢复", "拿不到包名", null, "请在流程或步骤上指定包名");
        }
        String deviceId = ctx.getDeviceId();
        long waitMs = Math.max(500L, ctx.longParam("waitMs", 2000L));

        // am kill 不是 force-stop：前者留着任务栈，再进去才是恢复路径
        adb(deviceId, "am", "kill", pkg);
        ctx.ok("杀进程", "已模拟系统回收（am kill，任务栈保留）：" + pkg);
        Thread.sleep(waitMs);

        // monkey 拉起走的是「点桌面图标」那条路，跟从最近任务恢复等价
        adb(deviceId, "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1");
        Thread.sleep(1500L);

        ctx.screenshotWhenSettled("恢复后");

        if (ctx.boolParam("assertAlive", true)) {
            String top = adb(deviceId, "dumpsys", "activity", "activities");
            if (top != null && !top.contains(pkg)) {
                throw ctx.fail("杀进程恢复", "恢复后应用不在前台，可能已崩溃", pkg,
                        "被系统回收后恢复失败——这正是这一步要抓的问题，去看崩溃日志");
            }
        }
        ctx.ok("杀进程恢复", "已从最近任务恢复：" + pkg);
    }

    /** 直接走 adb，不经 Appium：这一步本来就是要绕过应用自身的生命周期 */
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
                while ((line = r.readLine()) != null && sb.length() < 200_000) {
                    sb.append(line).append('\n');
                }
            }
            p.waitFor(20, TimeUnit.SECONDS);
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
