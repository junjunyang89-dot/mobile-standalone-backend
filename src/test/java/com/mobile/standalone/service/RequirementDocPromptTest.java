package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 两种素材要的提示词是<b>相反</b>的，这是整件事的核心。
 * <p>
 * 页面截图上有真实控件，所以要约束「不要臆造截图里没有的入口」；
 * 需求文档上一个控件都没有，同一句约束会让模型去找「文档这一页上的按钮」——
 * 要么跑偏，要么因为找不到控件只吐三五条。
 */
class RequirementDocPromptTest {

    private QwenTestCaseGenerator gen() {
        return new QwenTestCaseGenerator(mock(com.mobile.standalone.service.ai.QwenChatClient.class));
    }

    @Test
    void 页面截图保留不要臆造控件的约束() {
        String p = gen().prompt("登录页", null, true, MaterialKind.PAGE_SHOT);

        assertThat(p).contains("APP 页面").contains("不要臆造截图里没有的入口");
        // 页面截图不需要挑文档矛盾——图上就没有文档
        assertThat(p).doesNotContain("open_questions");
    }

    /**
     * 需求文档必须去掉那句控件约束。
     * 留着的话模型会去找「文档这一页上的按钮」，而文档上一个控件都没有。
     */
    @Test
    void 需求文档去掉控件约束换成需求约束() {
        String p = gen().prompt("灵动岛", null, true, MaterialKind.REQUIREMENT_DOC);

        assertThat(p).doesNotContain("不要臆造截图里没有的入口");
        assertThat(p).contains("不要臆造需求里没有写的规则");
        assertThat(p).contains("不是界面截图");
    }

    /** 挑矛盾是需求文档这一档存在的主要理由 */
    @Test
    void 需求文档要求挑出矛盾并单独输出() {
        String p = gen().prompt("灵动岛", null, true, MaterialKind.REQUIREMENT_DOC);

        assertThat(p).contains("open_questions");
        assertThat(p).contains("互相打架");
        // 明确禁止「照着矛盾的两条各写一遍」——那正是默认会发生的事
        assertThat(p).contains("不要】照着各写一遍");
        assertThat(p).contains("边界没定义");
    }

    /**
     * 禁止模型自己编预期。
     * 编出来的预期是错的，而错的预期比没有用例更糟：
     * 人会照着它测，然后把「符合错误预期」判成通过。
     */
    @Test
    void 需求文档禁止替产品编预期() {
        String p = gen().prompt("灵动岛", null, true, MaterialKind.REQUIREMENT_DOC);

        assertThat(p).contains("不要自己替产品编一个预期");
    }

    /** 平台硬约束是需求文档里永远不会写、但一定会踩的 */
    @Test
    void 需求文档提示补平台约束() {
        String p = gen().prompt("灵动岛", null, true, MaterialKind.REQUIREMENT_DOC);

        assertThat(p).contains("实时活动").contains("权限").contains("弱网");
    }

    /** 两种都要保留原有的通用要求，别为了加新东西把老的挤掉 */
    @Test
    void 通用要求两种素材都保留() {
        for (MaterialKind k : MaterialKind.values()) {
            String p = gen().prompt("X", null, true, k);
            assertThat(p).as(k.name()).contains("异常流至少占三分之一");
            assertThat(p).as(k.name()).contains("每条用例只验一件事");
            assertThat(p).as(k.name()).contains("test_cases");
        }
    }

    /** 不传素材类型时保持原有行为——老调用方不该因为加了新功能而改变 */
    @Test
    void 默认仍按页面截图() {
        assertThat(MaterialKind.from(null)).isEqualTo(MaterialKind.PAGE_SHOT);
        assertThat(MaterialKind.from("")).isEqualTo(MaterialKind.PAGE_SHOT);
        assertThat(MaterialKind.from("乱填")).isEqualTo(MaterialKind.PAGE_SHOT);
        assertThat(MaterialKind.from(" requirement_doc ")).isEqualTo(MaterialKind.REQUIREMENT_DOC);

        assertThat(gen().prompt("登录页", null, true))
                .isEqualTo(gen().prompt("登录页", null, true, MaterialKind.PAGE_SHOT));
    }

    /** 待确认项要和用例分开解析：混进用例会被当成用例去执行，然后判成失败 */
    @Test
    void 待确认项从模型输出里解析出来() {
        String raw = """
                {"test_cases":[{"case_id":"TC-001","title":"a","steps":[]}],
                 "open_questions":[
                   {"title":"轮播和只留一场冲突","kind":"冲突","question":"哪个是准的"},
                   {"kind":"没有标题的会被丢掉"}
                 ]}
                """;
        var qs = gen().parseOpenQuestions(raw);

        assertThat(qs).hasSize(1);
        assertThat(qs.get(0).get("title").asText()).contains("冲突");
    }

    /** 模型没吐待确认项时返回空，不能抛——用例才是主产物 */
    @Test
    void 没有待确认项时返回空而不是抛异常() {
        assertThat(gen().parseOpenQuestions("""
                {"test_cases":[{"case_id":"TC-001","title":"a"}]}""")).isEmpty();
        assertThat(gen().parseOpenQuestions("完全不是 JSON")).isEmpty();
        assertThat(gen().parseOpenQuestions("")).isEmpty();
    }

    /** 模型爱包一层 ```json，待确认项也要能从里面取出来 */
    @Test
    void 代码块包裹时也能解析出待确认项() {
        String raw = "```json\n{\"open_questions\":[{\"title\":\"超时多久没定义\"}]}\n```";

        assertThat(gen().parseOpenQuestions(raw)).hasSize(1);
    }
}
