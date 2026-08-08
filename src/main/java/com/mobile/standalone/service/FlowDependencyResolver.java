package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.entity.UiFlow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 把「勾选的一批流程」展开成「按依赖排好的执行顺序」。
 * <p>
 * 要解决的是：20 条用例都要先登录。以前只有两条路——每条都 {@code CALL_COMPONENT} 登录
 * （登录跑 20 次），或者人工排顺序（靠人不出错，换个人就乱）。声明依赖之后由这里排，
 * 被依赖的流程一批里<b>只出现一次</b>。
 * <p>
 * 两条刻意的设计：
 * <ul>
 *   <li><b>自动补入没勾的前置</b>。勾了 20 条业务用例、忘了勾登录，不该在跑到第一条时才失败。</li>
 *   <li><b>环在这里当场报出环路</b>。留到执行期只会得到一句「嵌套过深」，看到的人根本
 *       想不到是自己配了环——{@code CALL_COMPONENT} 现在就是这个毛病。</li>
 * </ul>
 */
public final class FlowDependencyResolver {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 依赖链再深就不是「前置」而是设计问题了，拦住免得排出一串没人看得懂的顺序 */
    static final int MAX_DEPTH = 20;

    private FlowDependencyResolver() {
    }

    /**
     * @param orderedIds 展开并排序后的执行顺序：前置一定排在依赖它的流程之前
     * @param autoAdded  用户没勾、因为被依赖而补进来的流程 ID（要在界面上告诉用户）
     */
    public record Result(List<String> orderedIds, List<String> autoAdded) {
    }

    /** 依赖成环。message 直接给人看，带完整环路 */
    public static class CycleException extends IllegalArgumentException {
        private final List<String> cycle;

        CycleException(List<String> cycle, String message) {
            super(message);
            this.cycle = List.copyOf(cycle);
        }

        public List<String> getCycle() {
            return cycle;
        }
    }

    /**
     * @param selectedIds 用户勾选的流程，保留勾选顺序——互不依赖的流程仍按人选的先后跑
     * @param loader      按 id 取流程；返回 null 视为不存在
     */
    public static Result resolve(List<String> selectedIds, Function<String, UiFlow> loader) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String id : selectedIds == null ? List.<String>of() : selectedIds) {
            if (id != null && !id.isBlank()) {
                selected.add(id.trim());
            }
        }

        List<String> ordered = new ArrayList<>();
        // 0=没访问过 1=正在访问（在当前 DFS 路径上）2=已完成
        Map<String, Integer> state = new HashMap<>();
        List<String> path = new ArrayList<>();
        Map<String, UiFlow> cache = new HashMap<>();

        for (String id : selected) {
            visit(id, loader, cache, state, path, ordered, 0);
        }

        List<String> autoAdded = new ArrayList<>();
        for (String id : ordered) {
            if (!selected.contains(id)) {
                autoAdded.add(id);
            }
        }
        return new Result(List.copyOf(ordered), List.copyOf(autoAdded));
    }

    private static void visit(String id, Function<String, UiFlow> loader, Map<String, UiFlow> cache,
                              Map<String, Integer> state, List<String> path, List<String> ordered,
                              int depth) {
        int color = state.getOrDefault(id, 0);
        if (color == 2) {
            return;
        }
        if (color == 1) {
            throw cycleAt(id, path, loader, cache);
        }
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "前置依赖层级过深（>" + MAX_DEPTH + "）：" + nameOf(id, loader, cache)
                            + "。请把公共前置拆成一条独立流程，别让依赖串成长链");
        }

        UiFlow flow = load(id, loader, cache);
        if (flow == null) {
            String from = path.isEmpty() ? "" : "（被「" + nameOf(path.get(path.size() - 1), loader, cache) + "」依赖）";
            throw new IllegalArgumentException("前置流程不存在: " + id + from);
        }

        state.put(id, 1);
        path.add(id);
        for (String dep : dependenciesOf(flow)) {
            visit(dep, loader, cache, state, path, ordered, depth + 1);
        }
        path.remove(path.size() - 1);
        state.put(id, 2);
        // 后序入列：依赖先进，天然满足「前置排在前面」
        ordered.add(id);
    }

    private static CycleException cycleAt(String id, List<String> path,
                                          Function<String, UiFlow> loader, Map<String, UiFlow> cache) {
        int start = path.indexOf(id);
        List<String> cycle = new ArrayList<>(path.subList(start < 0 ? 0 : start, path.size()));
        cycle.add(id);
        List<String> names = cycle.stream().map(x -> nameOf(x, loader, cache)).toList();
        return new CycleException(cycle, "前置依赖成环：" + String.join(" → ", names)
                + "。环里的流程谁都排不到前面，请断开其中一条依赖");
    }

    /** 解析 depends_on JSON；脏数据不该让整批跑不起来，解析不了就当无依赖 */
    public static List<String> dependenciesOf(UiFlow flow) {
        String raw = flow == null ? null : flow.getDependsOn();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        try {
            JsonNode node = JSON.readTree(raw);
            if (node.isArray()) {
                for (JsonNode n : node) {
                    String v = n.asText(null);
                    if (v != null && !v.isBlank() && !v.trim().equals(flow.getId())) {
                        out.add(v.trim());
                    }
                }
            }
        } catch (Exception ignored) {
            // 手改过库或历史脏数据：当作没有依赖，别让整批执行卡死在解析上
        }
        return List.copyOf(out);
    }

    /**
     * 谁（传递地）依赖 {@code target}。
     * <p>
     * 用于失败传播：登录挂了，只把依赖登录的标成跳过，不相干的用例继续跑——
     * 一次前置失败就把整批判死，会让报告里全是查不出所以然的红。
     */
    public static LinkedHashSet<String> dependentsOf(String target, List<String> scope,
                                                     Function<String, UiFlow> loader) {
        Map<String, UiFlow> cache = new HashMap<>();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String id : scope) {
            if (id != null && !id.equals(target) && dependsOnTransitively(id, target, loader, cache, 0)) {
                out.add(id);
            }
        }
        return out;
    }

    private static boolean dependsOnTransitively(String id, String target, Function<String, UiFlow> loader,
                                                 Map<String, UiFlow> cache, int depth) {
        if (depth > MAX_DEPTH) {
            return false;
        }
        UiFlow flow = load(id, loader, cache);
        if (flow == null) {
            return false;
        }
        for (String dep : dependenciesOf(flow)) {
            if (dep.equals(target) || dependsOnTransitively(dep, target, loader, cache, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private static UiFlow load(String id, Function<String, UiFlow> loader, Map<String, UiFlow> cache) {
        if (cache.containsKey(id)) {
            return cache.get(id);
        }
        UiFlow flow = loader.apply(id);
        cache.put(id, flow);
        return flow;
    }

    private static String nameOf(String id, Function<String, UiFlow> loader, Map<String, UiFlow> cache) {
        UiFlow f = load(id, loader, cache);
        return f != null && f.getName() != null && !f.getName().isBlank() ? f.getName() : id;
    }
}
