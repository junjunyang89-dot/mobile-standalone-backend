package com.mobile.standalone.service.apitest;

import com.mobile.standalone.auth.PermissionRules;
import com.mobile.standalone.auth.PlatformRole;
import com.mobile.standalone.entity.ApiDbConnection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DbConnectionPickTest {

    private static ApiDbConnection conn(String name, String... projects) {
        ApiDbConnection c = new ApiDbConnection();
        c.setName(name);
        c.setProjects(List.of(projects));
        return c;
    }

    private final List<ApiDbConnection> app = List.of(
            conn("data_center", "app-data-center"),
            conn("activity", "app-activity"),
            conn("admin", "app-data-center", "app-activity"));

    @Test
    void 项目只绑了一个库就直接用() {
        var p = DbConnectionService.pick(List.of(conn("dc", "app-data-center"), conn("shared")),
                "TEST", "app-data-center", null);

        assertThat(p.conn().getName()).isEqualTo("dc");
    }

    @Test
    void 项目绑了自己的库和共用库时要求断言写明() {
        var p = DbConnectionService.pick(app, "TEST", "app-data-center", null);

        assertThat(p.conn()).isNull();
        assertThat(p.error()).contains("2 个").contains("data_center").contains("admin");
    }

    @Test
    void 断言写了库名按名字找_大小写不敏感() {
        var p = DbConnectionService.pick(app, "TEST", "app-data-center", "ADMIN");

        assertThat(p.conn().getName()).isEqualTo("admin");
    }

    @Test
    void 写的库名不存在直接报_不退回去猜() {
        var p = DbConnectionService.pick(app, "TEST", "app-data-center", "nope");

        assertThat(p.conn()).isNull();
        assertThat(p.error()).contains("nope");
    }

    @Test
    void 项目没绑库时用通用连接() {
        var p = DbConnectionService.pick(List.of(conn("dc", "app-data-center"), conn("legacy")),
                "TEST", "app-user", null);

        assertThat(p.conn().getName()).isEqualTo("legacy");
    }

    @Test
    void 项目没绑库也没有通用连接时说清楚去哪配() {
        var p = DbConnectionService.pick(app, "PROD", "app-user", null);

        assertThat(p.conn()).isNull();
        assertThat(p.error()).contains("app-user").contains("数据库连接");
    }

    @Test
    void 项目编码大小写不敏感() {
        var p = DbConnectionService.pick(List.of(conn("act", "APP-ACTIVITY")), "TEST", "app-activity", null);

        assertThat(p.conn().getName()).isEqualTo("act");
    }

    @Test
    void 归属项目去空去重() {
        assertThat(DbConnectionService.normalizeProjects(List.of(" a ", "", "a", "b")))
                .containsExactly("a", "b");
    }

    @Test
    void 给项目勾选库时只动这一条归属() {
        assertThat(DbConnectionService.toggleProject(List.of("app-a"), "app-b", true))
                .containsExactly("app-a", "app-b");
        assertThat(DbConnectionService.toggleProject(List.of("app-a", "app-b"), "APP-A", false))
                .containsExactly("app-b");
        assertThat(DbConnectionService.toggleProject(List.of("app-a"), "app-a", true))
                .containsExactly("app-a");
    }

    @Test
    void 读表结构的候选库_关联了用关联的_没关联用通用的() {
        var both = List.of(conn("dc", "app-data-center"), conn("admin", "app-data-center", "app-activity"), conn("shared"));
        assertThat(DbConnectionService.candidates(both, "app-data-center"))
                .extracting(ApiDbConnection::getName).containsExactly("dc", "admin");
        assertThat(DbConnectionService.candidates(both, "app-user"))
                .extracting(ApiDbConnection::getName).containsExactly("shared");
        assertThat(DbConnectionService.candidates(List.of(conn("dc", "app-data-center")), "app-user")).isEmpty();
    }

    @Test
    void 改生产库连接要管理员() {
        assertThat(PermissionRules.required("POST", "/api-test/envs/PROD/db-connections"))
                .isEqualTo(PlatformRole.ADMIN);
        assertThat(PermissionRules.required("PUT", "/api-test/envs/PROD/projects/app-activity/db-connections"))
                .isEqualTo(PlatformRole.ADMIN);
        assertThat(PermissionRules.required("POST", "/api-test/envs/TEST/db-connections"))
                .isEqualTo(PlatformRole.TESTER);
        assertThat(PermissionRules.required("PUT", "/api-test/envs/TEST/projects/app-activity/db-connections"))
                .isEqualTo(PlatformRole.TESTER);
        assertThat(PermissionRules.required("POST", "/api-test/envs/PROD/db-connections/probe"))
                .isEqualTo(PlatformRole.ADMIN);
        assertThat(PermissionRules.required("POST", "/api-test/envs/TEST/db-connections/probe"))
                .isEqualTo(PlatformRole.TESTER);
    }
}
