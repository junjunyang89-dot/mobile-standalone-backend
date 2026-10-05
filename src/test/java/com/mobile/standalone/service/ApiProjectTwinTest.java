package com.mobile.standalone.service;

import com.mobile.standalone.entity.ApiProject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 组内项目不重名：APP 组里 data-center 就是 app-data-center，跨组才允许同名 */
class ApiProjectTwinTest {

    private static ApiProject p(String code, String name, String category) {
        ApiProject x = new ApiProject();
        x.setCode(code);
        x.setName(name);
        x.setCategory(category);
        return x;
    }

    private final List<ApiProject> existing = List.of(
            p("app-engine", "app-engine", "APP"),
            p("app-data-center", "数据中心", "APP"),
            p("match", "比赛", "PVP组"));

    @Test
    void APP组里去掉app前缀或换大小写还是同一个项目() {
        assertEquals("app-engine", ApiProjectService.twinInSameGroup(existing, p("engine", "引擎", "APP")).getCode());
        assertEquals("app-engine", ApiProjectService.twinInSameGroup(existing, p("APP-ENGINE", null, "APP")).getCode());
        assertEquals("app-data-center",
                ApiProjectService.twinInSameGroup(existing, p("data-center", "数据中心2", "APP")).getCode());
    }

    @Test
    void 名称撞上也算() {
        assertEquals("app-data-center",
                ApiProjectService.twinInSameGroup(existing, p("app-dc", "数据中心", "APP")).getCode());
    }

    @Test
    void 不同组可以同名() {
        assertNull(ApiProjectService.twinInSameGroup(existing, p("app-engine", "app-engine", "WEB组")));
        assertNull(ApiProjectService.twinInSameGroup(existing, p("app-match", "比赛", "APP")));
    }

    @Test
    void 真新项目放行() {
        assertNull(ApiProjectService.twinInSameGroup(existing, p("app-push", "推送", "APP")));
    }
}
