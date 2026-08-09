package com.mobile.standalone.service;

import com.mobile.standalone.entity.AiTestCase;
import com.mobile.standalone.entity.UiFlow;
import com.mobile.standalone.mapper.AiTestCaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
 * 手工录入用例，以及「是否已实现自动化」的推导。
 * <p>
 * 这组用例里最要紧的是：<b>自动化标记必须是算出来的，不能是存下来的</b>。
 * 人手填的布尔值会立刻过期——流程停用或删了，没人会回来改那一列，
 * 于是覆盖率长期偏高，看板越好看越没人信。
 */
class AiTestCaseManualTest {

    /** 只实现被 service 用到的那几个方法，够验行为就行 */
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
            return List.copyOf(rows.values());
        }

        @Override
        public List<AiTestCase> findByFilters(String category, String pageName, String priority, String status,
                                              String platform, String batchId, String automationAdvice,
                                              String releaseStage, String keyword, int offset, int limit) {
            return List.copyOf(rows.values());
        }

        @Override
        public int countByFilters(String category, String pageName, String priority, String status,
                                  String platform, String batchId, String automationAdvice,
                                  String releaseStage, String keyword) {
            return (int) rows.values().stream()
                    .filter(r -> category == null || category.equals(r.getCategory()))
                    .count();
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

    /** 只需要 get(id)，其余继承默认实现（构造参数传 null 即可，测试不会走到） */
    private static class FakeFlowService extends UiFlowService {
        final Map<String, UiFlow> flows = new HashMap<>();

        FakeFlowService() {
            super(null, null);
        }

        @Override
        public UiFlow get(String id) {
            return flows.get(id);
        }

        void put(String id, String name, boolean enabled) {
            UiFlow f = new UiFlow();
            f.setId(id);
            f.setName(name);
            f.setEnabled(enabled);
            // 只有「仅自动化编排」的流程才算覆盖用例；回归轨的流程不进这个判定
            f.setCatalog("AUTOMATION");
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
        // taskMapper / jdbcTemplate 传 null：这组用例不验最近执行与建表
        service = new AiTestCaseService(mapper, flows, null, null);
    }

    private AiTestCase newCase(String title) {
        AiTestCase c = new AiTestCase();
        c.setCategory("wmpvp-android");
        c.setTitle(title);
        return c;
    }

    // ==================== 手工录入 ====================

    @Test
    void 手工新建落成MANUAL来源() {
        AiTestCase saved = service.createManual(newCase("验证码登录成功"));

        assertEquals("MANUAL", saved.getSource(), "来源要能跟生成器的批量落库区分开");
        assertEquals("DRAFT", saved.getStatus());
        assertEquals("P1", saved.getPriority());
        assertEquals("[]", saved.getStepsJson(), "步骤留空也要是合法 JSON，否则读的时候炸");
    }

    @Test
    void 标题和分类是必填() {
        assertThrows(IllegalArgumentException.class, () -> service.createManual(newCase("   ")));

        AiTestCase noCat = newCase("标题");
        noCat.setCategory(null);
        assertThrows(IllegalArgumentException.class, () -> service.createManual(noCat));
    }

    @Test
    void 同分类下的编号顺次生成() {
        assertEquals("TC-001", service.createManual(newCase("A")).getCaseId());
        assertEquals("TC-002", service.createManual(newCase("B")).getCaseId());
    }

    /** page_name 建表时是 NOT NULL，手工录入未必填页面，不兜底就直接插入失败 */
    @Test
    void 没填页面时用模块名兜底() {
        AiTestCase c = newCase("登录");
        c.setModule("账号");

        assertEquals("账号", service.createManual(c).getPageName());

        AiTestCase bare = service.createManual(newCase("裸用例"));
        assertEquals("未分类", bare.getPageName());
    }

    // ==================== 编辑：没给的字段不能被清空 ====================

    @Test
    void 更新只覆盖请求里给了的字段() {
        AiTestCase saved = service.createManual(newCase("原标题"));
        saved.setRemark("原备注");
        mapper.update(saved);

        AiTestCase patch = new AiTestCase();
        patch.setTitle("新标题");

        AiTestCase out = service.updateManual(saved.getId(), patch);

        assertEquals("新标题", out.getTitle());
        assertEquals("原备注", out.getRemark(), "没传的字段不该被清空——前端漏传一个就丢内容");
    }

    /** 空串＝解除关联，null＝这次不改。两者混在一起就没法解除关联了 */
    @Test
    void 空串解除流程关联而null保持不变() {
        flows.put("uif-1", "登录流程", true);
        AiTestCase saved = service.createManual(newCase("登录"));
        saved.setFlowId("uif-1");
        mapper.update(saved);

        AiTestCase noTouch = new AiTestCase();
        noTouch.setTitle("改个标题");
        assertEquals("uif-1", service.updateManual(saved.getId(), noTouch).getFlowId());

        AiTestCase clear = new AiTestCase();
        clear.setFlowId("");
        assertNull(service.updateManual(saved.getId(), clear).getFlowId());
    }

    @Test
    void 更新不存在的用例返回null() {
        assertNull(service.updateManual(999L, new AiTestCase()));
    }

    // ==================== 「是否已实现自动化」由 flowId 推导 ====================

    @Test
    void 关联了启用中的流程才算已自动化() {
        flows.put("uif-1", "登录流程", true);
        AiTestCase c = service.createManual(newCase("登录"));
        c.setFlowId("uif-1");
        mapper.update(c);

        Map<String, Object> view = service.toView(mapper.findById(c.getId()));

        assertEquals(Boolean.TRUE, view.get("automated"));
        assertEquals("登录流程", view.get("flowName"), "界面要能显示挂在哪条流程上");
        assertEquals(Boolean.FALSE, view.get("flowMissing"));
    }

    @Test
    void 没关联流程就是未覆盖() {
        Map<String, Object> view = service.toView(service.createManual(newCase("还没做")));

        assertEquals(Boolean.FALSE, view.get("automated"));
        assertNull(view.get("flowName"));
    }

    /**
     * 这条是整个设计的支点：流程被停用之后，覆盖率必须自己掉下来。
     * 存成一列人手填的布尔值就做不到这件事。
     */
    @Test
    void 流程被停用后自动变回未覆盖() {
        flows.put("uif-1", "登录流程", true);
        AiTestCase c = service.createManual(newCase("登录"));
        c.setFlowId("uif-1");
        mapper.update(c);
        assertEquals(Boolean.TRUE, service.toView(mapper.findById(c.getId())).get("automated"));

        flows.put("uif-1", "登录流程", false);

        assertEquals(Boolean.FALSE, service.toView(mapper.findById(c.getId())).get("automated"),
                "流程停用了覆盖率还挂着，看板就开始骗人");
    }

    /** 流程被删了要能看出来，而不是静悄悄显示成「未覆盖」——那会让人以为是自己没做 */
    @Test
    void 流程被删除时标出失联() {
        AiTestCase c = service.createManual(newCase("登录"));
        c.setFlowId("uif-gone");
        mapper.update(c);

        Map<String, Object> view = service.toView(mapper.findById(c.getId()));

        assertEquals(Boolean.FALSE, view.get("automated"));
        assertEquals(Boolean.TRUE, view.get("flowMissing"));
    }

    // ==================== 三级层级 ====================

    @Test
    void 层级字段原样存取() {
        AiTestCase c = newCase("验证码登录成功");
        c.setModule("账号");
        c.setSubModule("验证码登录");
        c.setTestPoint("正确验证码可登录");
        c.setExpectedResult("进入首页且昵称正确");

        Map<String, Object> view = service.toView(service.createManual(c));

        assertEquals("账号", view.get("module"));
        assertEquals("验证码登录", view.get("subModule"));
        assertEquals("正确验证码可登录", view.get("testPoint"));
        assertEquals("进入首页且昵称正确", view.get("expectedResult"));
    }

    /** 视图里不该出现「实际结果」：那是每次执行的数据，不属于用例模板 */
    @Test
    void 视图里没有实际结果字段() {
        Map<String, Object> view = service.toView(service.createManual(newCase("A")));

        assertFalse(view.containsKey("actualResult"), "实际结果归执行记录，放进用例会被下一轮覆盖");
        assertTrue(view.containsKey("automated"), "自动化标记是算出来的，要在视图里");
        assertNotNull(view.get("id"));
    }
}
