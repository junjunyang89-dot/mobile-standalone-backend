package com.mobile.standalone.service.releasebatch;

import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.CallSite;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallSiteScannerTest {

    @Test
    void 前端_请求封装里的路径认成接口调用() {
        String src = """
                import request from '@/utils/request'
                export function scoreBreakdown(id) {
                  return request({ url: '/appadmin/comment/score/breakdown', method: 'get', params: { id } })
                }
                export const detail = (id) => request.get(`/appadmin/comment/${id}/detail`)
                """;
        List<CallSite> sites = CallSiteScanner.extract(src, "frontend/src/api/comment.js", CallSiteScanner.FRONT);
        assertEquals(2, sites.size());
        assertEquals("/appadmin/comment/score/breakdown", sites.get(0).getPath());
        assertEquals(3, sites.get(0).getLine());
        assertTrue(sites.get(0).isApiLike());
        assertEquals("/appadmin/comment/{}/detail", sites.get(1).getPath());
    }

    @Test
    void 前端_import_路由_图片_相对路径都不算() {
        String src = """
                import Foo from '@/views/comment/Foo.vue'
                const icon = '/static/img/logo.png'
                this.$router.push('/comment/list/detail')
                const fmt = 'yyyy/MM/dd'
                const rel = './a/b'
                """;
        assertTrue(CallSiteScanner.extract(src, "frontend/src/views/x.vue", CallSiteScanner.FRONT).isEmpty());
        String router = "{ path: '/comment/score/list', component: Score }";
        assertTrue(CallSiteScanner.extract(router, "frontend/src/router/index.js", CallSiteScanner.FRONT).isEmpty());
    }

    @Test
    void 后端_Feign_拼上类上的_path() {
        String src = """
                @FeignClient(name = "app-comment", path = "/appcomment")
                public interface CommentClient {
                    @GetMapping("/comment/score/breakdown")
                    Result<Object> breakdown(@RequestParam Long id);
                }
                """;
        List<CallSite> sites = CallSiteScanner.extract(src, "src/main/java/a/CommentClient.java", CallSiteScanner.BACK);
        assertEquals(1, sites.size());
        assertEquals("/appcomment/comment/score/breakdown", sites.get(0).getPath());
        assertEquals(CallSiteScanner.BACK, sites.get(0).getSide());
    }

    @Test
    void 后端_自己的_Controller_映射不算调用() {
        String src = """
                @RestController
                @RequestMapping("/comment")
                public class CommentController {
                    @GetMapping("/score/breakdown")
                    public Object breakdown() { return null; }
                }
                """;
        assertTrue(CallSiteScanner.extract(src, "src/main/java/a/CommentController.java", CallSiteScanner.BACK).isEmpty());
    }

    @Test
    void 路径过滤() {
        assertEquals("/appadmin/comment/list", CallSiteScanner.acceptPath("https://admin.x.com/appadmin/comment/list?a=1"));
        assertNull(CallSiteScanner.acceptPath("/list"));
        assertNull(CallSiteScanner.acceptPath("text/html"));
        assertNull(CallSiteScanner.acceptPath("/assets/a/b.css"));
        assertFalse(CallSiteScanner.acceptPath("/api/v1/user/info") == null);
    }

    @Test
    void 后端仓库里的前端算_FRONT_Java算_BACK_打包产物不看() {
        assertEquals(CallSiteScanner.FRONT, CallSiteScanner.sideOf("frontend/src/api/a.js", CallSiteScanner.Scope.BACKEND));
        assertEquals(CallSiteScanner.BACK, CallSiteScanner.sideOf("src/main/java/A.java", CallSiteScanner.Scope.BACKEND));
        assertNull(CallSiteScanner.sideOf("src/main/resources/static/js/app.js", CallSiteScanner.Scope.BACKEND));
        assertNull(CallSiteScanner.sideOf("frontend/dist/app.min.js", CallSiteScanner.Scope.FRONTEND));
        assertEquals(CallSiteScanner.CLIENT, CallSiteScanner.sideOf("app/src/main/java/Api.kt", CallSiteScanner.Scope.CLIENT));
    }
}
