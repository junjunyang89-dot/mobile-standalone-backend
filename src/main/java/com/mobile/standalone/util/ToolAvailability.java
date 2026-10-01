package com.mobile.standalone.util;

/**
 * 外部命令行工具的可用性判定。
 *
 * <p><b>为什么要分清"没装"和"装了但不在 PATH"</b>:这两种的排查方向完全不同,
 * 而现在的表现是一样的——设备列表空着,没有任何提示。
 *
 * <ul>
 *   <li><b>没装</b>:去装(`brew install`、下 SDK)</li>
 *   <li><b>装了但后端进程看不见</b>:后端的 PATH 和人的终端 PATH 不是一回事。
 *       从登录 shell 跑起来的后端能看到 {@code adb},而 launchd / LaunchAgent
 *       开机自启拉起来的后端,PATH 只有 {@code /usr/bin:/bin:/usr/sbin:/sbin}——
 *       <b>同一台机器、同一份代码,启动方式不同,结果就不同。</b></li>
 * </ul>
 *
 * <p>第二种是这台给 50 人用的常开机器上最可能发生的情况(几乎一定会配成开机自启),
 * 而它的症状最误导人:6 台设备一台都扫不出来,人会先怀疑数据线、怀疑手机、怀疑设备池代码,
 * 最后才想到 PATH。
 *
 * <p>判定逻辑做成纯函数,不碰文件系统——这样每条结论都能用单测钉住,
 * 而"某台机器上到底怎么样"由 {@code ToolProbe} 负责去看。
 */
public final class ToolAvailability {

    private ToolAvailability() {
    }

    public enum Status {
        /** PATH 里就有,后端进程能直接调 */
        OK_ON_PATH,
        /** 装了,但不在后端进程的 PATH 里——只能靠绝对路径调 */
        OK_OFF_PATH,
        /** 找不到,大概是没装 */
        MISSING
    }

    /**
     * @param name         工具名,例如 adb
     * @param status       判定结果
     * @param resolvedPath 最终能用的绝对路径;MISSING 时为 null
     * @param hint         给人看的下一步。<b>这是这个接口真正的产出</b>——
     *                     一个只说"adb: MISSING"的健康检查,和没有健康检查差不多
     */
    public record Result(String name, Status status, String resolvedPath, String hint) {

        public boolean usable() {
            return status != Status.MISSING;
        }
    }

    /**
     * 判定。
     *
     * @param name          工具名
     * @param onPath        PATH 查找的结果(which);没找到传 null
     * @param candidatePath 候选绝对路径里找到的;没找到传 null
     * @param installHint   没装时怎么装,例如 {@code brew install mitmproxy}
     */
    public static Result judge(String name, String onPath, String candidatePath, String installHint) {
        if (notBlank(onPath)) {
            return new Result(name, Status.OK_ON_PATH, onPath.trim(), null);
        }
        if (notBlank(candidatePath)) {
            return new Result(name, Status.OK_OFF_PATH, candidatePath.trim(),
                    name + " 装在 " + candidatePath.trim() + "，但不在后端进程的 PATH 里。"
                            + "代码里凡是直接调 \"" + name + "\" 的地方都会失败，"
                            + "而表现通常是「功能没反应」而不是报错。"
                            + "如果后端是 launchd / 开机自启拉起来的，"
                            + "PATH 只有 /usr/bin:/bin:/usr/sbin:/sbin——"
                            + "请在 plist 的 EnvironmentVariables 里补上，"
                            + "或改用 scripts/run-backend.sh 启动");
        }
        String how = notBlank(installHint) ? "。装法：" + installHint.trim() : "";
        return new Result(name, Status.MISSING, null,
                "找不到 " + name + "，PATH 和常见安装位置都没有" + how);
    }

    /**
     * 整体结论:所有必需工具都可用吗。
     *
     * <p>注意它<b>不看</b>可选工具——把"没装 solox"和"没装 adb"混成同一个红灯,
     * 人第二周就不看这个接口了。
     */
    public static boolean allRequiredUsable(java.util.Collection<Result> required) {
        if (required == null) {
            return true;
        }
        return required.stream().allMatch(Result::usable);
    }

    /** 一句话汇总,直接给人看 */
    public static String summarize(java.util.Collection<Result> required,
                                   java.util.Collection<Result> optional) {
        long reqBad = required == null ? 0 : required.stream().filter(r -> !r.usable()).count();
        long reqOffPath = required == null ? 0
                : required.stream().filter(r -> r.status() == Status.OK_OFF_PATH).count();
        long optBad = optional == null ? 0 : optional.stream().filter(r -> !r.usable()).count();

        if (reqBad > 0) {
            return "有 " + reqBad + " 个必需工具找不到，相关功能会静默不可用——先看下面 required 里标 MISSING 的";
        }
        if (reqOffPath > 0) {
            // 这条单独拎出来,因为它是"现在能用、换个启动方式就坏"的状态
            return "必需工具都能用，但有 " + reqOffPath
                    + " 个不在 PATH 里（靠绝对路径找到的）。代码里有大量直接调命令名的地方，"
                    + "这些地方会失败——建议把 PATH 配好，别依赖绝对路径兜底";
        }
        if (optBad > 0) {
            return "必需工具齐了；" + optBad + " 个可选工具没装（对应功能会降级，不影响主流程）";
        }
        return "全部工具就位";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
