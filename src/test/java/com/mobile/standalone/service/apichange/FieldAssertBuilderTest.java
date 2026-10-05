package com.mobile.standalone.service.apichange;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FieldAssertBuilderTest {

    private static FieldAssertBuilder.Field added(String name, String type) {
        return new FieldAssertBuilder.Field(name, null, "ADDED", null, type);
    }

    @Test
    void javaTypesMapToJsonTypes() {
        assertEquals("number", FieldAssertBuilder.jsonType("Float"));
        assertEquals("number", FieldAssertBuilder.jsonType("java.math.BigDecimal"));
        assertEquals("string", FieldAssertBuilder.jsonType("String"));
        assertEquals("boolean", FieldAssertBuilder.jsonType("boolean"));
        assertEquals("array", FieldAssertBuilder.jsonType("List<HeroLineDTO>"));
        assertEquals("array", FieldAssertBuilder.jsonType("Long[]"));
        assertEquals("object", FieldAssertBuilder.jsonType("Map<String, Object>"));
        assertEquals("object", FieldAssertBuilder.jsonType("HeroStatsDTO"));
        assertNull(FieldAssertBuilder.jsonType("LocalDateTime"), "时间的序列化格式看项目配置，只断言存在");
        assertNull(FieldAssertBuilder.jsonType("T"));
        assertNull(FieldAssertBuilder.jsonType("Object"));
    }

    @Test
    void prefixFollowsTheCaseResponseIncludingArrayPayload() {
        String body = "{\"code\":0,\"result\":[{\"heroId\":1,\"winrate\":0.52}]}";
        FieldAssertBuilder.Prefix p = FieldAssertBuilder.prefix(body, null);
        assertEquals("$.result[0]", p.path());
        assertFalse(p.guessed());

        List<FieldAssertBuilder.Proposal> out = FieldAssertBuilder.propose(
                List.of(added("winrate", "Float"), added("tier", "String")), body, null, null);
        assertEquals("$.result[0].winrate", out.get(0).path());
        assertEquals("type", out.get(0).check());
        assertEquals("number", out.get(0).jsonType());
        assertEquals("最近一次响应里有这个字段", out.get(0).note());
        assertEquals("$.result[0].tier", out.get(1).path(), "响应里还没有的字段按推断位置写");
    }

    @Test
    void truncatedPreviewStillRevealsTheWrapper() {
        String cut = "{\"code\":0,\"msg\":\"ok\",\"result\":{\"matchResponse\":{\"dtoList\":[{\"matchId\":1,\"na";
        assertEquals("$.result", FieldAssertBuilder.prefix(cut, null).path());
    }

    @Test
    void fallsBackToProjectWrapperThenDefault() {
        FieldAssertBuilder.Prefix fromProject = FieldAssertBuilder.prefix(null, "{\"code\":0,\"data\":[1,2]}");
        assertEquals("$.data", fromProject.path(), "别的接口的 payload 是不是数组不作数");
        assertTrue(fromProject.guessed());
        assertEquals("$.result", FieldAssertBuilder.prefix(null, null).path());
    }

    @Test
    void locatesNestedFieldAndUsesTreePath() {
        String body = "{\"result\":{\"matchResponse\":{\"dtoList\":[{\"matchId\":7,\"heroName\":\"x\"}]}}}";
        List<FieldAssertBuilder.Proposal> out = FieldAssertBuilder.propose(List.of(
                added("heroName", "String"),
                new FieldAssertBuilder.Field("heroId", "heroLine[].heroId", "ADDED", null, "Long")
        ), body, null, null);
        assertEquals("$.result.matchResponse.dtoList[0].heroName", out.get(0).path());
        assertEquals("$.result.heroLine[0].heroId", out.get(1).path());
    }

    @Test
    void removedFieldAssertsAbsentAndDuplicatesAreFlagged() {
        String existing = "[{\"type\":\"jsonPath\",\"path\":\"$.result.oldName\",\"exists\":false}]";
        List<FieldAssertBuilder.Proposal> out = FieldAssertBuilder.propose(List.of(
                new FieldAssertBuilder.Field("oldName", null, "REMOVED", "String", null)
        ), null, null, existing);
        assertEquals("absent", out.get(0).check());
        assertTrue(out.get(0).duplicate());

        ObjectNode a = FieldAssertBuilder.toAssertion("$.result.oldName", "absent", null);
        ObjectNode b = FieldAssertBuilder.toAssertion("$.result.winrate", "type", "number");
        FieldAssertBuilder.Merge m = FieldAssertBuilder.append(existing, List.of(a, b));
        assertEquals(1, m.added());
        assertEquals(1, m.skipped());
        assertTrue(m.json().contains("\"op\":\"type\""));
        assertTrue(m.json().contains("\"value\":\"number\""));
    }

    @Test
    void markKeyDependsOnChangeSigOnlyForConfirm() {
        var a = new FieldMarkService.Item("get", "/x", "RESPONSE", "winrate", "m..f|ADDED||Float");
        var b = new FieldMarkService.Item("GET", "/x", "response", "winrate", "m..f|TYPE_CHANGED|Float|Double");
        assertEquals(FieldMarkService.keyOf(FieldMarkService.TAG, "p", a), FieldMarkService.keyOf(FieldMarkService.TAG, "p", b));
        assertNotEquals(FieldMarkService.keyOf(FieldMarkService.CONFIRM, "p", a),
                FieldMarkService.keyOf(FieldMarkService.CONFIRM, "p", b), "变化本身变了要重新确认");
        assertThrows(IllegalArgumentException.class, () -> FieldMarkService.checked(
                List.of(new FieldMarkService.Item("GET", "/x", "BODY", "a", null)), false));
    }
}
