package com.mobile.standalone.service;

import com.mobile.standalone.entity.UiFlow;
import com.mobile.standalone.mapper.UiFlowMapper;
import com.mobile.standalone.util.RunTrack;
import com.mobile.standalone.util.TestEnvironment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UiFlowService {

    private final UiFlowMapper uiFlowMapper;
    private final com.mobile.standalone.mapper.UiElementMapper uiElementMapper;

    public List<UiFlow> list(String appId, String platform, String catalog, Boolean componentOnly) {
        return list(appId, platform, catalog, componentOnly, null);
    }

    /** environment 传空＝不按环境过滤（列表页「全部环境」） */
    public List<UiFlow> list(String appId, String platform, String catalog,
                             Boolean componentOnly, String environment) {
        return uiFlowMapper.findByFilters(blankToNull(appId), blankToNull(platform),
                blankToNull(catalog), componentOnly, blankToNull(environment));
    }

    /** 回归勾选列表：轨道为回归（ONLINE/SHARED）、启用、非组件，按 sort_order */
    public List<UiFlow> listForRegression(String appId, String platform) {
        return listForRegression(appId, platform, TestEnvironment.PROD);
    }

    /** 按环境取回归勾选列表；environment 传空按生产处理 */
    public List<UiFlow> listForRegression(String appId, String platform, String environment) {
        return listForBatch(appId, platform, environment, RunTrack.REGRESSION);
    }

    /** 某条轨道 + 某个环境的批量执行候选列表；两个参数都传空＝生产的回归，与加这两维之前一致 */
    public List<UiFlow> listForBatch(String appId, String platform, String environment, String track) {
        return uiFlowMapper.findForBatch(blankToNull(appId), blankToNull(platform),
                TestEnvironment.normalize(environment), RunTrack.catalogs(track));
    }

    /**
     * 哪些 catalog 属于回归轨道。
     * <p>
     * 跟环境无关——catalog 只表达「哪个入口用」，环境是独立的 {@code ui_flows.environment} 列。
     * 曾经想用 catalog 一个字段同时表达两件事，两种拆法都出过事：把 SHARED 当「两环境共用」，
     * 线上用例漏进了测试批次；把 AUTOMATION 当「测试环境回归」，自动化轨道被整个占掉、
     * 线上自动化用例没地方放。
     */
    public static List<String> regressionCatalogs() {
        return RunTrack.catalogs(RunTrack.REGRESSION);
    }

    public UiFlow get(String id) {
        return uiFlowMapper.findById(id);
    }

    /** 是否可作为线上回归用例执行 */
    public boolean isRegressionEligible(UiFlow flow) {
        return isRegressionEligible(flow, TestEnvironment.PROD);
    }

    /** 是否可作为该环境的回归用例执行 */
    public boolean isRegressionEligible(UiFlow flow, String environment) {
        return isBatchEligible(flow, environment, RunTrack.REGRESSION);
    }

    /** 能否进这条轨道、这个环境的批量执行：轨道、环境、启用、非组件，四条都要过 */
    public boolean isBatchEligible(UiFlow flow, String environment, String track) {
        if (flow == null) return false;
        if (Boolean.FALSE.equals(flow.getEnabled())) return false;
        if (Boolean.TRUE.equals(flow.getIsComponent())) return false;
        String c = flow.getCatalog() != null ? flow.getCatalog().trim().toUpperCase() : "";
        if (!RunTrack.catalogs(track).contains(c)) return false;
        if (isEnvironmentAgnostic(flow)) return true;
        // 回归流程：迁移前建的行 environment 为空，按 PROD 解读，存量线上回归不受影响
        return TestEnvironment.normalize(flow.getEnvironment())
                .equals(TestEnvironment.normalize(environment));
    }

    /**
     * 自动化流程不绑环境。
     * <p>
     * 一条用例（「协议勾选框可勾选」）在测试包和线上包上是同一件事，
     * 它的自动化实现也该是同一条流程。环境差异只在「装哪个包、用哪套数据」——
     * 那是执行时的参数，不是流程的属性。绑了环境，同一个用例就得写两遍流程，
     * 而且一条用例要挂两条流程，关联关系立刻从 N:1 退化成 N:N。
     * <p>
     * 回归流程<b>照旧绑环境</b>：线上回归和测试回归本来就走不同的流程（装包方式、渠道都不同）。
     */
    public static boolean isEnvironmentAgnostic(UiFlow flow) {
        return flow != null && "AUTOMATION".equals(flow.getCatalog());
    }

    /**
     * 流程是否归属该回归 APP。
     * 有 app_id 则精确匹配；空 app_id 的 SHARED 为通用；空 ONLINE 仅国内（历史兼容）。
     */
    public static boolean matchesRegressionApp(UiFlow flow, String appId) {
        if (flow == null || appId == null || appId.isBlank()) return false;
        String fa = flow.getAppId();
        if (fa != null && !fa.isBlank()) {
            return fa.equals(appId);
        }
        String c = flow.getCatalog() != null ? flow.getCatalog().trim().toUpperCase() : "";
        if ("SHARED".equals(c)) return true;
        if ("ONLINE".equals(c)) return "DOMESTIC_ESPORTS".equals(appId);
        return false;
    }

    @Transactional
    public UiFlow create(UiFlow flow) {
        if (flow.getName() == null || flow.getName().isBlank()) {
            throw new IllegalArgumentException("流程名称不能为空");
        }
        if (flow.getSteps() == null || flow.getSteps().isBlank()) {
            flow.setSteps("[]");
        }
        if (flow.getCatalog() == null || flow.getCatalog().isBlank()) {
            flow.setCatalog("SHARED");
        }
        flow.setEnvironment(TestEnvironment.normalize(flow.getEnvironment()));
        if (flow.getIsComponent() == null) flow.setIsComponent(false);
        if (flow.getEnabled() == null) flow.setEnabled(true);
        if (flow.getSortOrder() == null) flow.setSortOrder(100);
        flow.setId("uif-" + UUID.randomUUID());
        flow.setName(flow.getName().trim());
        validateDependencies(flow);
        LocalDateTime now = LocalDateTime.now();
        flow.setCreatedAt(now);
        flow.setUpdatedAt(now);
        uiFlowMapper.insert(flow);
        return flow;
    }

    @Transactional
    public UiFlow update(String id, UiFlow patch) {
        UiFlow existing = uiFlowMapper.findById(id);
        if (existing == null) return null;
        if (patch.getName() != null) existing.setName(patch.getName().trim());
        if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
        if (patch.getAppId() != null) existing.setAppId(patch.getAppId());
        if (patch.getPlatform() != null) existing.setPlatform(patch.getPlatform());
        if (patch.getCatalog() != null) existing.setCatalog(patch.getCatalog());
        if (patch.getEnvironment() != null) {
            existing.setEnvironment(TestEnvironment.normalize(patch.getEnvironment()));
        }
        if (patch.getIsComponent() != null) existing.setIsComponent(patch.getIsComponent());
        if (patch.getSteps() != null) existing.setSteps(patch.getSteps());
        if (patch.getEnabled() != null) existing.setEnabled(patch.getEnabled());
        if (patch.getSortOrder() != null) existing.setSortOrder(patch.getSortOrder());
        if (patch.getDependsOn() != null) existing.setDependsOn(patch.getDependsOn());
        validateDependencies(existing);
        existing.setUpdatedAt(LocalDateTime.now());
        uiFlowMapper.update(existing);
        return existing;
    }

    /**
     * 保存前校验前置依赖。
     * <p>
     * 环、指向不存在的流程、跨环境依赖，全在这里拦住。留到执行期只会在跑了一半时炸，
     * 而且 {@code CALL_COMPONENT} 的前车之鉴摆着：成环时它报的是「嵌套过深」，
     * 看到那句话的人根本想不到是自己配了个环。
     */
    void validateDependencies(UiFlow flow) {
        List<String> deps = FlowDependencyResolver.dependenciesOf(flow);
        if (deps.isEmpty()) {
            return;
        }
        String env = TestEnvironment.normalize(flow.getEnvironment());
        for (String depId : deps) {
            UiFlow dep = uiFlowMapper.findById(depId);
            if (dep == null) {
                throw new IllegalArgumentException("前置流程不存在: " + depId);
            }
            if (Boolean.TRUE.equals(dep.getIsComponent())) {
                throw new IllegalArgumentException(
                        "「" + dep.getName() + "」是组件，不能当前置流程。组件用「调用组件」步骤内联执行，"
                                + "前置流程是批量执行里单独跑一次的用例，两者不是一回事");
            }
            if (!TestEnvironment.normalize(dep.getEnvironment()).equals(env)) {
                throw new IllegalArgumentException(
                        "前置流程「" + dep.getName() + "」是" + TestEnvironment.label(dep.getEnvironment())
                                + "的用例，本流程是" + TestEnvironment.label(env)
                                + "。跨环境依赖跑起来必然拿错包或错账号");
            }
        }
        // 环检测要连上整张图，不能只看直接依赖：A→B→C→A 三条边单看都合法
        FlowDependencyResolver.resolve(List.of(flow.getId()), id ->
                id.equals(flow.getId()) ? flow : uiFlowMapper.findById(id));
    }

    @Transactional
    public boolean delete(String id) {
        return uiFlowMapper.deleteById(id) > 0;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * 这条流程（含它调用的组件）所有元素定位里用到的 ${变量} 名，按出现顺序去重。
     * <p>
     * 前端据此在执行时列出输入框。取不全也不致命——真缺变量时执行会当场报出缺哪个；
     * 但能提前列出来，人就不用记「这条流程要填什么」。
     */
    public java.util.List<String> collectLocatorVariables(UiFlow flow) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        collectInto(flow, names, new java.util.HashSet<>(), 0);
        return new java.util.ArrayList<>(names);
    }

    private void collectInto(UiFlow flow, java.util.Set<String> names,
                             java.util.Set<String> visited, int depth) {
        // 组件可以互相引用，没有环检测会栈溢出
        if (flow == null || depth > 5 || !visited.add(flow.getId())) {
            return;
        }
        for (String key : elementKeysOf(flow)) {
            com.mobile.standalone.entity.UiElement el = uiElementMapper.findActiveByKey(
                    flow.getAppId(),
                    com.mobile.standalone.util.PlatformNames.normalize(flow.getPlatform()),
                    key);
            if (el == null) {
                continue;
            }
            names.addAll(com.mobile.standalone.util.LocatorTemplate.variablesIn(el.getLocatorValue()));
            names.addAll(com.mobile.standalone.util.LocatorTemplate.variablesIn(el.getLocatorFallbacks()));
        }
        for (String childId : componentIdsOf(flow)) {
            collectInto(get(childId), names, visited, depth + 1);
        }
    }

    /** 步骤 JSON 里所有 elementKey，含嵌套分支 */
    private java.util.List<String> elementKeysOf(UiFlow flow) {
        java.util.List<String> out = new java.util.ArrayList<>();
        scanJson(flow.getSteps(), "elementKey", out);
        scanJson(flow.getSteps(), "element", out);
        return out;
    }

    private java.util.List<String> componentIdsOf(UiFlow flow) {
        java.util.List<String> out = new java.util.ArrayList<>();
        scanJson(flow.getSteps(), "componentId", out);
        scanJson(flow.getSteps(), "flowId", out);
        return out;
    }

    /** 步骤结构嵌套多变（分支、竞速、组件），按字段名整树扫比逐层解析稳 */
    private void scanJson(String json, String field, java.util.List<String> out) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    com.mobile.standalone.util.ExecutionLogJson.mapper().readTree(json);
            java.util.Deque<com.fasterxml.jackson.databind.JsonNode> stack = new java.util.ArrayDeque<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                com.fasterxml.jackson.databind.JsonNode n = stack.pop();
                if (n.isObject()) {
                    com.fasterxml.jackson.databind.JsonNode v = n.get(field);
                    if (v != null && v.isTextual() && !v.asText().isBlank()) {
                        out.add(v.asText().trim());
                    }
                    n.fields().forEachRemaining(e -> stack.push(e.getValue()));
                } else if (n.isArray()) {
                    n.forEach(stack::push);
                }
            }
        } catch (Exception ignored) {
            // 扫不出来就当没有变量，执行时仍会明确报缺哪个
        }
    }
}
