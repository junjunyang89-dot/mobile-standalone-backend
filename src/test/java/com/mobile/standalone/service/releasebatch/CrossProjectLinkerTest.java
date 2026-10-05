package com.mobile.standalone.service.releasebatch;

import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.EndpointChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.FileChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.Report;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.TableChange;
import com.mobile.standalone.service.apichange.ApiEndpoint;
import com.mobile.standalone.service.releasebatch.CallSiteScanner.Scope;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.CallSite;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.FrontChange;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Link;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.ProjectNode;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Target;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossProjectLinkerTest {

    private static Target target(String code, String kind) {
        Target t = new Target();
        t.setRawProject(code);
        t.setProjectCode(code);
        t.setProjectName(code);
        t.setKind(kind);
        t.setAnalyzable("BACKEND".equals(kind));
        t.setBranch("feat/x");
        return t;
    }

    private static EndpointChange ep(String type, String risk, String method, String path) {
        EndpointChange e = new EndpointChange();
        e.setChangeType(type);
        e.setRisk(risk);
        e.setHttpMethod(method);
        e.setPath(path);
        return e;
    }

    private static CallSite call(String file, String path, String side) {
        CallSite c = new CallSite();
        c.setFile(file);
        c.setLine(10);
        c.setPath(path);
        c.setRaw(path);
        c.setSide(side);
        c.setApiLike(true);
        return c;
    }

    private static Report report(EndpointChange... eps) {
        Report r = new Report();
        r.getEndpoints().addAll(List.of(eps));
        FileChange f = new FileChange();
        f.setPath("src/main/java/A.java");
        f.setStatus("MODIFIED");
        r.getFiles().add(f);
        return r;
    }

    @Test
    void 前端这次改了调用_后端也改了接口_是联调点() {
        Target comment = target("comment", "BACKEND");
        Target admin = target("admin", "BACKEND");
        FrontChange fc = new FrontChange();
        fc.setChangedFiles(1);
        CallSite added = call("frontend/src/api/comment.js", "/appcomment/comment/score/breakdown", "FRONT");
        fc.getAddedCalls().add(added);
        admin.setFront(fc);

        var result = CrossProjectLinker.link(List.of(
                new CrossProjectLinker.Project(comment, report(ep("ADDED", "NEW", "GET", "/comment/score/breakdown")),
                        List.of(), List.of(), Set.of("src/main/java/A.java")),
                new CrossProjectLinker.Project(admin, null, List.of(added), List.of(), Set.of(added.getFile()))));

        Link l = result.links().get(0);
        assertEquals(CrossProjectLinker.JOINT, l.getLevel());
        assertEquals("admin:FRONT", l.getFromNode());
        assertEquals("comment:BACK", l.getToNode());
        assertEquals("NEW_CALL", l.getCallChange());
        assertEquals(List.of("frontend/src/api/comment.js:10"), l.getRefs());
        List<String> ids = result.nodes().stream().map(ProjectNode::getId).toList();
        assertTrue(ids.containsAll(List.of("comment:BACK", "admin:BACK", "admin:FRONT")));
    }

    @Test
    void 同仓库前后端_后端只改实现_调用文件顺带动了_只算回归() {
        Target admin = target("admin", "BACKEND");
        FrontChange fc = new FrontChange();
        fc.setChangedFiles(1);
        admin.setFront(fc);
        CallSite list = call("frontend/api/comment.js", "/steamcn/comment/getcommentlist", "FRONT");
        var result = CrossProjectLinker.link(List.of(
                new CrossProjectLinker.Project(admin, report(ep("MODIFIED", "IMPL", "GET", "/steamcn/comment/getCommentList")),
                        List.of(list), List.of(), Set.of("src/main/java/A.java", list.getFile()))));
        Link l = result.links().get(0);
        assertEquals(CrossProjectLinker.INFO, l.getLevel());
        assertEquals("CHANGED_FILE", l.getCallChange());
        assertEquals("admin 前端", l.getFromName());
        assertEquals("admin 后端", l.getToName());
        assertTrue(l.getTitle().contains("只改了实现"), l.getTitle());
    }

    @Test
    void 前端删调用_后端也下线_提示一起下线() {
        Target admin = target("admin", "BACKEND");
        FrontChange fc = new FrontChange();
        fc.setChangedFiles(1);
        fc.getRemovedCalls().add(call("frontend/api/game.js", "/steamgame/select", "FRONT"));
        admin.setFront(fc);
        var result = CrossProjectLinker.link(List.of(
                new CrossProjectLinker.Project(admin, report(ep("REMOVED", "BREAKING", "GET", "/steamGame/select")),
                        List.of(), List.of(), Set.of("frontend/api/game.js"))));
        assertEquals(1, result.links().size());
        Link l = result.links().get(0);
        assertEquals(CrossProjectLinker.INFO, l.getLevel());
        assertEquals("REMOVED_CALL", l.getCallChange());
        assertEquals("admin:FRONT", l.getFromNode());
        assertTrue(l.getTitle().contains("也下线"), l.getTitle());
    }

    @Test
    void 后端不兼容改动_调用方没跟着改_是风险() {
        Target comment = target("comment", "BACKEND");
        Target h5 = target("h5", "FRONTEND");
        CallSite old = call("src/api/a.js", "/comment/detail/info", "FRONT");
        var result = CrossProjectLinker.link(List.of(
                new CrossProjectLinker.Project(comment, report(ep("MODIFIED", "BREAKING", "GET", "/comment/detail/info")),
                        List.of(), List.of(), Set.of()),
                new CrossProjectLinker.Project(h5, null, List.of(old), List.of(), Set.of())));
        Link l = result.links().get(0);
        assertEquals(CrossProjectLinker.RISK, l.getLevel());
        assertEquals("UNCHANGED", l.getCallChange());
    }

    @Test
    void Feign_调用_和_接口下线() {
        Target comment = target("comment", "BACKEND");
        Target engine = target("engine", "BACKEND");
        CallSite feign = call("src/main/java/CommentClient.java", "/appcomment/comment/old/list", "BACK");
        var result = CrossProjectLinker.link(List.of(
                new CrossProjectLinker.Project(comment, report(ep("REMOVED", "BREAKING", "GET", "/comment/old/list")),
                        List.of(), List.of(), Set.of()),
                new CrossProjectLinker.Project(engine, null, List.of(feign), List.of(), Set.of())));
        Link l = result.links().get(0);
        assertEquals(CrossProjectLinker.RISK, l.getLevel());
        assertEquals("engine:BACK", l.getFromNode());
        assertTrue(l.getTitle().contains("下线"));
    }

    @Test
    void 新接口没人调_而前端缺代码_标推测() {
        Target comment = target("comment", "BACKEND");
        Target h5 = target("h5", "FRONTEND");
        h5.setCodeMissing("没填分支，也没填本地目录");
        var result = CrossProjectLinker.link(List.of(
                new CrossProjectLinker.Project(comment, report(ep("ADDED", "NEW", "POST", "/comment/score/save")),
                        List.of(), List.of(), Set.of()),
                new CrossProjectLinker.Project(h5, null, List.of(), List.of(), Set.of())));
        assertTrue(result.links().stream().allMatch(l -> CrossProjectLinker.CHECK.equals(l.getLevel()) && l.isInferred()));
        assertTrue(result.links().stream().anyMatch(l -> l.getTitle().contains("h5")));
        ProjectNode h5Node = result.nodes().stream().filter(n -> n.getId().equals("h5:FRONT")).findFirst().orElseThrow();
        assertEquals("NO_CODE", h5Node.getStatus());
    }

    @Test
    void 前端新调用已有接口_和找不到的接口() {
        Target user = target("user", "BACKEND");
        Target h5 = target("h5", "FRONTEND");
        FrontChange fc = new FrontChange();
        fc.setChangedFiles(1);
        fc.getAddedCalls().add(call("src/api/u.js", "/appuser/user/profile/get", "FRONT"));
        fc.getAddedCalls().add(call("src/api/u.js", "/appx/nowhere/to/go", "FRONT"));
        h5.setFront(fc);
        ApiEndpoint existing = new ApiEndpoint("GET", "/user/profile/get", "get", null, "用户资料", null, List.of(), Set.of());
        var result = CrossProjectLinker.link(List.of(
                new CrossProjectLinker.Project(user, report(), List.of(), List.of(existing), Set.of()),
                new CrossProjectLinker.Project(h5, null, fc.getAddedCalls(), List.of(), Set.of("src/api/u.js"))));
        assertEquals(2, result.links().size());
        Link missing = result.links().get(0);
        assertEquals(CrossProjectLinker.CHECK, missing.getLevel());
        Link hit = result.links().get(1);
        assertEquals(CrossProjectLinker.INFO, hit.getLevel());
        assertEquals("user:BACK", hit.getToNode());
        assertEquals("/user/profile/get", hit.getPath());
    }

    @Test
    void 两个服务改同一张表() {
        Report a = report();
        TableChange t1 = new TableChange();
        t1.setTable("comment_score");
        a.getTables().add(t1);
        Report b = report();
        TableChange t2 = new TableChange();
        t2.setTable("db.`comment_score`");
        b.getTables().add(t2);
        var result = CrossProjectLinker.link(List.of(
                new CrossProjectLinker.Project(target("a", "BACKEND"), a, List.of(), List.of(), Set.of()),
                new CrossProjectLinker.Project(target("b", "BACKEND"), b, List.of(), List.of(), Set.of())));
        assertEquals(1, result.links().size());
        assertTrue(result.links().get(0).getTitle().contains("comment_score"));
    }

    @Test
    void 匹配_允许网关前缀_路径变量() {
        assertTrue(CrossProjectLinker.matches("/appcomment/comment/{}/detail", "/comment/{id}/detail"));
        assertTrue(CrossProjectLinker.matches("/comment/score/getbreakdown", "/comment/score/getBreakdown"));
        assertFalse(CrossProjectLinker.matches("/a/b/c/comment/list", "/comment/list"));
        assertFalse(CrossProjectLinker.matches("/x/list", "/list"));
    }

    @Test
    void 目录对比_前端改动文件和新增删除的调用(@TempDir Path dir) throws Exception {
        Path base = Files.createDirectories(dir.resolve("base"));
        Path head = Files.createDirectories(dir.resolve("head"));
        write(base, "frontend/src/api/comment.js", "export const a = () => request({ url: '/appcomment/comment/old/list' })\n");
        write(head, "frontend/src/api/comment.js", "export const a = () => request({ url: '/appcomment/comment/score/breakdown' })\n");
        write(base, "frontend/src/views/comment/Score.vue", "<template>a</template>\n");
        write(head, "frontend/src/views/comment/Score.vue", "<template>b</template>\n");
        write(head, "frontend/node_modules/x/index.js", "request('/should/not/count')\n");
        write(base, "src/main/java/A.java", "class A {}\n");
        write(head, "src/main/java/A.java", "class A { int x; }\n");

        FrontChange fc = FrontDiffScanner.diff(base, head, Scope.BACKEND, "admin");
        assertEquals(2, fc.getChangedFiles());
        assertEquals(List.of("frontend/src/views/comment/Score.vue"), fc.getPages());
        assertEquals("/appcomment/comment/score/breakdown", fc.getAddedCalls().get(0).getPath());
        assertEquals("/appcomment/comment/old/list", fc.getRemovedCalls().get(0).getPath());

        Path pure = Files.createDirectories(dir.resolve("pure"));
        write(pure, "src/main/java/B.java", "class B {}\n");
        assertEquals(null, FrontDiffScanner.diff(pure, pure, Scope.BACKEND, "svc"));
    }

    private static void write(Path root, String rel, String content) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }
}
