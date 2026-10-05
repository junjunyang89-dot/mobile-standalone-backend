package com.mobile.standalone.service.releasebatch;

import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.EndpointChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.FieldChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.ParamChange;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.CompatCheck;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.CompatHit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallerCompatProbeTest {

    private static EndpointChange endpoint(String method, String path) {
        EndpointChange e = new EndpointChange();
        e.setChangeType("MODIFIED");
        e.setRisk("BREAKING");
        e.setHttpMethod(method);
        e.setPath(path);
        return e;
    }

    private static FieldChange field(String type, String name, String oldType, String newType, String wire) {
        FieldChange f = new FieldChange();
        f.setChangeType(type);
        f.setName(name);
        f.setOldType(oldType);
        f.setNewType(newType);
        f.setWireEffect(wire);
        return f;
    }

    private static Path write(Path root, String rel, String src) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, src);
        return p;
    }

    private static List<CompatHit> of(CompatCheck c, String caller) {
        return c.getHits().stream().filter(h -> caller.equals(h.getCaller())).toList();
    }

    @Test
    void 老版本App在读被删的字段_标高风险并定位到文件(@TempDir Path dir) throws Exception {
        Path android = dir.resolve("android");
        write(android, "app/src/main/java/com/x/api/LotteryApi.kt",
                "interface LotteryApi {\n  @POST(\"/activity/lottery/draw\")\n  fun draw(): Call<DrawResp>\n}\n");
        write(android, "app/src/main/java/com/x/model/DrawResp.kt",
                "data class DrawResp(\n  val prizeName: String,\n  val times: Int\n)\n");
        write(android, "app/src/test/java/com/x/DrawRespTest.kt", "val prizeName = \"x\"");

        EndpointChange e = endpoint("POST", "/activity/lottery/draw");
        e.getFields().add(field("REMOVED", "prizeName", "String", null, null));
        e.getFields().add(field("TYPE_CHANGED", "times", "Integer", "int", "NULLABILITY"));

        CompatCheck c = CallerCompatProbe.probe(List.of(new CallerCompatProbe.Endpoint("app-activity", e)),
                List.of(new CallerCompatProbe.Caller("android", "app-esports-android", "CLIENT", false, android, null)));

        assertEquals(List.of("app-esports-android（客户端）"), c.getChecked());
        CompatHit removed = c.getHits().get(0);
        assertEquals("RISK", removed.getLevel());
        assertEquals("FOUND", removed.getFieldFound());
        assertEquals(List.of("app/src/main/java/com/x/model/DrawResp.kt:2"), removed.getFieldRefs(), "单测文件不算");
        assertTrue(removed.getCallRefs().get(0).startsWith("app/src/main/java/com/x/api/LotteryApi.kt:2"));
        assertTrue(removed.getAdvice().contains("新包修了也没用"), removed.getAdvice());
        CompatHit nullable = c.getHits().get(1);
        assertEquals("CHECK", nullable.getLevel());
        assertEquals("FOUND", nullable.getFieldFound());
        assertTrue(nullable.getAdvice().contains("判空"), nullable.getAdvice());
    }

    @Test
    void 没调这个接口的仓库不出结果_调了但搜不到字段降为待确认(@TempDir Path dir) throws Exception {
        Path h5 = dir.resolve("h5");
        write(h5, "src/api/lottery.ts", "export const draw = () => request.post('/activity/lottery/draw')\n");
        write(h5, "src/views/Lottery.vue", "<template><div>{{ prize }}</div></template>\n");
        Path other = dir.resolve("other");
        write(other, "src/api/user.ts", "export const me = () => request.get('/user/me')\n");

        EndpointChange e = endpoint("POST", "/activity/lottery/draw");
        e.getFields().add(field("REMOVED", "prizeName", "String", null, null));

        CompatCheck c = CallerCompatProbe.probe(List.of(new CallerCompatProbe.Endpoint("app-activity", e)), List.of(
                new CallerCompatProbe.Caller("h5", "app-swift-v3", "FRONTEND", false, h5, null),
                new CallerCompatProbe.Caller("other", "pogpog-h5", "FRONTEND", false, other, null)));

        assertTrue(of(c, "pogpog-h5").isEmpty());
        CompatHit h = of(c, "app-swift-v3").get(0);
        assertEquals("NOT_FOUND", h.getFieldFound());
        assertEquals("CHECK", h.getLevel());
        assertEquals(2, c.getChecked().size());
    }

    @Test
    void 不在单里的H5_新增必填入参_标高风险_在单里的标待确认(@TempDir Path dir) throws Exception {
        Path h5 = dir.resolve("h5");
        write(h5, "src/api/lottery.ts", "export const draw = () => request.post('/api/activity/lottery/draw')\n");
        EndpointChange e = endpoint("POST", "/activity/lottery/draw");
        ParamChange p = new ParamChange();
        p.setChangeType("ADDED");
        p.setName("activityId");
        p.setRequired(true);
        e.getParams().add(p);

        CompatCheck outside = CallerCompatProbe.probe(List.of(new CallerCompatProbe.Endpoint("app-activity", e)),
                List.of(new CallerCompatProbe.Caller("h5", "app-swift-v3", "FRONTEND", false, h5, null)));
        assertEquals("RISK", outside.getHits().get(0).getLevel());
        assertTrue(outside.getHits().get(0).getProblem().contains("activityId"));
        assertNull(outside.getHits().get(0).getFieldFound());

        CompatCheck inside = CallerCompatProbe.probe(List.of(new CallerCompatProbe.Endpoint("app-activity", e)),
                List.of(new CallerCompatProbe.Caller("h5", "app-swift-v3", "FRONTEND", true, h5, null)));
        assertEquals("CHECK", inside.getHits().get(0).getLevel());
    }

    @Test
    void 没拿到代码的仓库记进没检查_不当成没受影响() {
        EndpointChange e = endpoint("POST", "/activity/lottery/draw");
        e.setChangeType("REMOVED");
        CompatCheck c = CallerCompatProbe.probe(List.of(new CallerCompatProbe.Endpoint("app-activity", e)),
                List.of(new CallerCompatProbe.Caller("ios", "app-esports-ios", "CLIENT", false, null, "本地还没有这个仓库")));
        assertTrue(c.getHits().isEmpty());
        assertTrue(c.getChecked().isEmpty());
        assertEquals(List.of("app-esports-ios（客户端）：本地还没有这个仓库"), c.getUnchecked());
    }

    @Test
    void 只有兼容改动时不算不兼容_不用去拉调用方仓库() {
        EndpointChange e = endpoint("GET", "/a");
        e.setRisk("COMPATIBLE");
        e.getFields().add(field("ADDED", "newField", null, "String", null));
        e.getFields().add(field("TYPE_CHANGED", "n", "int", "long", "WIDENING"));
        assertFalse(CallerCompatProbe.anyBreak(List.of(new CallerCompatProbe.Endpoint("x", e))));

        EndpointChange shape = endpoint("GET", "/b");
        shape.getFields().add(field("TYPE_CHANGED", "tags", "List<String>", "String", "SHAPE"));
        assertTrue(CallerCompatProbe.anyBreak(List.of(new CallerCompatProbe.Endpoint("x", shape))));
    }

    @Test
    void 字段名_取最后一段_常见名和下划线写法() {
        assertEquals("prizeName", CallerCompatProbe.searchWord("data.prizeName"));
        assertNull(CallerCompatProbe.searchWord("status"));
        assertNull(CallerCompatProbe.searchWord("id"));
        assertEquals("prize_name", CallerCompatProbe.snake("prizeName"));
    }
}
