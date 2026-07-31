package com.mobile.standalone.service;

import com.mobile.standalone.config.ReleaseDocProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 复现用户真实备注场景：人名斜杠 + eventcenter/appuser 两种习惯写法。
 */
class ReleaseDocRealCommentParseTest {

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
    void csTicketCommentShouldCatchEventcenterAndAppuser() {
        var parsed = svc.parseComment("""
                CS赛后选手评分新增比赛中评分 - 才华/曹键/中华/文欢，7.17提测，7.23上线
                eventcenter fch/20260714/APP-8742
                fch/20260721/fixhotfeigine appuser
                """);
        System.out.println("BRANCHES=\n" + parsed.branches);
        assertTrue(parsed.branches.contains("event-center: fch/20260714/APP-8742"), parsed.branches);
        assertTrue(parsed.branches.contains("app-user: fch/20260721/fixhotfeigine"), parsed.branches);
        assertFalse(parsed.branches.contains("才华"), parsed.branches);
        assertFalse(parsed.branches.contains("曹键"), parsed.branches);
    }

    @Test
    void fullwidthSpaceAndMixedPunctuation() {
        var parsed = svc.parseComment("eventcenter　fch/20260714/APP-8742\n"
                + "fch/20260721/fixhotfeigine　appuser");
        System.out.println("BRANCHES2=\n" + parsed.branches);
        assertTrue(parsed.branches.contains("event-center: fch/20260714/APP-8742"), parsed.branches);
        assertTrue(parsed.branches.contains("app-user: fch/20260721/fixhotfeigine"), parsed.branches);
    }

    @Test
    void bothOnSameLineStillWorks() {
        var parsed = svc.parseComment(
                "eventcenter fch/20260714/APP-8742 fch/20260721/fixhotfeigine appuser");
        System.out.println("BRANCHES3=\n" + parsed.branches);
        assertTrue(parsed.branches.contains("event-center: fch/20260714/APP-8742"), parsed.branches);
        assertTrue(parsed.branches.contains("app-user: fch/20260721/fixhotfeigine"), parsed.branches);
    }
}
