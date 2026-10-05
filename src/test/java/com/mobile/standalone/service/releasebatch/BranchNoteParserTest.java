package com.mobile.standalone.service.releasebatch;

import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.service.releasebatch.BranchNoteParser.BranchRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BranchNoteParserTest {

    private static String pairs(String text) {
        return BranchNoteParser.parse(text).stream()
                .map(r -> r.project() + "=" + r.branch())
                .collect(Collectors.joining("; "));
    }

    @Test
    void 项目冒号分支() {
        assertEquals("app-swift-v3=cj/app-9078", pairs("app-swift-v3 : cj/app-9078"));
        assertEquals("steam-assistant=cj/app-9083", pairs("steam-assistant:  cj/app-9083"));
        assertEquals("app-admin=agent/2026-09-24/rating_switch", pairs("app-admin：agent/2026-09-24/rating_switch"));
    }

    @Test
    void 多个项目共用分支_后面的中文是备注() {
        List<BranchRef> r = BranchNoteParser.parse("comment-service、app-admin：agent/2026-09-17/comment-score 按顺序发版");
        assertEquals(2, r.size());
        assertEquals("comment-service", r.get(0).project());
        assertEquals("app-admin", r.get(1).project());
        assertEquals("agent/2026-09-17/comment-score", r.get(0).branch());
        assertEquals("按顺序发版", r.get(0).note());
        assertEquals("app-engine=dyl/20260903/yd; app-activity=dyl/20260903/yd",
                pairs("app-engine、app-activity：dyl/20260903/yd"));
    }

    @Test
    void 带编号_分支在下一行() {
        String text = """
                1.app-admin, app-activity, app-video:
                agent/2026-09-11/split-commentary-from-bgm

                2. app-admin, app-activity, app-video:
                agent/2026-09-17/personal-content-config

                3.app-admin:

                agent/2026-09-18/personal-content-frontend
                """;
        List<BranchRef> r = BranchNoteParser.parse(text);
        assertEquals(7, r.size());
        assertEquals("app-admin=agent/2026-09-18/personal-content-frontend",
                r.get(6).project() + "=" + r.get(6).branch());
    }

    @Test
    void 项目和分支各占一行没有冒号() {
        assertEquals("commerce-channel=zhaojinfeng/2026-09-10/doudian",
                pairs("commerce-channel\nzhaojinfeng/2026-09-10/doudian"));
    }

    @Test
    void 方括号写法() {
        String text = """
                [project]:app_esports_android
                [branch]:dev20260924


                [project]:esportapp-ios
                [branch]:dev20260924

                [project]:app-swift
                [branch]:2026-09-18/APP-9119/douyin_case
                """;
        assertEquals("app_esports_android=dev20260924; esportapp-ios=dev20260924; "
                + "app-swift=2026-09-18/APP-9119/douyin_case", pairs(text));
    }

    @Test
    void 客户端只写端名() {
        assertEquals("Android=dev20260924", pairs("Android：dev20260924"));
        assertEquals("Android=dev20260917", pairs("Android分支：dev20260917"));
        assertEquals("iOS=dev20261015", pairs("iOS 分支：dev20261015"));
        assertEquals("iOS=dev20261012", pairs("iOS: dev20261012"));
    }

    @Test
    void 整段混写() {
        String text = """
                app-swift-v3: feat/APP-9083/candyworks

                pwd-game-mall: feat/app-9083/candyshop

                app-engine: mike/20260901/APP-9083
                event-center: mike/20260901/APP-9083
                robot: mike/20260901/APP-9083

                Android分支：dev20260917
                QA:已测试，已上线
                配置：spring.redis.host: 10.0.0.1
                测试地址 https://a.b/c
                """;
        assertEquals("app-swift-v3=feat/APP-9083/candyworks; pwd-game-mall=feat/app-9083/candyshop; "
                + "app-engine=mike/20260901/APP-9083; event-center=mike/20260901/APP-9083; "
                + "robot=mike/20260901/APP-9083; Android=dev20260917", pairs(text));
    }

    @Test
    void 不是分支的不认() {
        assertTrue(BranchNoteParser.parse("QA:已测试，已上线\n注意：先发 admin").isEmpty());
        assertTrue(BranchNoteParser.parse("app-admin: 增加配置").isEmpty());
    }

    // ---------------- 项目匹配 ----------------

    private static ApiProject proj(String code, String name, String kind, String gitUrl) {
        ApiProject p = new ApiProject();
        p.setCode(code);
        p.setName(name);
        p.setKind(kind);
        p.setGitUrl(gitUrl);
        return p;
    }

    private static final List<ApiProject> PROJECTS = List.of(
            proj("app-admin", "app-admin", "BACKEND", "http://gitlab.shdev.net/SteamCN/app-admin.git"),
            proj("app-engine", "app-engine", "BACKEND", null),
            proj("robot", "robot", "BACKEND", null),
            proj("app-message-center", "评论服务", "BACKEND", null),
            proj("app-swift-v3", "app-swift-v3（电竞 H5 v3）", "FRONTEND", null),
            proj("app-esports-android", "电竞 Android", "CLIENT", "http://gitlab.shdev.net/x/app_esports_android.git"),
            proj("app-esports-ios", "电竞 iOS", "CLIENT", "http://gitlab.shdev.net/x/esportapp-ios.git"));

    private static String code(String raw) {
        ProjectMatcher.Match m = ProjectMatcher.match(raw, PROJECTS);
        return m == null ? null : m.project().getCode() + "/" + m.how();
    }

    @Test
    void 项目匹配() {
        assertEquals("app-admin/EXACT", code("app-admin"));
        assertEquals("app-swift-v3/EXACT", code("app-swift-v3"));
        assertEquals("app-engine/FUZZY", code("app-engnie"));
        assertEquals("robot/ALIAS", code("app-robot"));
        assertEquals("app-message-center/ALIAS", code("comment-service"));
        assertEquals("app-esports-android/EXACT", code("app_esports_android"));
        assertEquals("app-esports-ios/EXACT", code("esportapp-ios"));
        assertEquals("app-esports-android/CLIENT", code("Android"));
        assertEquals("app-esports-ios/CLIENT", code("iOS"));
        assertNull(code("pwd-game-mall"));
    }

    @Test
    void 有独立的app_comment_service时_不再被老别名带去消息中心() {
        List<ApiProject> withComment = new java.util.ArrayList<>(PROJECTS);
        withComment.add(proj("app-comment-service", "评论服务（新）", "BACKEND", null));
        ProjectMatcher.Match m = ProjectMatcher.match("comment-service", withComment);
        assertEquals("app-comment-service", m.project().getCode());
        assertEquals("app-comment-service", ProjectMatcher.match("app-comment-service", withComment).project().getCode());
        assertEquals("app-comment-service", ProjectMatcher.match("app_comment_service", withComment).project().getCode());
    }

    @Test
    void 开发乱写_少横线_下划线_连写_都认得() {
        List<ApiProject> ps = new java.util.ArrayList<>(PROJECTS);
        ps.add(proj("app-comment-service", "评论服务（新）", "BACKEND", null));
        ps.add(proj("app-commerce-channel", "commerce-channel", "BACKEND", null));
        for (String raw : List.of("commentservice", "comment_service", "appcommentservice", "app-commentservice",
                "App_Comment_Service", "commentservcie")) {
            ProjectMatcher.Match m = ProjectMatcher.match(raw, ps);
            assertEquals("app-comment-service", m == null ? null : m.project().getCode(), raw);
        }
        assertEquals("app-commerce-channel", ProjectMatcher.match("commercechannel", ps).project().getCode());
        assertEquals("app-engine", ProjectMatcher.match("appengine", ps).project().getCode());
    }

    @Test
    void 连写项目名单独一行_下一行是分支() {
        List<BranchRef> r = BranchNoteParser.parse("commentservice\nzhaojinfeng/2026-09-10/doudian");
        assertEquals(1, r.size());
        assertEquals("commentservice", r.get(0).project());
        assertEquals("zhaojinfeng/2026-09-10/doudian", r.get(0).branch());
        assertTrue(BranchNoteParser.parse("master\nzhaojinfeng/2026-09-10/doudian").isEmpty());
    }
}
