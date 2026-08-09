package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 失败原因指纹。
 * <p>
 * 这东西要同时满足两个相反的要求：
 * <ul>
 *   <li><b>换了原因要换签名</b>——否则修完 bug 重跑会复用旧结论，
 *       报告上还是过时的分析，人以为没修好，回去接着改</li>
 *   <li><b>同一个原因要保持同签名</b>——里面的时间戳、耗时、路径每次都变，
 *       不抹掉就永远命中不了复用，每次都白调一次模型</li>
 * </ul>
 */
class DiagnosisSignatureTest {

    /** 本组核心：这正是「iOS 暂仅支持 CHANNEL_PAGE」修完之后仍显示旧结论的原因 */
    @Test
    void 不同失败原因指纹不同() {
        String a = FailureDiagnosisService.errorFingerprint("iOS 暂仅支持 CHANNEL_PAGE，其它 installMode 后续接入");
        String b = FailureDiagnosisService.errorFingerprint("ApplicationVerificationFailed: 签名不匹配");

        assertNotEquals(a, b, "换了失败原因还复用旧结论，等于告诉人「你没修好」");
    }

    /** 时间戳、耗时不同不该算成新问题——否则复用永远不命中，每次白调模型 */
    @Test
    void 只有数字不同算同一个问题() {
        String a = FailureDiagnosisService.errorFingerprint("等待元素超时 5000ms（第 3 次重试）");
        String b = FailureDiagnosisService.errorFingerprint("等待元素超时 8000ms（第 7 次重试）");

        assertEquals(a, b);
    }

    /** 临时目录每次都不一样，不抹掉的话同一个安装失败会算成无数个问题 */
    @Test
    void 路径不同算同一个问题() {
        String a = FailureDiagnosisService.errorFingerprint("安装失败 file=/tmp/pgyer-a1b2/app.ipa");
        String b = FailureDiagnosisService.errorFingerprint("安装失败 file=/tmp/pgyer-c3d4/app.ipa");

        assertEquals(a, b);
    }

    @Test
    void 大小写和空白不影响() {
        assertEquals(FailureDiagnosisService.errorFingerprint("Install   Failed"),
                FailureDiagnosisService.errorFingerprint("install failed"));
    }

    @Test
    void 空输入返回空串而不是null() {
        assertEquals("", FailureDiagnosisService.errorFingerprint(null));
        assertEquals("", FailureDiagnosisService.errorFingerprint("   "));
    }

    /** 签名列有长度上限，指纹不能把它撑爆 */
    @Test
    void 超长原因会截断() {
        assertTrue(FailureDiagnosisService.errorFingerprint("很长的报错".repeat(200)).length() <= 80);
    }
}
