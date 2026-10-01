package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「没跑成」和「跑了没过」的分界线。
 *
 * <p>这个判断有方向性：<b>误判成 FAILED 只是让人多查一会儿，
 * 误判成 BLOCKED 会把一个真实的产品缺陷从通过率里摘出去、藏起来</b>。
 * 所以下面「不该算 blocked」那一组比「该算」那一组更重要——
 * 它守的是「以后有人往短语表里加了一条太宽的词」这件事。
 */
class ExecutionBlockersTest {

    // ---------- 该算 blocked：环境/基础设施 ----------

    @Test
    void 设备类() {
        assertTrue(ExecutionBlockers.matches("设备未连接: emulator-5554"));
        assertTrue(ExecutionBlockers.matches("设备正被占用：测试任务「登录冒烟」（自 14:00 起）"));
        assertTrue(ExecutionBlockers.matches("排队 30 分钟仍未分配到设备：没有可用设备"));
    }

    @Test
    void 会话与控件树() {
        assertTrue(ExecutionBlockers.matches(
                "A session is either terminated or not started Build info: version: '4.15.0'"));
        assertTrue(ExecutionBlockers.matches("无法获取控件树：请先点「刷新截图」"));
        assertTrue(ExecutionBlockers.matches("这一刻没抓到控件树（设备太慢，或 uiautomator 被 Appium 占着）"));
    }

    @Test
    void 配置缺失也算没跑成() {
        // 包名没配 = 这条流程根本发不出去，不是被测应用的问题
        assertTrue(ExecutionBlockers.matches("应用字典未配置包名: DOMESTIC_ESPORTS / ANDROID"));
        assertTrue(ExecutionBlockers.matches("未指定包名：请在执行时填写包名"));
    }

    // ---------- 不该算 blocked：这些是真失败 ----------

    @Test
    void 断言失败不是没跑成() {
        assertFalse(ExecutionBlockers.matches("点击失败: btn_login (method=AUTO, tried=1)"));
        assertFalse(ExecutionBlockers.matches("断言未通过: 期望 code=0，实际 code=10001"));
        assertFalse(ExecutionBlockers.matches("元素库里没有这个元素: nameEn=btn_login"));
        assertFalse(ExecutionBlockers.matches("滑动失败: up #1"));
    }

    @Test
    void 元素找不到是回归要抓的东西不能藏() {
        // 界面改了导致元素定位不到——这正是 UI 回归存在的意义，必须留在失败里
        assertFalse(ExecutionBlockers.matches("元素「vg_recom_pager_coordinator」不在当前控件树里"));
    }

    @Test
    void 空与null() {
        assertFalse(ExecutionBlockers.matches(null));
        assertFalse(ExecutionBlockers.matches(""));
        assertFalse(ExecutionBlockers.matches("   "));
    }

    // ---------- 异常链 ----------

    @Test
    void 标记过的异常直接算blocked() {
        UiFlowExecutionException e = UiFlowExecutionException.blocked("随便什么文案", List.of());
        assertTrue(ExecutionBlockers.isBlocked(e));
    }

    @Test
    void 没标记的不算() {
        UiFlowExecutionException e = new UiFlowExecutionException("断言未通过", List.of());
        assertFalse(ExecutionBlockers.isBlocked(e));
    }

    @Test
    void 链上任意一层命中都算() {
        Exception root = new IllegalStateException("设备未连接: be469eba");
        Exception wrapped = new RuntimeException("执行流程失败", root);
        assertTrue(ExecutionBlockers.isBlocked(wrapped));
    }

    /** 自引用的 cause 不能把判定转成死循环——这种异常链真实存在过 */
    @Test
    void 自引用异常链不死循环() {
        RuntimeException loop = new RuntimeException("普通失败") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertFalse(ExecutionBlockers.isBlocked(loop));
    }

    // ---------- 接口侧：按异常类型判 ----------

    @Test
    void 连接类异常算没跑成() {
        assertTrue(ExecutionBlockers.isTransportFailure(new ConnectException("Connection refused")));
        assertTrue(ExecutionBlockers.isTransportFailure(new UnknownHostException("gwapi.example.cn")));
        assertTrue(ExecutionBlockers.isTransportFailure(new SocketTimeoutException("connect timed out")));
        assertTrue(ExecutionBlockers.isTransportFailure(
                new javax.net.ssl.SSLHandshakeException("PKIX path building failed")));
    }

    @Test
    void 包一层也认得出来() {
        assertTrue(ExecutionBlockers.isTransportFailure(
                new RuntimeException("请求失败", new ConnectException("Connection refused"))));
    }

    /**
     * 笼统的 IOException 不算。
     * <p>响应体读到一半断了也是 IOException，但那时请求已经发出去、服务也答了——
     * 把它算成「没跑成」就会把一类真实的服务端问题藏起来。
     */
    @Test
    void 笼统的IO异常不算() {
        assertFalse(ExecutionBlockers.isTransportFailure(new IOException("stream closed")));
        assertFalse(ExecutionBlockers.isTransportFailure(new RuntimeException("断言未通过")));
        assertFalse(ExecutionBlockers.isTransportFailure(null));
    }
}
