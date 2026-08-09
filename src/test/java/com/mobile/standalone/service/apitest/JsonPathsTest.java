package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路径取值。断言和 extract 共用这一份，所以边界要钉死。
 */
class JsonPathsTest {

    private static final String BODY = """
            {"code":0,"message":"success","result":{
              "token":"tk-123",
              "userId":9007199254740993,
              "list":[{"id":1,"name":"a"},{"id":2,"name":"b"}],
              "empty":null
            }}""";

    @Test
    void 按层级取标量() throws Exception {
        assertEquals("tk-123", JsonPaths.readAsString(BODY, "$.result.token"));
        assertEquals("0", JsonPaths.readAsString(BODY, "$.code"));
    }

    @Test
    void 数组下标() throws Exception {
        assertEquals("2", JsonPaths.readAsString(BODY, "$.result.list[1].id"));
    }

    /** 造完数据取「刚插进去那条」时很顺手 */
    @Test
    void 负数下标取倒数() throws Exception {
        assertEquals("b", JsonPaths.readAsString(BODY, "$.result.list[-1].name"));
    }

    @Test
    void 越界返回null而不是抛() throws Exception {
        assertNull(JsonPaths.readAsString(BODY, "$.result.list[9].id"));
    }

    /**
     * 大整数不能走 double。userId 是 2^53+1，转成 double 再转回来会变成 ...992——
     * 拿这个值去请求下一个接口就是查不到数据，而且现象像是后端的锅。
     */
    @Test
    void 大整数不丢精度() throws Exception {
        assertEquals("9007199254740993", JsonPaths.readAsString(BODY, "$.result.userId"));
    }

    /** 对象/数组抽出来给变量用时要是 JSON 原文，不能是 toString 出来的 Java 形态 */
    @Test
    void 对象节点按JSON原文返回() throws Exception {
        String s = JsonPaths.readAsString(BODY, "$.result.list[0]");

        assertTrue(s.startsWith("{") && s.contains("\"name\":\"a\""), s);
    }

    @Test
    void 路径不存在或值为null都返回null() throws Exception {
        assertNull(JsonPaths.readAsString(BODY, "$.result.nope"));
        assertNull(JsonPaths.readAsString(BODY, "$.result.empty"));
    }

    @Test
    void 空输入不抛() throws Exception {
        assertNull(JsonPaths.readAsString(null, "$.a"));
        assertNull(JsonPaths.readAsString(BODY, null));
        assertNull(JsonPaths.readAsString("not json", "$.a"));
    }

    @Test
    void 前缀写法都兼容() throws Exception {
        assertEquals("0", JsonPaths.readAsString(BODY, "$.code"));
        assertEquals("0", JsonPaths.readAsString(BODY, "code"));
        assertEquals("0", JsonPaths.readAsString(BODY, "$code"));
    }
}
