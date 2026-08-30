package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 拿文档约束设计边界用例。
 * <p>
 * 这些用例的价值不在于自动断言——期望结果各接口不一样，猜一个填进去第一次跑就是红的。
 * 价值在于<b>把十几个请求敲出来</b>这件苦力活：手工测很少有人真的
 * 去删一个必填参数试一次、去传个非法枚举试一次。
 */
class DocCaseDesignerTest {

    private Map<String, Object> param(String name, boolean required, String def, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("required", required);
        m.put("default", def);
        m.put("note", note);
        return m;
    }

    /** 文档里 friend/list 的真实入参 */
    private List<Map<String, Object>> friendListParams() {
        return List.of(
                param("steamId", true, null, "好友主体 SteamId；32/64 在 DC 归一到 64"),
                param("mode", false, "1", "场次/胜率统计模式：1=标准，4=天梯；其它回落 1"),
                param("scope", false, "1", "统计范围：1=全部；其它回落 1"),
                param("global", false, "true", "海外 true；国内必须传 false"));
    }

    private Map<String, String> base() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("steamId", "103561140");
        m.put("mode", "4");
        m.put("scope", "1");
        return m;
    }

    @Test
    void 必填参数生成不传的用例() {
        var vs = DocCaseDesigner.design(friendListParams(), base());

        var missing = vs.stream().filter(v -> "MISSING_REQUIRED".equals(v.kind())).findFirst().orElseThrow();
        assertThat(missing.name()).contains("steamId");
        // 不传＝这个 key 不在，而不是传空串。传空串是「传了空值」，两回事
        assertThat(missing.query()).doesNotContainKey("steamId");
        // 其余参数保持真实值，否则请求本身就是错的，跑出来的失败说明不了问题
        assertThat(missing.query()).containsEntry("mode", "4");
    }

    @Test
    void 有默认值的可选参数生成不传的用例() {
        var vs = DocCaseDesigner.design(friendListParams(), base());

        var def = vs.stream()
                .filter(v -> "DEFAULT".equals(v.kind()) && v.name().contains("mode"))
                .findFirst().orElseThrow();
        assertThat(def.query()).doesNotContainKey("mode");
        assertThat(def.name()).contains("默认值 1");
    }

    @Test
    void 写了回落规则的生成非法值用例() {
        var vs = DocCaseDesigner.design(friendListParams(), base());

        var fb = vs.stream()
                .filter(v -> "FALLBACK".equals(v.kind()) && v.name().contains("mode"))
                .findFirst().orElseThrow();
        assertThat(fb.query()).containsEntry("mode", "99");
        // 文档原话直接当预期贴给人看，归纳过一定丢信息
        assertThat(fb.expectNote()).contains("回落");
    }

    @Test
    void 全部默认停用因为断言要人补() {
        // 期望结果各接口不一样（有的 PARAM_ERROR、有的回空数组），
        // 平台猜一个填进去，第一次跑就是红的——红得没有道理最消耗信任
        var vs = DocCaseDesigner.design(friendListParams(), base());

        assertThat(vs).isNotEmpty();
        assertThat(vs).allSatisfy(v -> assertThat(v.enabled()).isFalse());
        assertThat(vs).allSatisfy(v -> assertThat(v.expectNote()).isNotBlank());
    }

    @Test
    void 边界用例只在文档提到边界时才生成() {
        var withBoundary = DocCaseDesigner.design(
                List.of(param("steamId", true, null, "严格 32 位；<=0 或 > THRESHOLD → PARAM_ERROR")),
                Map.of("steamId", "103561140"));
        assertThat(withBoundary).anySatisfy(v -> assertThat(v.kind()).isEqualTo("BOUNDARY"));

        // 文档没说边界就不造：造出来的边界值期望是什么，谁也不知道
        var noBoundary = DocCaseDesigner.design(
                List.of(param("steamId", true, null, "玩家 SteamId")),
                Map.of("steamId", "103561140"));
        assertThat(noBoundary).noneSatisfy(v -> assertThat(v.kind()).isEqualTo("BOUNDARY"));
    }

    @Test
    void 没有文档参数时一条都不造() {
        assertThat(DocCaseDesigner.design(null, base())).isEmpty();
        assertThat(DocCaseDesigner.design(List.of(), base())).isEmpty();
    }

    @Test
    void 条数有上限() {
        // 十几条边界用例堆上来没人会逐条补断言，反而全被忽略
        List<Map<String, Object>> many = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(param("p" + i, false, "1", "其它回落 1"));
        }

        assertThat(DocCaseDesigner.design(many, Map.of())).hasSizeLessThanOrEqualTo(8);
    }
}
