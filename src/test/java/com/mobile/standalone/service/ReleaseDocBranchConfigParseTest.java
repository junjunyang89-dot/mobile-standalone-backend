package com.mobile.standalone.service;

import com.mobile.standalone.config.ReleaseDocProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseDocBranchConfigParseTest {

    private ReleaseDocService svc;

    @BeforeEach
    void setUp() {
        ReleaseDocProperties props = new ReleaseDocProperties();
        props.setBranchKeywords(List.of(
                "pvp-match-ws-server",
                "app-swift-static",
                "app-swift-v3",
                "event-center",
                "eventcenter",
                "app-push",
                "app-swift",
                "global-video",
                "swift-sphere",
                "data-center",
                "datacenter",
                "app-engine",
                "app-user",
                "appuser",
                "esports-api",
                "esportsapi",
                "esport-api",
                "app-community",
                "comment-service",
                "commentservice",
                "iOS 分支",
                "Android分支",
                "Android 分支",
                "客户端分支",
                "客户端",
                "iOS",
                "Android"
        ));
        svc = new ReleaseDocService(props);
    }

    @Test
    void sharedBranchForMultipleProjects() {
        String line = "app-engine、esport-api、app-community：dyl/20260707/deadlock_rating，顺序发版";
        List<String> parts = svc.splitBranchEntries(line, propsKeywords());
        assertEquals(3, parts.size());
        assertTrue(parts.stream().anyMatch(p -> p.startsWith("app-engine:") && p.contains("dyl/20260707/deadlock_rating")));
        assertTrue(parts.stream().anyMatch(p -> p.startsWith("esports-api:") && p.contains("dyl/20260707/deadlock_rating")));
        assertTrue(parts.stream().anyMatch(p -> p.startsWith("app-community:") && p.contains("dyl/20260707/deadlock_rating")));
        assertTrue(parts.stream().noneMatch(p -> p.contains("顺序发版")));
    }

    @Test
    void frontendAndBackendProjects() {
        var parsed = svc.parseComment("""
                event-center: feature/2026-07-20/APP-1
                app-push：fix/app-push-hotfix
                pvp-match-ws-server: main/20260720/ws
                app-swift-v3: feat/APP-8440/deadlock_hero
                app-swift: feat/APP-9000/x
                """);
        assertTrue(parsed.branches.contains("event-center:"));
        assertTrue(parsed.branches.contains("app-push:"));
        assertTrue(parsed.branches.contains("pvp-match-ws-server:"));
        assertTrue(parsed.branches.contains("app-swift-v3: feat/APP-8440/deadlock_hero"));
        assertTrue(parsed.branches.contains("app-swift: feat/APP-9000/x"));
    }

    @Test
    void eventcenterAliasWithoutColon() {
        var parsed = svc.parseComment("eventcenter fch/20260714/APP-8742");
        assertTrue(parsed.branches.contains("event-center: fch/20260714/APP-8742"), parsed.branches);
        assertFalse(parsed.branches.toLowerCase().contains("eventcenter:"), parsed.branches);
    }

    @Test
    void datacenterAliasWithoutColon() {
        var parsed = svc.parseComment("datacenter fch/20260722/APP-8748");
        assertTrue(parsed.branches.contains("data-center: fch/20260722/APP-8748"), parsed.branches);
        assertFalse(parsed.branches.toLowerCase().contains("datacenter:"), parsed.branches);
    }

    @Test
    void eventCenterWithSpaceAlsoWorks() {
        var parsed = svc.parseComment("event-center fch/20260714/APP-8742");
        assertTrue(parsed.branches.contains("event-center: fch/20260714/APP-8742"), parsed.branches);
    }

    @Test
    void branchThenProjectAppuser() {
        var parsed = svc.parseComment("fch/20260721/fixhotfeigine appuser");
        assertTrue(parsed.branches.contains("app-user: fch/20260721/fixhotfeigine"), parsed.branches);
    }

    @Test
    void branchThenProjectAppUserHyphen() {
        var parsed = svc.parseComment("fch/20260721/fixhotfeigine app-user");
        assertTrue(parsed.branches.contains("app-user: fch/20260721/fixhotfeigine"), parsed.branches);
    }

    @Test
    void projectThenBranchStillPreferred() {
        var parsed = svc.parseComment("appuser fch/20260721/fixhotfeigine");
        assertTrue(parsed.branches.contains("app-user: fch/20260721/fixhotfeigine"), parsed.branches);
    }

    @Test
    void projectOnOwnLineThenBranchNextLine() {
        var parsed = svc.parseComment("""
                comment-service
                zhaojinfeng/2026-07-03/reply
                """);
        assertTrue(parsed.branches.contains("comment-service: zhaojinfeng/2026-07-03/reply"), parsed.branches);
        // 分支行不应再单独成条
        assertFalse(parsed.branches.lines().anyMatch(l -> l.equals("zhaojinfeng/2026-07-03/reply")), parsed.branches);
    }

    @Test
    void projectWithColonEmptyThenBranchNextLine() {
        var parsed = svc.parseComment("""
                comment-service:
                zhaojinfeng/2026-07-03/reply
                """);
        assertTrue(parsed.branches.contains("comment-service: zhaojinfeng/2026-07-03/reply"), parsed.branches);
    }

    @Test
    void xxlJobAndSpringConfig() {
        var parsed = svc.parseComment("""
                event-center： 增加xxl-job
                esportsapi 增加配置 测试环境已加
                spring.datasource.deadlock.jdbcUrl=jdbc:mariadb://rm-uf6ic7epig1ffiy5p.mysql.rds.aliyuncs.com/app_deadlock?useUnicode=true&characterEncoding=utf8
                spring.datasource.deadlock.username=
                spring.datasource.deadlock.password=
                spring.datasource.deadlock.driver-class-name=org.mariadb.jdbc.Driver
                """);
        assertTrue(parsed.config.contains("xxl") || parsed.config.contains("event-center"));
        assertTrue(parsed.config.contains("esports-api"), parsed.config);
        assertTrue(parsed.config.contains("增加配置"), parsed.config);
        assertTrue(parsed.config.contains("spring.datasource.deadlock.jdbcUrl"), parsed.config);
        assertTrue(parsed.config.contains("driver-class-name"), parsed.config);
        assertTrue(parsed.config.contains("jdbc:mariadb"), parsed.config);
        // 不应把 esportsapi / esports-api 各记一条
        long esportsHeaders = parsed.config.lines()
                .filter(l -> l.toLowerCase().contains("esports") && l.contains("增加配置"))
                .count();
        assertEquals(1, esportsHeaders, parsed.config);
        // 不应把增加xxl / jdbcUrl 当成分支
        assertFalse(parsed.branches.contains("增加xxl"), parsed.branches);
        assertFalse(parsed.branches.toLowerCase().contains("xxl-job"), parsed.branches);
        assertFalse(parsed.branches.contains("jdbc"), parsed.branches);
        assertFalse(parsed.branches.contains("spring.datasource"), parsed.branches);
        assertFalse(parsed.branches.toLowerCase().contains("esports"), parsed.branches);
    }

    @Test
    void apolloHeaderWithDottedProps() {
        var parsed = svc.parseComment("""
                apollo:

                deadlock.weekly.title.baseline.zero.resample.enabled=true

                deadlock.weekly.title.baseline.zero.interpolation.enabled=true
                """);
        assertTrue(parsed.config.toLowerCase().contains("apollo"), parsed.config);
        assertTrue(parsed.config.contains("deadlock.weekly.title.baseline.zero.resample.enabled=true"), parsed.config);
        assertTrue(parsed.config.contains("deadlock.weekly.title.baseline.zero.interpolation.enabled=true"), parsed.config);
        assertFalse(parsed.branches.contains("deadlock"), parsed.branches);
    }

    @Test
    void esportsapiSpringConfigNotInBranches() {
        var parsed = svc.parseComment("""
                esportsapi 增加配置 测试环境已加
                spring.datasource.deadlock.jdbcUrl=jdbc:mariadb://rm-uf6ic7epig1ffiy5p.mysql.rds.aliyuncs.com/app_deadlock?useUnicode=true&characterEncoding=utf8
                spring.datasource.deadlock.username=app_deadlock
                spring.datasource.deadlock.password=2aIk60E!6vTS
                spring.datasource.deadlock.driver-class-name=org.mariadb.jdbc.Driver
                """);
        assertTrue(parsed.config.contains("esports-api"), parsed.config);
        assertTrue(parsed.config.contains("spring.datasource.deadlock.jdbcUrl"), parsed.config);
        assertTrue(parsed.config.contains("app_deadlock"), parsed.config);
        assertTrue(parsed.branches.isBlank() || parsed.branches.equals("无")
                || !parsed.branches.contains("jdbc"), parsed.branches);
        assertFalse(parsed.branches.contains("spring."), parsed.branches);
        assertFalse(parsed.branches.contains("esports-api: spring"), parsed.branches);
    }

    @Test
    void esportsApiConfigAliasNotDuplicated() {
        var parsed = svc.parseComment("""
                esports-api: 增加配置 测试环境已加
                esportsapi 增加配置 测试环境已加
                spring.datasource.deadlock.jdbcUrl=jdbc:x
                """);
        long headers = parsed.config.lines()
                .filter(l -> l.contains("增加配置"))
                .count();
        assertEquals(1, headers, parsed.config);
        assertTrue(parsed.config.startsWith("esports-api:"), parsed.config);
    }

    @Test
    void ignoreEnvUrlLines() {
        var parsed = svc.parseComment("""
                deadlock英雄列表测试环境地址：  https://news-testing.wmpvp.com/v3/deadlock-heros.html
                app-engine: dyl/20260707/deadlock_rating
                """);
        assertFalse(parsed.branches.contains("news-testing"));
        assertFalse(parsed.config.contains("news-testing"));
        assertTrue(parsed.branches.contains("app-engine: dyl/20260707/deadlock_rating"));
    }

    @Test
    void remarkOrderReleaseKept() {
        var parsed = svc.parseComment("app-engine、esport-api、app-community：dyl/20260707/deadlock_rating，顺序发版");
        assertTrue(parsed.remarks.contains("顺序发版"));
    }

    @Test
    void mergeIosAndroidToClientBranch() {
        var parsed = svc.parseComment("""
                iOS 分支: dev20260723
                Android 分支: dev20260723
                app-engine: dyl/20260707/deadlock_rating
                """);
        assertTrue(parsed.branches.contains("客户端分支: dev20260723"), parsed.branches);
        assertFalse(parsed.branches.contains("iOS 分支"), parsed.branches);
        assertFalse(parsed.branches.contains("Android 分支"), parsed.branches);
        assertTrue(parsed.branches.contains("app-engine: dyl/20260707/deadlock_rating"), parsed.branches);
    }

    @Test
    void mergeOnlyIosStillClientBranch() {
        var parsed = svc.parseComment("iOS 分支: dev20260723");
        assertEquals("客户端分支: dev20260723", parsed.branches.trim());
    }

    @Test
    void bareIosAndroidAlsoClientBranch() {
        var parsed = svc.parseComment("""
                Android：dev20260723
                iOS：dev20260723
                app-engine: dyl/20260707/deadlock_rating
                """);
        assertTrue(parsed.branches.contains("客户端分支: dev20260723"), parsed.branches);
        assertFalse(parsed.branches.matches("(?s).*\\bAndroid\\s*[:：].*"), parsed.branches);
        assertFalse(parsed.branches.matches("(?s).*\\biOS\\s*[:：].*"), parsed.branches);
        assertTrue(parsed.branches.contains("app-engine: dyl/20260707/deadlock_rating"), parsed.branches);
    }

    @Test
    void bareAndroidOnlyStillClientBranch() {
        var parsed = svc.parseComment("Android: feat/2026-07-20/APP-1");
        assertEquals("客户端分支: feat/2026-07-20/APP-1", parsed.branches.trim());
    }

    @Test
    void configFileQaSkippedOnlyProdCounted() {
        var parsed = svc.parseComment("""
                配置文件：qa
                spring.datasource.deadlock.jdbcUrl=jdbc:qa
                spring.datasource.deadlock.username=qa_user
                配置文件：prod
                spring.datasource.deadlock.jdbcUrl=jdbc:prod
                spring.datasource.deadlock.username=prod_user
                """);
        assertFalse(parsed.config.contains("jdbc:qa"), parsed.config);
        assertFalse(parsed.config.contains("qa_user"), parsed.config);
        assertTrue(parsed.config.contains("配置文件"), parsed.config);
        assertTrue(parsed.config.toLowerCase().contains("prod"), parsed.config);
        assertTrue(parsed.config.contains("jdbc:prod"), parsed.config);
        assertTrue(parsed.config.contains("prod_user"), parsed.config);
    }

    @Test
    void configFileQaAloneNotCounted() {
        var parsed = svc.parseComment("""
                配置文件：qa
                spring.foo.bar=1
                app-engine: dyl/20260707/deadlock_rating
                """);
        assertFalse(parsed.config.contains("spring.foo"), parsed.config);
        assertFalse(parsed.config.contains("配置文件"), parsed.config);
        assertTrue(parsed.branches.contains("app-engine: dyl/20260707/deadlock_rating"), parsed.branches);
    }

    private List<String> propsKeywords() {
        return List.of(
                "pvp-match-ws-server",
                "app-swift-static",
                "app-swift-v3",
                "event-center",
                "eventcenter",
                "app-push",
                "app-swift",
                "app-engine",
                "data-center",
                "datacenter",
                "app-user",
                "appuser",
                "esports-api",
                "esportsapi",
                "esport-api",
                "app-community"
        );
    }
}
