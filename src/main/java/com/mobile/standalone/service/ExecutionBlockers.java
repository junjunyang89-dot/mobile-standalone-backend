package com.mobile.standalone.service;

import java.util.List;
import java.util.Locale;

/**
 * 判断一次失败是「被测应用真的不对」还是「根本没跑成」。
 *
 * <p><b>为什么要分</b>：这两件事现在都叫「失败」，混在同一个通过率里，
 * 而它们要找的人完全不是同一批：
 * <ul>
 *   <li>断言跑了、没通过 → <b>FAILED</b>，是产品或用例的问题，测试同学看</li>
 *   <li>设备连不上、Appium 会话没了、控件树抓不到、应用拉不起来 → <b>BLOCKED</b>，
 *       是环境的问题，谁维护主控机谁看</li>
 * </ul>
 * 不分的后果不是少了个字段，是<b>通过率在说谎</b>：模拟器卡了一晚上，
 * 早上看到的是「通过率 40%」，人会去查用例、查应用、查定位，
 * 而真相是那 60% 压根没跑到断言那一步。
 *
 * <p><b>判定一律偏保守：拿不准就算 FAILED。</b>
 * 误判成 FAILED 最多让人多查一会儿；误判成 BLOCKED 会把一个真实的产品缺陷
 * 从通过率里摘出去，藏起来——那比现在的状况更糟。
 * 所以这里只认<b>明确是环境</b>的那几类，短语都取自平台自己抛出的文案。
 */
public final class ExecutionBlockers {

    private ExecutionBlockers() {
    }

    /**
     * 明确属于环境／基础设施的失败短语。
     *
     * <p>都是平台自己写的文案，不是第三方库的——第三方的措辞会随版本变，
     * 拿它做判定条件，升级一次库就悄悄失效了，而且没人会发现。
     */
    private static final List<String> ENVIRONMENT_PHRASES = List.of(
            // —— 设备 ——
            "设备不存在",
            "设备未连接",
            "设备已离线",
            "设备正被占用",
            "没有可用设备",
            "排队超时",
            // —— Appium / 驱动 ——
            "a session is either terminated or not started",
            "invalid session id",
            "无法拉起或连通 appium",
            "appium 不可用",
            "没有 appium 会话",
            "挂接 android appium 会话失败",
            // —— 控件树 / 截图 ——
            "无法获取控件树",
            "无法读取截图",
            "无法读取截图/屏幕尺寸",
            "这一刻没抓到控件树",
            // —— 应用装不上 / 拉不起来 ——
            "安装失败",
            "下载安装包失败",
            // —— 配置缺失：跑不起来，不是被测应用的问题 ——
            "未指定包名",
            "应用字典不存在",
            "应用字典未配置包名",
            "是模拟器，不能跑生产环境的流程"
    );

    /**
     * 这次失败是不是「没跑成」而不是「跑了没过」。
     *
     * @param e 失败异常；链上任意一层命中就算
     */
    public static boolean isBlocked(Throwable e) {
        int guard = 0;
        for (Throwable t = e; t != null && guard++ < 10; t = t.getCause()) {
            if (t instanceof UiFlowExecutionException ufe && ufe.isBlocked()) {
                return true;
            }
            if (matches(t.getMessage())) {
                return true;
            }
            if (t == t.getCause()) {
                break;
            }
        }
        return false;
    }

    /**
     * 接口测试专用：请求根本没发出去 / 没收回来。
     *
     * <p>按<b>异常类型</b>判，不按文案判——这几类是 JDK 自己抛的，类型稳定，
     * 而它们的 message 会随 JDK 版本和 DNS 实现变。
     * 上面那套文案匹配只用在平台自己抛的异常上，两者不要混。
     *
     * <p>为什么值得单独一条：接口服务挂了的时候，整批用例会一起红。
     * 记成 FAILED 的话，报告上是「200 条用例失败」，看起来像 200 个 bug，
     * 人会一条条点开看断言；记成 BLOCKED，它就是一句「服务连不上」。
     */
    public static boolean isTransportFailure(Throwable e) {
        int guard = 0;
        for (Throwable t = e; t != null && guard++ < 10; t = t.getCause()) {
            if (t instanceof java.net.ConnectException
                    || t instanceof java.net.UnknownHostException
                    || t instanceof java.net.SocketTimeoutException
                    || t instanceof java.net.NoRouteToHostException
                    || t instanceof java.net.PortUnreachableException
                    || t instanceof java.net.http.HttpTimeoutException
                    || t instanceof javax.net.ssl.SSLException) {
                return true;
            }
            if (t == t.getCause()) {
                break;
            }
        }
        // IOException 太宽（响应体读一半断了也是它），只认明确是连接层的那几种。
        // 宁可漏判成 FAILED，也不能把真实的接口错误藏进 BLOCKED 里
        return false;
    }

    /** 单独暴露给单测和调用方：只看一句话算不算环境问题 */
    public static boolean matches(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String m = message.toLowerCase(Locale.ROOT);
        for (String phrase : ENVIRONMENT_PHRASES) {
            if (m.contains(phrase)) {
                return true;
            }
        }
        return false;
    }
}
