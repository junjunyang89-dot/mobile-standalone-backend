package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WDA 拉起失败后的重试策略。
 * <p>
 * 存在的理由：原来只有<b>认识的</b>报错才冷却，认不出的一律当「可以重试」——
 * 一个没见过的签名错误就能让 xcodebuild 无限重装 WDA，跑一晚上还在装。
 * <p>
 * 默认方向反了：<b>认不出的错应该更保守</b>。「不认识」恰恰说明我们不知道再试有没有用。
 */
class WdaRetryPolicyTest {

    /** 认识的硬失败：第一次就该停，再试一百次也是这个结果 */
    @Test
    void 描述文件过期第一次就冷却() {
        assertTrue(DeviceManager.cooldownMsFor("WDA 描述文件已过期(0xe8008011)：请重新签名", 1) > 0);
    }

    @Test
    void 未信任开发者第一次就冷却() {
        assertTrue(DeviceManager.cooldownMsFor("手机未信任开发者证书", 1) > 0);
    }

    /**
     * 本组核心。这正是这次踩到的：签名失败的报错不在白名单里，
     * 于是被当成「可以重试」，无限重装。
     */
    @Test
    void 认不出的错也会在几次后停下来() {
        String unknown = "some unexpected xcodebuild failure nobody has seen before";

        assertEquals(0, DeviceManager.cooldownMsFor(unknown, 1), "前几次留给真正的瞬时问题");
        assertEquals(0, DeviceManager.cooldownMsFor(unknown, 2));
        assertTrue(DeviceManager.cooldownMsFor(unknown, DeviceManager.IOS_WDA_MAX_ATTEMPTS) > 0,
                "连试 " + DeviceManager.IOS_WDA_MAX_ATTEMPTS + " 次还不行就别再试了");
    }

    /** 冷却结束后如果还是不行，等更久——别一放开又开始狂试 */
    @Test
    void 连续失败越多冷却越久() {
        String unknown = "unknown failure";
        long first = DeviceManager.cooldownMsFor(unknown, DeviceManager.IOS_WDA_MAX_ATTEMPTS);
        long later = DeviceManager.cooldownMsFor(unknown, DeviceManager.IOS_WDA_MAX_ATTEMPTS + 3);

        assertTrue(later > first, "退避没生效：" + first + " -> " + later);
    }

    /** 退避要有上限，否则失败几十次之后冷却会长到形同永久禁用，而设备可能早就修好了 */
    @Test
    void 退避有上限() {
        long huge = DeviceManager.cooldownMsFor("unknown failure", 100);

        assertTrue(huge <= DeviceManager.IOS_WDA_SOFT_FAIL_COOLDOWN_MS * 16,
                "冷却不能无限增长，设备修好了要能自己恢复");
    }

    @Test
    void 空原因按未知处理() {
        assertEquals(0, DeviceManager.cooldownMsFor(null, 1));
        assertTrue(DeviceManager.cooldownMsFor(null, DeviceManager.IOS_WDA_MAX_ATTEMPTS) > 0);
    }
}
