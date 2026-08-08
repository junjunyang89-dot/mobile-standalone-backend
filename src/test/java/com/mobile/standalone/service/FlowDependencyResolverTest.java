package com.mobile.standalone.service;

import com.mobile.standalone.entity.UiFlow;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 依赖展开与拓扑排序。
 * <p>
 * 这组用例盯的是三件事，按重要性排：<b>被依赖的流程一批里只跑一次</b>（否则登录跑 20 遍，
 * 依赖机制就白做了）、<b>环要当场报出环路</b>（留到执行期只会得到「嵌套过深」，
 * 没人能从那句话想到自己配了环）、<b>没勾的前置要自动补进来</b>。
 */
class FlowDependencyResolverTest {

    private final Map<String, UiFlow> db = new HashMap<>();

    private UiFlow flow(String id, String name, String... deps) {
        UiFlow f = new UiFlow();
        f.setId(id);
        f.setName(name);
        f.setDependsOn(deps.length == 0 ? null
                : "[" + String.join(",", java.util.Arrays.stream(deps).map(d -> "\"" + d + "\"").toList()) + "]");
        db.put(id, f);
        return f;
    }

    private FlowDependencyResolver.Result resolve(String... selected) {
        return FlowDependencyResolver.resolve(List.of(selected), db::get);
    }

    // ==================== 一次批量里只跑一次 ====================

    /** 这条是整个机制存在的理由：20 条用例都依赖登录，登录只该出现一次 */
    @Test
    void 多条用例依赖同一前置时前置只出现一次() {
        flow("login", "登录");
        for (int i = 1; i <= 20; i++) {
            flow("case" + i, "用例" + i, "login");
        }

        List<String> ordered = resolve("case1", "case2", "case3", "case4", "case5",
                "case6", "case7", "case8", "case9", "case10").orderedIds();

        assertEquals(1, ordered.stream().filter("login"::equals).count(),
                "登录跑多次的话，这套依赖就没有意义了：" + ordered);
        assertEquals("login", ordered.get(0));
        assertEquals(11, ordered.size());
    }

    @Test
    void 前置排在依赖它的流程之前() {
        flow("install", "安装启动");
        flow("login", "登录", "install");
        flow("mine", "我的页", "login");

        List<String> ordered = resolve("mine").orderedIds();

        assertEquals(List.of("install", "login", "mine"), ordered);
    }

    /** 菱形依赖：公共祖先只跑一次 */
    @Test
    void 菱形依赖里公共祖先只跑一次() {
        flow("login", "登录");
        flow("a", "A", "login");
        flow("b", "B", "login");
        flow("c", "C", "a", "b");

        List<String> ordered = resolve("c").orderedIds();

        assertEquals(1, ordered.stream().filter("login"::equals).count(), ordered.toString());
        assertTrue(ordered.indexOf("login") < ordered.indexOf("a"), ordered.toString());
        assertTrue(ordered.indexOf("a") < ordered.indexOf("c"), ordered.toString());
        assertTrue(ordered.indexOf("b") < ordered.indexOf("c"), ordered.toString());
    }

    // ==================== 自动补入没勾的前置 ====================

    /** 勾了业务用例忘了勾登录，不该跑到第一条才失败 */
    @Test
    void 没勾的前置自动补进来并列出() {
        flow("login", "登录");
        flow("mine", "我的页", "login");

        FlowDependencyResolver.Result r = resolve("mine");

        assertEquals(List.of("login", "mine"), r.orderedIds());
        assertEquals(List.of("login"), r.autoAdded(), "补了什么必须能告诉用户");
    }

    @Test
    void 已经勾了的前置不算自动补入() {
        flow("login", "登录");
        flow("mine", "我的页", "login");

        assertTrue(resolve("login", "mine").autoAdded().isEmpty());
    }

    // ==================== 无依赖时保留勾选顺序 ====================

    /** 互不依赖的流程仍按人勾的先后跑——顺序有时反映的是「这次想怎么验」 */
    @Test
    void 互不依赖时保留勾选顺序() {
        flow("a", "A");
        flow("b", "B");
        flow("c", "C");

        assertEquals(List.of("c", "a", "b"), resolve("c", "a", "b").orderedIds());
    }

    @Test
    void 空输入返回空() {
        assertTrue(FlowDependencyResolver.resolve(List.of(), db::get).orderedIds().isEmpty());
        assertTrue(FlowDependencyResolver.resolve(null, db::get).orderedIds().isEmpty());
    }

    @Test
    void 重复勾选去重() {
        flow("a", "A");

        assertEquals(List.of("a"), resolve("a", "a", "a").orderedIds());
    }

    // ==================== 环：必须当场报出环路 ====================

    /**
     * CALL_COMPONENT 现在只有深度上限，成环时报的是「嵌套过深」——
     * 看到那句话的人根本想不到是自己配了个环。这里必须把环路打印出来。
     */
    @Test
    void 成环时报出完整环路而不是笼统报错() {
        flow("a", "登录", "b");
        flow("b", "首页", "a");

        FlowDependencyResolver.CycleException e = assertThrows(
                FlowDependencyResolver.CycleException.class, () -> resolve("a"));

        assertTrue(e.getMessage().contains("登录"), e.getMessage());
        assertTrue(e.getMessage().contains("首页"), e.getMessage());
        assertTrue(e.getMessage().contains("→"), e.getMessage());
        assertTrue(e.getCycle().contains("a") && e.getCycle().contains("b"), e.getCycle().toString());
    }

    @Test
    void 三节点长环也能检出() {
        flow("a", "A", "b");
        flow("b", "B", "c");
        flow("c", "C", "a");

        assertThrows(FlowDependencyResolver.CycleException.class, () -> resolve("a"));
    }

    /** 自己依赖自己在解析层就被丢掉，不该变成一条报错 */
    @Test
    void 自引用被忽略() {
        flow("a", "A", "a");

        assertEquals(List.of("a"), resolve("a").orderedIds());
    }

    // ==================== 坏数据 ====================

    @Test
    void 依赖的流程不存在时报出是谁依赖的() {
        flow("mine", "我的页", "ghost");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> resolve("mine"));

        assertTrue(e.getMessage().contains("ghost"), e.getMessage());
        assertTrue(e.getMessage().contains("我的页"), "要说清是谁引用了它：" + e.getMessage());
    }

    /** 手改过库或历史脏数据，不该让整批执行卡在解析上 */
    @Test
    void dependsOn不是合法JSON时当作无依赖() {
        UiFlow f = flow("a", "A");
        f.setDependsOn("这不是JSON");

        assertEquals(List.of("a"), resolve("a").orderedIds());
    }

    @Test
    void dependsOn为空数组或空串当作无依赖() {
        UiFlow a = flow("a", "A");
        a.setDependsOn("[]");
        UiFlow b = flow("b", "B");
        b.setDependsOn("   ");

        assertEquals(List.of("a", "b"), resolve("a", "b").orderedIds());
    }

    @Test
    void 依赖链过深时拦住() {
        flow("f0", "F0");
        for (int i = 1; i <= FlowDependencyResolver.MAX_DEPTH + 3; i++) {
            flow("f" + i, "F" + i, "f" + (i - 1));
        }

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> resolve("f" + (FlowDependencyResolver.MAX_DEPTH + 3)));
        assertTrue(e.getMessage().contains("过深"), e.getMessage());
    }

    // ==================== 失败传播：谁该被跳过 ====================

    /**
     * 登录挂了，只跳过依赖登录的；不相干的用例继续跑。
     * 一次前置失败把整批判死，报告里全是查不出所以然的红。
     */
    @Test
    void 只有传递依赖失败流程的才被跳过() {
        flow("login", "登录");
        flow("mine", "我的页", "login");
        flow("settings", "设置", "mine");
        flow("splash", "启动页");

        List<String> scope = List.of("login", "mine", "settings", "splash");

        assertEquals(List.of("mine", "settings"),
                List.copyOf(FlowDependencyResolver.dependentsOf("login", scope, db::get)));
    }

    @Test
    void 没人依赖时返回空() {
        flow("login", "登录");
        flow("splash", "启动页");

        assertTrue(FlowDependencyResolver.dependentsOf("login", List.of("login", "splash"), db::get).isEmpty());
    }
}
