package com.mobile.standalone.service.pool;

import com.mobile.standalone.service.pool.DevicePicker.Candidate;
import com.mobile.standalone.service.pool.DevicePicker.Requirement;
import com.mobile.standalone.service.pool.DevicePicker.Result;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 挑设备的规则错一条，后果是任务跑在了不该跑的设备上——而且不会报错。
 * 测试包的用例落到生产机、生产巡检落到模拟器，两种都会安静地给出一份看着正常的结果。
 * 这是最不该只靠肉眼 review 的一类代码。
 */
class DevicePickerTest {

    private static LocalDateTime at(int minute) {
        return LocalDateTime.of(2026, 9, 6, 10, 0).plusMinutes(minute);
    }

    private static Candidate dev(String id, String platform, String env, boolean emulator,
                                 boolean connected, boolean disabled, boolean busy, LocalDateTime used) {
        return new Candidate(id, id, platform, env, emulator, connected, disabled, busy, used);
    }

    private static Candidate phone(String id, String env, LocalDateTime used) {
        return dev(id, "ANDROID", env, false, true, false, false, used);
    }

    private static Candidate emulator(String id, String env, LocalDateTime used) {
        return dev(id, "ANDROID", env, true, true, false, false, used);
    }

    private static Requirement req(String platform, String env, boolean allowEmulator) {
        return new Requirement(platform, env, allowEmulator);
    }

    /** 三真机 + 三模拟器，就是目标里那六台 */
    private static List<Candidate> sixDevices() {
        return List.of(
                phone("p1", "TEST", at(50)),
                phone("p2", "TEST", at(10)),
                phone("p3", "TEST", at(30)),
                emulator("e1", "TEST", at(40)),
                emulator("e2", "TEST", at(20)),
                emulator("e3", "TEST", null));
    }

    // ==================== 轮转 ====================

    /**
     * 从没派过活的排最前。
     * <p>
     * 如果把「没用过」排到最后，一台刚接进来的设备要等所有老设备都轮一遍才轮得到，
     * 那新加设备就完全没起到分担的作用。
     */
    @Test
    void 从没用过的设备优先派() {
        assertEquals("e3", DevicePicker.pick(sixDevices(), req("ANDROID", "TEST", true)).picked().id());
    }

    @Test
    void 只用真机时在真机里挑最久没用的() {
        assertEquals("p2", DevicePicker.pick(sixDevices(), req("ANDROID", "TEST", false)).picked().id());
    }

    /** 「轮着来」的本质：刚派过的那台不能紧接着又被选中 */
    @Test
    void 刚派过活的设备不会紧接着再被选中() {
        List<Candidate> after = new ArrayList<>();
        for (Candidate c : sixDevices()) {
            after.add(c.id().equals("e3")
                    ? dev("e3", "ANDROID", "TEST", true, true, false, false, at(99))
                    : c);
        }
        assertEquals("p2", DevicePicker.pick(after, req("ANDROID", "TEST", true)).picked().id());
    }

    /** 同样的输入永远挑同一台，出了问题才复现得出来 */
    @Test
    void 闲置时间相同时按id定序() {
        List<Candidate> tie = List.of(phone("pb", "TEST", at(5)), phone("pa", "TEST", at(5)));
        assertEquals("pa", DevicePicker.pick(tie, req("ANDROID", "TEST", false)).picked().id());
    }

    // ==================== 模拟器隔离 ====================

    @Test
    void 不允许模拟器时宁可挑不到() {
        List<Candidate> onlyEmulators = List.of(emulator("e1", "TEST", at(1)), emulator("e2", "TEST", at(2)));
        Result r = DevicePicker.pick(onlyEmulators, req("ANDROID", "TEST", false));
        assertFalse(r.ok());
        assertTrue(r.reason().contains("全是模拟器"), r.reason());
    }

    // ==================== 环境匹配 ====================

    /**
     * 派错环境不会报错，现象是跑到登录那步失败——
     * 人会去查账号、查网络、查风控，最后才想到是包不对。所以这一条不能放松。
     */
    @Test
    void 按环境隔离设备() {
        List<Candidate> mixed = List.of(phone("prod1", "PROD", at(1)), phone("test1", "TEST", at(2)));
        assertEquals("prod1", DevicePicker.pick(mixed, req("ANDROID", "PROD", false)).picked().id());
        assertEquals("test1", DevicePicker.pick(mixed, req("ANDROID", "TEST", false)).picked().id());
    }

    @Test
    void 设备没绑环境时两边都能派() {
        assertEquals("any", DevicePicker.pick(
                List.of(phone("any", null, at(1))), req("ANDROID", "PROD", false)).picked().id());
        assertEquals("any", DevicePicker.pick(
                List.of(phone("any", "  ", at(1))), req("ANDROID", "TEST", false)).picked().id());
    }

    @Test
    void 环境都不对时说得出该怎么办() {
        Result r = DevicePicker.pick(List.of(phone("p", "PROD", at(1))), req("ANDROID", "TEST", false));
        assertFalse(r.ok());
        assertTrue(r.reason().contains("留空"), r.reason());
    }

    // ==================== 可用性 ====================

    @Test
    void 离线停用在忙的都不派() {
        Requirement r = req("ANDROID", "TEST", false);
        assertFalse(DevicePicker.pick(
                List.of(dev("off", "ANDROID", "TEST", false, false, false, false, at(1))), r).ok());
        assertFalse(DevicePicker.pick(
                List.of(dev("bad", "ANDROID", "TEST", false, true, true, false, at(1))), r).ok());
        assertFalse(DevicePicker.pick(
                List.of(dev("busy", "ANDROID", "TEST", false, true, false, true, at(1))), r).ok());
    }

    /** 被人借走的设备也是 busy，跟跑着任务一样跳过 */
    @Test
    void 在忙的即使闲置最久也跳过() {
        List<Candidate> pool = List.of(
                dev("idleLong", "ANDROID", "TEST", false, true, false, true, at(1)),
                phone("idleShort", "TEST", at(90)));
        assertEquals("idleShort", DevicePicker.pick(pool, req("ANDROID", "TEST", false)).picked().id());
    }

    @Test
    void 都在忙时提示排队() {
        Result r = DevicePicker.pick(
                List.of(dev("busy", "ANDROID", "TEST", false, true, false, true, at(1))),
                req("ANDROID", "TEST", false));
        assertTrue(r.reason().contains("排队"), r.reason());
    }

    // ==================== 平台 ====================

    @Test
    void 按平台挑并且大小写不敏感() {
        List<Candidate> cross = List.of(
                dev("ios1", "IOS", "TEST", false, true, false, false, at(1)),
                phone("and1", "TEST", at(2)));
        assertEquals("ios1", DevicePicker.pick(cross, req("IOS", "TEST", false)).picked().id());
        assertEquals("and1", DevicePicker.pick(cross, req("android", "TEST", false)).picked().id());
        assertEquals("ios1", DevicePicker.pick(cross, req(null, "TEST", false)).picked().id());
    }

    @Test
    void 没有对应平台时说清楚缺哪个平台() {
        Result r = DevicePicker.pick(List.of(phone("a", "TEST", at(1))), req("IOS", "TEST", false));
        assertFalse(r.ok());
        assertTrue(r.reason().contains("IOS"), r.reason());
    }

    // ==================== 边界 ====================

    @Test
    void 空池子与空入参不抛异常() {
        assertFalse(DevicePicker.pick(List.of(), req("ANDROID", "TEST", true)).ok());
        assertFalse(DevicePicker.pick(null, req("ANDROID", "TEST", true)).ok());
        assertFalse(DevicePicker.pick(sixDevices(), null).ok());
        assertNull(DevicePicker.pick(List.of(), req("ANDROID", "TEST", true)).picked());
    }

    /** 挑不到时报出数量：「6 台里 4 台在忙」比「没有可用设备」有排查价值 */
    @Test
    void 挑不到时报出数量() {
        Result r = DevicePicker.pick(
                List.of(dev("o1", "ANDROID", "TEST", false, false, false, false, null),
                        dev("o2", "ANDROID", "TEST", false, false, false, false, null)),
                req("ANDROID", "TEST", true));
        assertTrue(r.reason().contains("共 2 台"), r.reason());
    }
}
