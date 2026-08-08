package com.mobile.standalone.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 直接渲染 findForRegression 的动态 SQL。
 * <p>
 * 回归用例的候选集合是发版当天要依赖的东西，而它整个压在一段带 choose/foreach 的动态 SQL 上——
 * 这种地方写错不会编译失败、不会有单测报红，只会在真跑的时候少几条或多几条用例。
 * 所以这里把 SQL 渲染出来逐条断言，特别是「生产的候选集合跟加环境之前完全一致」。
 */
class UiFlowRegressionSqlTest {

    private static final String STATEMENT =
            "com.mobile.standalone.mapper.UiFlowMapper.findForBatch";

    private static Configuration configuration;

    @BeforeAll
    static void parseMapper() throws Exception {
        configuration = new Configuration();
        try (InputStream in = Resources.getResourceAsStream("mapper/UiFlowMapper.xml")) {
            new XMLMapperBuilder(in, configuration, "mapper/UiFlowMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
    }

    /** 返回 (归一化后的 SQL, 参数值列表) */
    private static Rendered render(String appId, String platform, String environment) {
        return render(appId, platform, environment, null);
    }

    private static Rendered render(String appId, String platform, String environment,
                                   List<String> catalogs) {
        Map<String, Object> params = new HashMap<>();
        params.put("appId", appId);
        params.put("platform", platform);
        params.put("environment", environment);
        params.put("catalogs", catalogs);

        MappedStatement ms = configuration.getMappedStatement(STATEMENT);
        BoundSql bs = ms.getBoundSql(params);
        String sql = bs.getSql().replaceAll("\\s+", " ").trim();
        List<Object> values = bs.getParameterMappings().stream()
                .map(m -> bs.getAdditionalParameter(m.getProperty()) != null
                        ? bs.getAdditionalParameter(m.getProperty())
                        : params.get(m.getProperty()))
                .toList();
        return new Rendered(sql, values);
    }

    private record Rendered(String sql, List<Object> values) {
    }

    // ==================== 生产：不能有任何变化 ====================

    /**
     * 这一条是本组的核心。环境传空走 otherwise 分支落到写死的 'PROD'，
     * 轨道恒为 ('ONLINE', 'SHARED')——存量调用与加环境之前一致。
     */
    @Test
    void 环境传空时退回生产() {
        Rendered r = render("DOMESTIC_ESPORTS", "ANDROID", null);

        assertTrue(r.sql().contains("catalog IN ('ONLINE', 'SHARED')"), r.sql());
        assertTrue(r.sql().contains("COALESCE(environment, 'PROD') = 'PROD'"), r.sql());
        // 环境是字面量，占位符只剩 appId / platform
        assertEquals(List.of("DOMESTIC_ESPORTS", "ANDROID"), r.values());
    }

    /**
     * 迁移前建的行 environment 为 NULL，必须仍被生产批次选中。
     * 少了 COALESCE，那批用例会在发版当天凭空消失。
     */
    @Test
    void 用COALESCE兜住迁移前的空环境() {
        assertTrue(render("DOMESTIC_ESPORTS", null, "PROD").sql()
                .contains("COALESCE(environment, 'PROD')"), "生产必须兜 NULL");
        assertTrue(render("DOMESTIC_ESPORTS", null, "TEST").sql()
                .contains("COALESCE(environment, 'PROD')"), "测试也走同一套判断");
    }

    /** 轨道恒定：回归候选永远只看 ONLINE/SHARED，不受环境影响 */
    @Test
    void 轨道不随环境变化() {
        for (String env : List.of("PROD", "TEST")) {
            String sql = render("DOMESTIC_ESPORTS", null, env).sql();
            assertTrue(sql.contains("catalog IN ('ONLINE', 'SHARED')"), env + ": " + sql);
            assertFalse(sql.contains("'AUTOMATION'"), env + ": 仅自动化编排不该进回归批次");
            assertFalse(sql.contains("'PERFORMANCE'"), env + ": " + sql);
        }
    }

    /** 启用、非组件这两条硬约束在任何环境下都在 */
    @Test
    void 始终排除停用与组件() {
        for (String env : List.of("PROD", "TEST")) {
            String sql = render("DOMESTIC_ESPORTS", null, env).sql();
            assertTrue(sql.contains("enabled = 1"), sql);
            assertTrue(sql.contains("is_component = 0 OR is_component IS NULL"), sql);
        }
    }

    // ==================== 轨道 ====================

    /** 自动化轨道换一批 catalog，环境那一维不受影响 */
    @Test
    void 自动化轨道按自己的catalog过滤() {
        Rendered r = render("DOMESTIC_ESPORTS", null, "TEST", List.of("AUTOMATION", "SHARED"));

        assertTrue(r.values().contains("AUTOMATION"), r.values().toString());
        assertTrue(r.values().contains("SHARED"), r.values().toString());
        assertFalse(r.values().contains("ONLINE"), "仅回归的用例不该进自动化批次");
        assertTrue(r.values().contains("TEST"), r.values().toString());
    }

    // ==================== 环境作为参数下发 ====================

    @Test
    void 指定环境时作为参数绑定() {
        Rendered test = render("DOMESTIC_ESPORTS", "ANDROID", "TEST");

        assertTrue(test.values().contains("TEST"), test.values().toString());
        assertFalse(test.sql().contains("= 'PROD'"), "指定了环境就不该再落到写死的 PROD：" + test.sql());
    }

    // ==================== 空 app_id 的历史兼容 ====================

    /**
     * 空 app_id 的流程只有国内电竞看得到（避免海外误展示完美电竞用例）。
     * 这两句要与 UiFlowService.matchesRegressionApp 保持一致，那边就是这两条规则。
     */
    @Test
    void 空appId的兼容分支只认SHARED与ONLINE() {
        for (String env : List.of("PROD", "TEST")) {
            String sql = render("DOMESTIC_ESPORTS", null, env).sql();
            assertTrue(sql.contains("catalog = 'SHARED'"), sql);
            assertTrue(sql.contains("catalog = 'ONLINE'"), sql);
        }
    }

    /** 海外只认精确 app_id 与通用 SHARED，不吃国内那条兼容 */
    @Test
    void 海外应用不吃国内的兼容分支() {
        Rendered pog = render("OVERSEAS_POGPOG", null, "PROD");

        assertEquals(1, countOf(pog.sql(), "app_id IS NULL OR app_id = ''"), pog.sql());
        assertTrue(pog.sql().contains("catalog = 'SHARED'"), pog.sql());
    }

    @Test
    void 不传appId时不加应用过滤() {
        String sql = render(null, null, "PROD").sql();

        assertFalse(sql.contains("app_id"), sql);
        assertFalse(sql.contains("platform ="), sql);
    }

    private static int countOf(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
