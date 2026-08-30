package com.mobile.standalone.service;

import com.mobile.standalone.entity.AiTestCase;
import com.mobile.standalone.entity.UiFlow;
import com.mobile.standalone.mapper.AiTestCaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用例 ↔ 流程的关联：过期判定、平台校验、从流程侧批量设置。
 * <p>
 * 最要紧的是 <b>flowStale</b>：用例的期望结果从「四项区号」改成六项，流程还在断言四项，
 * 覆盖标记照样是绿的，但它覆盖的是旧需求。这比没做自动化更糟——
 * 没做的话人知道要手测，假绿的话没人再看它。
 */
class AiTestCaseFlowLinkTest {

    private static class FakeMapper implements AiTestCaseMapper {
        final Map<Long, AiTestCase> rows = new HashMap<>();
        final AtomicLong seq = new AtomicLong();

        @Override
        public AiTestCase findById(Long id) {
            return rows.get(id);
        }

        @Override
        public List<AiTestCase> findByFlowId(String flowId) {
            return rows.values().stream().filter(r -> flowId.equals(r.getFlowId())).toList();
        }

        @Override
        public List<AiTestCase> findLinkable(String flowId, String platform, String keyword) {
            List<AiTestCase> out = new ArrayList<>();
            for (AiTestCase r : rows.values()) {
                boolean free = r.getFlowId() == null || r.getFlowId().isBlank() || flowId.equals(r.getFlowId());
                boolean samePlatform = platform == null || platform.isBlank()
                        || r.getPlatform() == null || platform.equals(r.getPlatform());
                if (free && samePlatform) {
                    out.add(r);
                }
            }
            return out;
        }

        @Override
        public List<java.util.Map<String, Object>> countByRequirement(String category, String appVersion) {
            return List.of();
        }

        @Override
        public List<java.util.Map<String, Object>> distinctVersions(String category) {
            return List.of();
        }

        @Override
        public List<AiTestCase> findByFilters(String category, String pageName, String priority, String status,
                                              String platform, String batchId, String automationAdvice,
                                              String releaseStage, String appVersion,
                                              String requirementNo, String side, String keyword,
                                              int offset, int limit) {
            return List.copyOf(rows.values());
        }

        @Override
        public int countByFilters(String category, String pageName, String priority, String status,
                                  String platform, String batchId, String automationAdvice,
                                  String releaseStage, String appVersion,
                                  String requirementNo, String side, String keyword) {
            return rows.size();
        }

        @Override
        public int insert(AiTestCase row) {
            row.setId(seq.incrementAndGet());
            rows.put(row.getId(), row);
            return 1;
        }

        @Override
        public int update(AiTestCase row) {
            rows.put(row.getId(), row);
            return 1;
        }

        @Override
        public int deleteById(Long id) {
            return rows.remove(id) == null ? 0 : 1;
        }
    }

    private static class FakeFlowService extends UiFlowService {
        final Map<String, UiFlow> flows = new HashMap<>();

        FakeFlowService() {
            super(null, null, null);
        }

        @Override
        public UiFlow get(String id) {
            return flows.get(id);
        }

        void put(String id, String name, String platform) {
            put(id, name, platform, "AUTOMATION");
        }

        void put(String id, String name, String platform, String catalog) {
            UiFlow f = new UiFlow();
            f.setId(id);
            f.setName(name);
            f.setPlatform(platform);
            f.setCatalog(catalog);
            f.setEnabled(true);
            flows.put(id, f);
        }
    }

    private FakeMapper mapper;
    private FakeFlowService flows;
    private AiTestCaseService service;

    @BeforeEach
    void setUp() {
        mapper = new FakeMapper();
        flows = new FakeFlowService();
        // taskMapper / jdbcTemplate 这组用例走不到，传 null（service 内部已对 null 做了保护）
        service = new AiTestCaseService(mapper, flows, null, null);
    }

    private AiTestCase newCase(String title, String platform) {
        AiTestCase c = new AiTestCase();
        c.setCategory("wmpvp-android");
        c.setTitle(title);
        c.setPlatform(platform);
        return c;
    }

    private AiTestCase patchOf(String field, String value) {
        AiTestCase p = new AiTestCase();
        switch (field) {
            case "expectedResult" -> p.setExpectedResult(value);
            case "stepsJson" -> p.setStepsJson(value);
            case "remark" -> p.setRemark(value);
            case "priority" -> p.setPriority(value);
            case "flowId" -> p.setFlowId(value);
            default -> throw new IllegalArgumentException(field);
        }
        return p;
    }

    // ==================== 流程过期判定 ====================

    @Test
    void 刚关联时不算过期() {
        flows.put("uif-1", "登录流程", "Android");
        AiTestCase c = service.createManual(newCase("区号选择", "Android"));
        service.updateManual(c.getId(), patchOf("flowId", "uif-1"));

        assertEquals(Boolean.FALSE, service.toView(mapper.findById(c.getId())).get("flowStale"));
    }

    /** 本组核心：真实场景就是「期望结果从四项改成六项，流程还在断言四项」 */
    @Test
    void 关联后改了期望结果就标过期() throws Exception {
        flows.put("uif-1", "登录流程", "Android");
        AiTestCase c = service.createManual(newCase("区号选择", "Android"));
        c.setExpectedResult("下拉固定展示四项区号");
        mapper.update(c);
        service.updateManual(c.getId(), patchOf("flowId", "uif-1"));

        Thread.sleep(10);
        service.updateManual(c.getId(), patchOf("expectedResult", "下拉固定展示六项区号"));

        Map<String, Object> view = service.toView(mapper.findById(c.getId()));
        assertEquals(Boolean.TRUE, view.get("flowStale"),
                "用例改了流程没改，标记还绿着——覆盖的是旧需求，这是最隐蔽的覆盖率造假");
        assertEquals(Boolean.TRUE, view.get("automated"), "过期不等于没覆盖，两个标记要能同时看到");
    }

    @Test
    void 改了步骤也算过期() throws Exception {
        flows.put("uif-1", "登录流程", "Android");
        AiTestCase c = service.createManual(newCase("区号选择", "Android"));
        service.updateManual(c.getId(), patchOf("flowId", "uif-1"));

        Thread.sleep(10);
        service.updateManual(c.getId(), patchOf("stepsJson", "[{\"action\":\"点两下\"}]"));

        assertEquals(Boolean.TRUE, service.toView(mapper.findById(c.getId())).get("flowStale"));
    }

    /**
     * 这条决定这个标记有没有人看。改备注、改优先级也报过期的话，
     * 人核过两三次假的就不看了，真的那次也一起废掉。
     */
    @Test
    void 改备注和优先级不算过期() throws Exception {
        flows.put("uif-1", "登录流程", "Android");
        AiTestCase c = service.createManual(newCase("区号选择", "Android"));
        service.updateManual(c.getId(), patchOf("flowId", "uif-1"));

        Thread.sleep(10);
        service.updateManual(c.getId(), patchOf("remark", "找 xx 确认过"));
        service.updateManual(c.getId(), patchOf("priority", "P0"));

        assertEquals(Boolean.FALSE, service.toView(mapper.findById(c.getId())).get("flowStale"),
                "跟流程无关的字段不该报过期，误报几次这个标记就没人看了");
    }

    /** 流程按新用例改完了，重新关联一次即可把标记清掉，不用去数据库改时间 */
    @Test
    void 重新关联后过期标记清掉() throws Exception {
        flows.put("uif-1", "登录流程", "Android");
        flows.put("uif-2", "登录流程v2", "Android");
        AiTestCase c = service.createManual(newCase("区号选择", "Android"));
        service.updateManual(c.getId(), patchOf("flowId", "uif-1"));
        Thread.sleep(10);
        service.updateManual(c.getId(), patchOf("expectedResult", "改了"));
        assertEquals(Boolean.TRUE, service.toView(mapper.findById(c.getId())).get("flowStale"));

        service.updateManual(c.getId(), patchOf("flowId", "uif-2"));

        assertEquals(Boolean.FALSE, service.toView(mapper.findById(c.getId())).get("flowStale"));
    }

    @Test
    void 没关联流程时不谈过期() {
        AiTestCase c = service.createManual(newCase("还没做", "Android"));

        assertEquals(Boolean.FALSE, service.toView(mapper.findById(c.getId())).get("flowStale"));
    }

    // ==================== 平台校验 ====================

    /** 两端用例是两拨人分开写的两套，挂错了不报错、只会永远显示「未执行」 */
    @Test
    void 安卓用例不能挂iOS流程() {
        flows.put("uif-ios", "iOS登录", "iOS");
        AiTestCase c = service.createManual(newCase("区号选择", "Android"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.updateManual(c.getId(), patchOf("flowId", "uif-ios")));

        assertTrue(e.getMessage().contains("平台不一致"), e.getMessage());
        assertTrue(e.getMessage().contains("iOS") && e.getMessage().contains("Android"), e.getMessage());
    }

    @Test
    void 同平台正常关联() {
        flows.put("uif-1", "登录流程", "Android");
        AiTestCase c = service.createManual(newCase("区号选择", "Android"));

        AiTestCase out = service.updateManual(c.getId(), patchOf("flowId", "uif-1"));

        assertEquals("uif-1", out.getFlowId());
        assertNotNull(out.getLinkedAt());
    }

    /** 流程没填平台（通用流程）时不拦 */
    @Test
    void 流程未指定平台时不校验() {
        flows.put("uif-any", "通用流程", null);
        AiTestCase c = service.createManual(newCase("区号选择", "Android"));

        assertEquals("uif-any", service.updateManual(c.getId(), patchOf("flowId", "uif-any")).getFlowId());
    }

    // ==================== 只有自动化编排轨的流程能覆盖用例 ====================

    /**
     * 判断依据是「这条流程做没做那件事」，不是它出现在哪个入口。
     * 一条仅回归的登录流程确实执行了这条用例的步骤，那它就是这条用例的实现。
     */
    @Test
    void 仅回归的流程也能关联用例() {
        flows.put("uif-reg", "登录流程", "Android", "ONLINE");
        AiTestCase c = service.createManual(newCase("协议勾选框", "Android"));

        assertEquals("uif-reg",
                service.updateManual(c.getId(), patchOf("flowId", "uif-reg")).getFlowId());
    }

    /** 装包不执行任何用例步骤——它是把设备弄成可测状态，挂上去必然是假覆盖 */
    @Test
    void 装包准备不能关联用例() {
        flows.put("uif-install", "安装蒲公英回归包", "Android", "INSTALL");
        AiTestCase c = service.createManual(newCase("协议勾选框", "Android"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.updateManual(c.getId(), patchOf("flowId", "uif-install")));

        assertTrue(e.getMessage().contains("假覆盖"), e.getMessage());
    }

    /** 性能流程采的是资源曲线，不验功能 */
    @Test
    void 仅性能的流程不能关联() {
        flows.put("uif-perf", "启动耗时", "Android", "PERFORMANCE");
        AiTestCase c = service.createManual(newCase("协议勾选框", "Android"));

        assertThrows(IllegalArgumentException.class,
                () -> service.updateManual(c.getId(), patchOf("flowId", "uif-perf")));
    }

    /**
     * 本组核心。「回归 + 自动化编排」是大多数流程的用途，必须能关联——
     * 一条流程既进回归批次、又确实执行了某条用例的步骤，这两件事是正交的，
     * 硬要二选一等于让大多数流程都关联不上用例，覆盖率永远是 0。
     */
    @Test
    void 回归加自动化的流程能关联() {
        flows.put("uif-shared", "测试环境发版前回归-登录", "Android", "SHARED");
        AiTestCase c = service.createManual(newCase("协议勾选框", "Android"));

        assertEquals("uif-shared",
                service.updateManual(c.getId(), patchOf("flowId", "uif-shared")).getFlowId());
        assertEquals(Boolean.TRUE, service.toView(mapper.findById(c.getId())).get("automated"));
    }

    @Test
    void 仅自动化编排的流程可以关联() {
        flows.put("uif-auto", "调试中的流程", "Android", "AUTOMATION");
        AiTestCase c = service.createManual(newCase("协议勾选框", "Android"));

        assertEquals("uif-auto",
                service.updateManual(c.getId(), patchOf("flowId", "uif-auto")).getFlowId());
    }

    /**
     * 存量脏数据：之前已经挂上去的回归流程，不用写迁移脚本改数据——
     * 视图里直接判成未覆盖，因为它本来就没被自动化覆盖。
     */
    /** 挂了装包流程的用例显示为未覆盖，并标出是「挂错了流程」而不是「没挂」 */
    @Test
    void 挂了装包流程的用例显示为未覆盖() {
        flows.put("uif-install", "安装蒲公英回归包", "Android", "INSTALL");
        AiTestCase c = service.createManual(newCase("协议勾选框", "Android"));
        c.setFlowId("uif-install");
        mapper.update(c);

        Map<String, Object> view = service.toView(mapper.findById(c.getId()));

        assertEquals(Boolean.FALSE, view.get("automated"), "装包跑通不说明任何功能是好的");
        assertEquals(Boolean.TRUE, view.get("flowNotAutomation"));
    }

    @Test
    void 从流程侧也拦住装包流程() {
        flows.put("uif-install", "安装包", "Android", "INSTALL");
        AiTestCase c = service.createManual(newCase("协议勾选框", "Android"));

        assertThrows(IllegalArgumentException.class,
                () -> service.setFlowCases("uif-install", List.of(c.getId())));
    }

    // ==================== 从流程侧批量设置 ====================

    @Test
    void 一条流程可以覆盖多条用例() {
        flows.put("uif-1", "登录流程", "Android");
        AiTestCase a = service.createManual(newCase("手机号登录", "Android"));
        AiTestCase b = service.createManual(newCase("区号选择", "Android"));

        Map<String, Integer> r = service.setFlowCases("uif-1", List.of(a.getId(), b.getId()));

        assertEquals(2, r.get("linked"));
        assertEquals("uif-1", mapper.findById(a.getId()).getFlowId());
        assertEquals("uif-1", mapper.findById(b.getId()).getFlowId());
        assertNotNull(mapper.findById(b.getId()).getLinkedAt());
    }

    /** 取消勾选＝解除关联，同时把关联时间清掉，否则下次会拿旧时间算过期 */
    @Test
    void 不在列表里的解除关联() {
        flows.put("uif-1", "登录流程", "Android");
        AiTestCase a = service.createManual(newCase("手机号登录", "Android"));
        AiTestCase b = service.createManual(newCase("区号选择", "Android"));
        service.setFlowCases("uif-1", List.of(a.getId(), b.getId()));

        Map<String, Integer> r = service.setFlowCases("uif-1", List.of(a.getId()));

        assertEquals(1, r.get("unlinked"));
        assertNull(mapper.findById(b.getId()).getFlowId());
        assertNull(mapper.findById(b.getId()).getLinkedAt());
    }

    @Test
    void 全部取消时清空() {
        flows.put("uif-1", "登录流程", "Android");
        AiTestCase a = service.createManual(newCase("手机号登录", "Android"));
        service.setFlowCases("uif-1", List.of(a.getId()));

        service.setFlowCases("uif-1", List.of());

        assertNull(mapper.findById(a.getId()).getFlowId());
    }

    @Test
    void 从流程侧关联也校验平台() {
        flows.put("uif-ios", "iOS登录", "iOS");
        AiTestCase c = service.createManual(newCase("区号选择", "Android"));

        assertThrows(IllegalArgumentException.class,
                () -> service.setFlowCases("uif-ios", List.of(c.getId())));
    }

    /**
     * 候选里不出现「已挂别的流程」的用例：在这里勾上会把它从原流程悄悄抢走，
     * 而原流程那边毫无提示。
     */
    @Test
    void 候选不含已被别的流程占用的用例() {
        flows.put("uif-1", "登录流程", "Android");
        flows.put("uif-2", "另一条", "Android");
        AiTestCase a = service.createManual(newCase("手机号登录", "Android"));
        service.setFlowCases("uif-1", List.of(a.getId()));
        AiTestCase b = service.createManual(newCase("区号选择", "Android"));

        List<Map<String, Object>> out = service.linkableCases("uif-2", "Android", null);

        assertEquals(1, out.size());
        assertEquals(b.getId(), out.get(0).get("id"));
    }

    @Test
    void 候选包含已挂本流程的用例() {
        flows.put("uif-1", "登录流程", "Android");
        AiTestCase a = service.createManual(newCase("手机号登录", "Android"));
        service.setFlowCases("uif-1", List.of(a.getId()));

        assertFalse(service.linkableCases("uif-1", "Android", null).isEmpty(),
                "已勾选的要能显示成已勾选，否则打开编辑框会以为没关联过");
    }
}
