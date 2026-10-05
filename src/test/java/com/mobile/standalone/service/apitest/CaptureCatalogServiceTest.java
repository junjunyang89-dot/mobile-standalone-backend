package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiEndpoint;
import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.entity.ApiUrlEntry;
import com.mobile.standalone.mapper.ApiEndpointMapper;
import com.mobile.standalone.mapper.ApiTestCaseMapper;
import com.mobile.standalone.service.ApiProjectService;
import com.mobile.standalone.service.ApiUrlService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaptureCatalogServiceTest {

    private static ApiEndpoint ep(String project) {
        ApiEndpoint e = new ApiEndpoint();
        e.setProject(project);
        return e;
    }

    private static CaptureCatalogService service(ApiEndpointMapper mapper) {
        return new CaptureCatalogService(null, null, mapper, null, null, null, null, null);
    }

    @Test
    void pathPrefixWinsOverPickedProject() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        assertEquals("app-global-data",
                service(mapper).resolveProject("app-community", "GET", "/gld/deadlock/list"));
    }

    @Test
    void uniqueOwnerInCatalogWinsOverPickedProject() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        when(mapper.findByMethodPath("GET", "/user/info")).thenReturn(List.of(ep("appuser")));
        assertEquals("appuser", service(mapper).resolveProject("app-community", "GET", "/user/info"));
    }

    @Test
    void ambiguousOrUnknownFallsBackToPickedProject() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        when(mapper.findByMethodPath("GET", "/a")).thenReturn(List.of(ep("p1"), ep("p2")));
        when(mapper.findByMethodPath("GET", "/b")).thenReturn(List.of());
        assertEquals("app-community", service(mapper).resolveProject("app-community", "GET", "/a"));
        assertNull(service(mapper).resolveProject(null, "GET", "/b"));
    }

    @Test
    void inboxOwnerIsIgnoredWhenInferringProject() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        when(mapper.findByMethodPath("GET", "/user/info"))
                .thenReturn(List.of(ep(CaptureCatalogService.INBOX), ep("appuser")));
        assertEquals("appuser", service(mapper).resolveProject(null, "GET", "/user/info"));
    }

    private final ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
    private final ApiEndpointService endpoints = mock(ApiEndpointService.class);
    private final ApiProjectService projects = mock(ApiProjectService.class);
    private final ApiTestCaseMapper cases = mock(ApiTestCaseMapper.class);
    private final ApiUrlService urls = mock(ApiUrlService.class);

    private CaptureCatalogService saving() {
        return new CaptureCatalogService(null, endpoints, mapper, projects, null, cases, null, urls);
    }

    private static ApiUrlEntry url(String id, String name, String base) {
        ApiUrlEntry u = new ApiUrlEntry();
        u.setId(id);
        u.setName(name);
        u.setBaseUrl(base);
        u.setEnvironment("TEST");
        return u;
    }

    private static Map<String, Object> savedRow(String project, String origin) {
        Map<String, Object> r = new java.util.HashMap<>();
        r.put("project", project);
        r.put("endpointId", "e");
        r.put("origin", origin);
        return r;
    }

    @Test
    void originDropsPathQueryAndDefaultPort() {
        assertEquals("https://gwapi-staging.pwesports.cn", CaptureCatalogService.originOf(
                "https://gwapi-staging.pwesports.cn/apppush/user/message/msgClassNew?token=1e14"));
        assertEquals("http://172.16.108.178:8100", CaptureCatalogService.originOf("http://172.16.108.178:8100/a"));
        assertEquals("https://h.cn", CaptureCatalogService.originOf("https://h.cn:443/a"));
        assertNull(CaptureCatalogService.originOf("/no/host"));
        assertNull(CaptureCatalogService.originOf(null));
    }

    @Test
    void backfillAddsCapturedOriginInFrontOfIntranetSeedWithoutTouchingIt() {
        when(urls.autoPick("app-push", "TEST")).thenReturn(url("url-host-app-push-178", "内网", "http://172.16.108.178:8100"));
        when(urls.list("app-push", "TEST", null)).thenReturn(List.of(url("url-host-app-push-178", "内网", "http://172.16.108.178:8100")));

        List<Map<String, Object>> out = saving().backfillTestUrls(List.of(
                savedRow("app-push", "https://gwapi-staging.pwesports.cn"),
                savedRow("app-push", "https://gwapi-staging.pwesports.cn"),
                savedRow("app-push", "https://other.cn"),
                savedRow(CaptureCatalogService.INBOX, "https://x.cn")));

        assertEquals(1, out.size());
        assertEquals("CREATED", out.get(0).get("action"));
        assertEquals("http://172.16.108.178:8100", out.get(0).get("previous"));
        ArgumentCaptor<ApiUrlEntry> made = ArgumentCaptor.forClass(ApiUrlEntry.class);
        verify(urls).create(made.capture());
        assertEquals("https://gwapi-staging.pwesports.cn", made.getValue().getBaseUrl());
        assertEquals(0, made.getValue().getSortOrder());
        verify(urls, never()).update(eq("url-host-app-push-178"), any());
    }

    @Test
    void backfillOverwritesItsOwnEntryAndSkipsWhenAlreadyPicked() {
        when(urls.autoPick("p1", "TEST")).thenReturn(url("u-cap", CaptureCatalogService.CAPTURE_URL_NAME, "https://old.cn"));
        when(urls.list("p1", "TEST", null)).thenReturn(List.of(url("u-cap", CaptureCatalogService.CAPTURE_URL_NAME, "https://old.cn")));
        when(urls.autoPick("p2", "TEST")).thenReturn(url("u2", "手配", "https://same.cn/"));

        List<Map<String, Object>> out = saving().backfillTestUrls(List.of(
                savedRow("p1", "https://new.cn"), savedRow("p2", "https://same.cn")));

        assertEquals("UPDATED", out.get(0).get("action"));
        verify(urls).update(eq("u-cap"), any());
        assertEquals("KEPT", out.get(1).get("action"));
        verify(urls, never()).create(any());
    }

    @Test
    void urlPlanUsesTheEnvAndDomainThePersonPicked() {
        when(projects.get("app-engine")).thenReturn(new ApiProject());
        when(urls.list("app-engine", "PROD", null)).thenReturn(List.of());

        List<Map<String, Object>> out = saving().applyUrlPlans(List.of(
                new CaptureCatalogService.UrlPlan("app-engine", "PROD", "appengine.wmpvp.com/steamcn/x?token=1")));

        assertEquals("CREATED", out.get(0).get("action"));
        ArgumentCaptor<ApiUrlEntry> made = ArgumentCaptor.forClass(ApiUrlEntry.class);
        verify(urls).create(made.capture());
        assertEquals("https://appengine.wmpvp.com", made.getValue().getBaseUrl());
        assertEquals("PROD", made.getValue().getEnvironment());
        assertEquals("抓包回填 · 生产环境", made.getValue().getName());
    }

    @Test
    void urlPlanRejectsInboxUnknownProjectAndBadDomain() {
        when(projects.get("p1")).thenReturn(new ApiProject());

        List<Map<String, Object>> out = saving().applyUrlPlans(List.of(
                new CaptureCatalogService.UrlPlan(CaptureCatalogService.INBOX, "TEST", "https://a.cn"),
                new CaptureCatalogService.UrlPlan("nope", "TEST", "https://a.cn"),
                new CaptureCatalogService.UrlPlan("p1", "TEST", "  ")));

        assertTrue(out.stream().allMatch(r -> "FAILED".equals(r.get("action"))));
        assertEquals("项目不存在：nope", out.get(1).get("message"));
        verify(urls, never()).create(any());
    }

    @Test
    void pickedProjectBeatsPathInference() {
        when(projects.get("my-proj")).thenReturn(new ApiProject());
        when(endpoints.ensureEndpointFor("my-proj", "GET", "/gld/x", null, null)).thenReturn("e1");

        Map<String, Object> row = saving().save("s", null,
                List.of(new CaptureCatalogService.FlowRef(null, "get", "/gld/x", null, "my-proj"))).get(0);

        assertEquals("my-proj", row.get("project"));
        assertEquals("e1", row.get("endpointId"));
    }

    @Test
    void pickedProjectMovesTheInboxCopyInsteadOfDuplicating() {
        ApiEndpoint parked = ep(CaptureCatalogService.INBOX);
        parked.setId("p1");
        parked.setHttpMethod("GET");
        parked.setPath("/member/memberInfo");
        when(projects.get("my-proj")).thenReturn(new ApiProject());
        when(mapper.findByKey(CaptureCatalogService.INBOX, "GET", "/member/memberInfo")).thenReturn(parked);
        when(mapper.findById("p1")).thenReturn(parked);
        when(endpoints.ensureEndpointFor("my-proj", "GET", "/member/memberInfo", null, null)).thenReturn("p1");

        Map<String, Object> row = saving().save("s", null, List.of(
                new CaptureCatalogService.FlowRef(null, "GET", "/member/memberInfo", null, "my-proj"))).get(0);

        verify(mapper).updateProject("p1", "my-proj");
        assertEquals("p1", row.get("endpointId"));
    }

    @Test
    void unknownOrInboxProjectIsRejectedPerRow() {
        List<Map<String, Object>> rows = saving().save("s", null, List.of(
                new CaptureCatalogService.FlowRef(null, "GET", "/a", null, "nope"),
                new CaptureCatalogService.FlowRef(null, "GET", "/b", null, CaptureCatalogService.INBOX)));

        assertEquals("项目不存在：nope", rows.get(0).get("message"));
        assertEquals("不能收录到「待归类」", rows.get(1).get("message"));
        assertNull(rows.get(0).get("endpointId"));
        verify(endpoints, never()).ensureEndpointFor(any(), any(), any(), any(), any());
    }

    @Test
    void saveRejectsPreflightOptions() {
        Map<String, Object> row = saving().save("s1", null, List.of(
                new CaptureCatalogService.FlowRef(1, "options", "/appuser/x", null, "appuser"))).get(0);
        assertEquals("OPTIONS 是浏览器跨域预检，不收录", row.get("message"));
        assertNull(row.get("endpointId"));
        verify(endpoints, never()).ensureEndpointFor(any(), any(), any(), any(), any());
    }

    @Test
    void ensureEndpointNeverCreatesPreflight() {
        ApiEndpointMapper m = mock(ApiEndpointMapper.class);
        assertNull(new ApiEndpointService(m, null).ensureEndpointFor("appuser", "OPTIONS", "/x", null, null));
        verify(m, never()).insert(any());
    }

    private static ApiEndpoint counted(String id, String method, int cases) {
        ApiEndpoint e = ep("appuser");
        e.setId(id);
        e.setHttpMethod(method);
        e.setPath("/x/" + id);
        e.setCaseCount(cases);
        return e;
    }

    @Test
    void purgeKeepsPreflightWithCasesUnlessAsked() {
        when(mapper.search(null, null, null, null)).thenReturn(List.of(
                counted("o1", "OPTIONS", 0), counted("o2", "OPTIONS", 2), counted("g1", "GET", 0)));
        when(mapper.deleteById(any())).thenReturn(1);

        Map<String, Object> r = saving().purgePreflight(false);
        assertEquals(1, r.get("deleted"));
        assertEquals(1, ((List<?>) r.get("kept")).size());
        verify(mapper).deleteById("o1");
        verify(mapper, never()).deleteById("o2");
        verify(mapper, never()).deleteById("g1");

        com.mobile.standalone.entity.ApiTestCase c = new com.mobile.standalone.entity.ApiTestCase();
        c.setId("c1");
        when(cases.findByEndpointId("o2")).thenReturn(List.of(c));
        when(cases.deleteById("c1")).thenReturn(1);
        Map<String, Object> all = saving().purgePreflight(true);
        assertEquals(2, all.get("deleted"));
        assertEquals(1, all.get("casesDeleted"));
        verify(cases).deleteById("c1");
        verify(mapper).deleteById("o2");
    }

    @Test
    void misplacedSkipsSourceConfirmedAndCorrectOnes() {
        ApiProject user = new ApiProject();
        user.setCode("app-user");
        user.setName("APP-USER");
        ApiProject event = new ApiProject();
        event.setCode("app-event");
        event.setName("APP-EVENT");
        when(projects.list(true)).thenReturn(List.of(user, event));
        ApiEndpoint wrong = counted("w1", "GET", 4);
        wrong.setProject("app-event");
        wrong.setPath("/appuser/community/user/getUserSupportTeam");
        ApiEndpoint fromSource = counted("s1", "GET", 0);
        fromSource.setProject("app-event");
        fromSource.setPath("/appuser/legacy");
        fromSource.setHandler("LegacyController#x");
        ApiEndpoint right = counted("r1", "GET", 0);
        right.setProject("app-user");
        right.setPath("/appuser/ok");
        when(mapper.search(null, null, null, null)).thenReturn(List.of(wrong, fromSource, right));

        List<Map<String, Object>> rows = saving().misplaced();
        assertEquals(1, rows.size());
        assertEquals("w1", rows.get(0).get("id"));
        assertEquals("app-user", rows.get(0).get("suggest"));
        assertEquals(4, rows.get(0).get("caseCount"));
    }

    @Test
    void classifyMovesLiveCollectionCasesButLeavesRequirementOnes() {
        ApiTestCollectionService cols = mock(ApiTestCollectionService.class);
        com.mobile.standalone.entity.ApiTestCollection evLive = new com.mobile.standalone.entity.ApiTestCollection();
        evLive.setId("col-event");
        com.mobile.standalone.entity.ApiTestCollection userLive = new com.mobile.standalone.entity.ApiTestCollection();
        userLive.setId("col-user");
        when(cols.ensureDefaultLiveCollection("app-event")).thenReturn(evLive);
        when(cols.ensureDefaultLiveCollection("app-user")).thenReturn(userLive);
        when(projects.get("app-user")).thenReturn(new ApiProject());
        ApiEndpoint e = counted("w1", "GET", 2);
        e.setProject("app-event");
        when(mapper.findById("w1")).thenReturn(e);
        com.mobile.standalone.entity.ApiTestCase live = new com.mobile.standalone.entity.ApiTestCase();
        live.setId("c-live");
        live.setCollectionId("col-event");
        com.mobile.standalone.entity.ApiTestCase req = new com.mobile.standalone.entity.ApiTestCase();
        req.setId("c-req");
        req.setCollectionId("col-req-123");
        when(cases.findByEndpointId("w1")).thenReturn(List.of(live, req));

        var svc = new CaptureCatalogService(null, endpoints, mapper, projects, null, cases, cols, urls);
        Map<String, Object> r = svc.classify(List.of("w1"), "app-user");

        assertEquals(1, r.get("moved"));
        verify(mapper).updateProject("w1", "app-user");
        verify(cols).assign("col-user", List.of("c-live"));
        verify(cols, never()).assign(eq("col-user"), eq(List.of("c-req")));
        assertEquals("app-user", req.getProject());
    }

    @Test
    void knownEndpointsListsOwnersAndFlagsInboxButSkipsNewOnes() {
        ApiEndpoint owned = ep("appuser");
        owned.setId("e1");
        when(mapper.findByMethodPath("GET", "/user/info"))
                .thenReturn(List.of(ep(CaptureCatalogService.INBOX), owned));
        when(mapper.findByMethodPath("POST", "/parked")).thenReturn(List.of(ep(CaptureCatalogService.INBOX)));
        when(mapper.findByMethodPath("GET", "/brand/new")).thenReturn(List.of());
        when(cases.findByEndpointId("e1")).thenReturn(List.of(
                new com.mobile.standalone.entity.ApiTestCase(), new com.mobile.standalone.entity.ApiTestCase()));

        List<Map<String, Object>> known = saving().knownEndpoints(List.of(
                new CaptureCatalogService.FlowRef(null, "get", "/user/info", null),
                new CaptureCatalogService.FlowRef(null, "POST", "/parked", null),
                new CaptureCatalogService.FlowRef(null, "GET", "/brand/new", null)));

        assertEquals(2, known.size());
        assertEquals("GET", known.get(0).get("method"));
        assertEquals(List.of(Map.of("project", "appuser", "id", "e1", "caseCount", 2)), known.get(0).get("owners"));
        assertEquals(true, known.get(0).get("inbox"));
        assertEquals(List.of(), known.get(1).get("owners"));
        assertEquals(true, known.get(1).get("inbox"));
    }
}
