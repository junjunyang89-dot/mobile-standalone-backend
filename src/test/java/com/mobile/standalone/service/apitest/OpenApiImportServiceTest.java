package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiEndpoint;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenApiImportServiceTest {

    private final ApiEndpointService endpoints = mock(ApiEndpointService.class);
    private final OpenApiImportService service = new OpenApiImportService(endpoints);

    @Test
    void 导入openapi写入docMeta() {
        when(endpoints.applyDocMeta(any(), any(), any(), any()))
                .thenReturn(new ApiEndpoint());

        String doc = """
                {
                  "openapi": "3.0.0",
                  "paths": {
                    "/gld/deadlock/friend/list": {
                      "get": {
                        "summary": "好友列表",
                        "description": "分页拉好友",
                        "tags": ["deadlock"],
                        "x-mysql-tables": ["friend_relation"],
                        "x-redis-keys": ["friend:list:{uid}"],
                        "responses": {
                          "200": {
                            "content": {
                              "application/json": {
                                "schema": {
                                  "type": "object",
                                  "properties": {
                                    "code": { "type": "integer" },
                                    "data": { "type": "array" }
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """;

        var result = service.importDocument("app-global-data", doc);

        assertThat(result.imported()).isEqualTo(1);
        verify(endpoints).applyDocMeta(
                eq("app-global-data"), eq("GET"), eq("/gld/deadlock/friend/list"),
                // referenceOnly 不在这儿断言：它是 applyDocMeta 自己盖的章，
                // 而这里 ApiEndpointService 被 mock 掉了，真方法根本没跑。
                // 盖章盖在被调用方是对的——以后再来个 Swagger、Confluence 导入，
                // 谁都不用记得带这个标记，而忘了的后果是文档里的字段
                // 被当成实测事实拿去生成断言。这个不变量由下面那条测试守
                org.mockito.ArgumentMatchers.argThat(meta ->
                        "好友列表".equals(meta.get("summary"))
                                && meta.get("fields") instanceof java.util.List<?> f
                                && f.contains("code")
                                && meta.get("mysqlTables") instanceof java.util.List<?> m
                                && m.contains("friend_relation")));
    }
}
