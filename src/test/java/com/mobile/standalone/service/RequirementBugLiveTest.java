package com.mobile.standalone.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bug 记录，对着真库跑。
 * <p>
 * 这块几乎全是 SQL 和一段拼 HTML，把 JdbcTemplate mock 掉就只是在验我写的字符串。
 * 加 {@code -Ddb.live=true} 跑。
 */
@EnabledIfSystemProperty(named = "db.live", matches = "true")
class RequirementBugLiveTest {

    private static final String DB = "ms_bug_live";
    private static JdbcTemplate jdbc;
    private RequirementBugService service;

    @BeforeEach
    void setUp() {
        var admin = new JdbcTemplate(ds(""));
        admin.execute("DROP DATABASE IF EXISTS " + DB);
        admin.execute("CREATE DATABASE " + DB + " DEFAULT CHARACTER SET utf8mb4");
        jdbc = new JdbcTemplate(ds(DB));
        service = new RequirementBugService(jdbc);
        service.ensureSchema();
    }

    @AfterAll
    static void tearDown() {
        new JdbcTemplate(ds("")).execute("DROP DATABASE IF EXISTS " + DB);
    }

    private Map<String, Object> bug(String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requirementNo", "APP-8999");
        m.put("appVersion", "9.3 海外客户端");
        m.put("module", "好友PK");
        m.put("description", desc);
        m.put("developer", "IOS");
        m.put("tester", "yjj");
        return m;
    }

    @Test
    void 序号按需求单从1开始连排() {
        // wiki 表里第一列就是序号，对不上的话搬过去还得重排
        service.create(bug("iOS红色圈中得选手参考原型修改"));
        service.create(bug("二者均有天梯数据，点击PK后提示无天梯数据"));

        var rows = service.listByRequirement("APP-8999");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("seq")).isEqualTo(1);
        assertThat(rows.get(1).get("seq")).isEqualTo(2);
    }

    @Test
    void 没有描述不让存() {
        // 只有截图没有描述，过两周谁也想不起来是什么问题
        assertThatThrownBy(() -> service.create(bug("  ")))
                .hasMessageContaining("问题描述");
    }

    @Test
    void 修了但没验是单独一档() {
        // 开发说改好了、而没人回去点一遍，这是最容易漏的一档
        String id = service.create(bug("点击PK后提示无天梯数据"));
        service.update(id, Map.of("description", "点击PK后提示无天梯数据", "fixed", true));

        var s = service.summaryOf("APP-8999");
        assertThat(s.get("total")).isEqualTo(1);
        assertThat(s.get("fixed")).isEqualTo(1L);
        assertThat(s.get("verified")).isEqualTo(0L);
        assertThat(s.get("fixedNotVerified")).isEqualTo(1L);
    }

    @Test
    void 导出的表头和wiki一模一样() {
        service.create(bug("iOS红色圈中得选手参考原型修改"));

        String html = service.toWikiHtml("APP-8999");

        assertThat(html)
                .contains("<th>序号</th>").contains("<th>所属模块</th>")
                .contains("<th>问题描述</th>").contains("<th>截图</th>")
                .contains("<th>开发</th>").contains("<th>测试</th>")
                .contains("<th>是否修复</th>").contains("<th>是否验证</th>")
                .contains("<th>备注</th>");
        assertThat(html).contains("好友PK").contains("参考原型修改");
    }

    @Test
    void 描述里的尖括号不会破坏表格() {
        // 问题描述里写 <div> 是常事，不转义的话整张表会散掉
        service.create(bug("接口返回 <null>，前端崩了"));

        assertThat(service.toWikiHtml("APP-8999")).contains("&lt;null&gt;");
    }

    @Test
    void 截图只列文件名因为图粘不过去() {
        Map<String, Object> b = bug("原型对照");
        b.put("images", "[\"/bug-images/2026-08-30/aaa.png\",\"/bug-images/2026-08-30/bbb.png\"]");
        service.create(b);

        // wiki 服务器访问不到本机地址，硬贴 <img> 过去全是裂图
        assertThat(service.toWikiHtml("APP-8999")).contains("2 张：aaa.png、bbb.png");
    }

    @Test
    void 删掉之后就不在列表里了() {
        String id = service.create(bug("临时记的，记错了"));

        assertThat(service.delete(id)).isTrue();
        assertThat(service.listByRequirement("APP-8999")).isEmpty();
    }

    private static DriverManagerDataSource ds(String db) {
        var d = new DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:3306/" + db
                        + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
                        + "&characterEncoding=UTF-8");
        d.setUsername("root");
        d.setPassword("1qaz!QAZ9");
        return d;
    }
}
