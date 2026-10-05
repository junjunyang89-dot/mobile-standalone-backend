package com.mobile.standalone.service.apichange;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 只改了 Service、Controller 一行没动的接口要进报告。
 * <p>
 * 「改业务逻辑不改签名」是最常见的改动形态。原来只遍历变更文件里的 Controller，
 * 这批接口一条都不出现，只在「其它改动」里剩一句「业务实现改了，但没能连回具体接口」。
 */
class UnchangedControllerLookupTest {

    private static final String LOMBOK_CONTROLLER = """
            package a;
            @RestController
            @RequestMapping("/order")
            @RequiredArgsConstructor
            public class OrderController {
                private final OrderService orderService;
                private final UserService userService;

                @GetMapping("/detail")
                public ResponseResult<OrderVO> detail(@RequestParam Long id) {
                    return ResponseResult.ok(orderService.detail(id));
                }

                @GetMapping("/user")
                public ResponseResult<String> user(@RequestParam Long id) {
                    return ResponseResult.ok(userService.name(id));
                }
            }
            """;

    private static final String RESOURCE_CONTROLLER = """
            package a;
            @RestController
            @RequestMapping("/order")
            public class OrderController {
                @Resource
                private OrderService orderService;

                @GetMapping("/detail")
                public ResponseResult<OrderVO> detail(@RequestParam Long id) {
                    return ResponseResult.ok(orderService.detail(id));
                }
            }
            """;

    private static final String SERVICE = """
            package a;
            public interface OrderService {
                OrderVO detail(Long id);
            }
            """;

    private static final String IMPL_BEFORE = """
            package a;
            @Service
            public class OrderServiceImpl implements OrderService {
                public OrderVO detail(Long id) {
                    return new OrderVO(id, 1);
                }
            }
            """;

    private static final String IMPL_AFTER = """
            package a;
            @Service
            public class OrderServiceImpl implements OrderService {
                public OrderVO detail(Long id) {
                    return new OrderVO(id, 2);
                }
            }
            """;

    private static final String IMPL_COMMENT_ONLY = """
            package a;
            @Service
            public class OrderServiceImpl implements OrderService {
                /** 查订单详情 */
                public OrderVO detail(Long id) {
                    return new OrderVO(id, 1);
                }
            }
            """;

    @TempDir
    Path tmp;

    private ApiChangeAnalyzer.Report analyze(String controllerBase, String controllerHead,
                                             String implBase, String implHead) throws Exception {
        return analyze(controllerBase, controllerHead, implBase, implHead, true);
    }

    private ApiChangeAnalyzer.Report analyze(String controllerBase, String controllerHead,
                                             String implBase, String implHead, boolean deep) throws Exception {
        Path base = side("base", controllerBase, implBase);
        Path head = side("head", controllerHead, implHead);
        return new ApiChangeAnalyzer().analyzeDirs("p", base.toString(), head.toString(), deep);
    }

    private Path side(String name, String controller, String impl) throws Exception {
        return side(name, controller, SERVICE, impl);
    }

    private Path side(String name, String controller, String service, String impl) throws Exception {
        Path root = tmp.resolve(name);
        Path pkg = root.resolve("src/main/java/a");
        Files.createDirectories(pkg);
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(pkg.resolve("OrderController.java"), controller);
        Files.writeString(pkg.resolve("OrderService.java"), service);
        Files.writeString(pkg.resolve("OrderServiceImpl.java"), impl);
        return root;
    }

    private static final String TWO_CONTROLLER = """
            package a;
            @RestController
            @RequestMapping("/order")
            @RequiredArgsConstructor
            public class OrderController {
                private final OrderService orderService;

                @GetMapping("/detail")
                public ResponseResult<OrderVO> detail(@RequestParam Long id) {
                    return ResponseResult.ok(orderService.detail(id));
                }

                @GetMapping("/score")
                public ResponseResult<Integer> score(@RequestParam Long id) {
                    return ResponseResult.ok(orderService.score(id));
                }
            }
            """;

    private static final String TWO_SERVICE = """
            package a;
            public interface OrderService {
                OrderVO detail(Long id);
                int score(Long id);
                void recalc();
            }
            """;

    private static final String TWO_IMPL = """
            package a;
            @Service
            public class OrderServiceImpl implements OrderService {
                public OrderVO detail(Long id) {
                    return new OrderVO(id, 1);
                }
                public int score(Long id) {
                    return calc(id);
                }
                public void recalc() {
                    mapper.update(1);
                }
                private int calc(Long id) {
                    return 1;
                }
            }
            """;

    /** comment-service 本地-1005-0050：只改了定时任务调的方法，注入同一个 Service 的接口不该进必测 */
    @Test
    void 接口调的是没改的方法_不拉进来() throws Exception {
        Path base = side("base", TWO_CONTROLLER, TWO_SERVICE, TWO_IMPL);
        Path head = side("head", TWO_CONTROLLER, TWO_SERVICE, TWO_IMPL.replace("mapper.update(1);", "mapper.update(2);"));
        ApiChangeAnalyzer.Report r = new ApiChangeAnalyzer().analyzeDirs("p", base.toString(), head.toString(), true);

        assertTrue(r.getEndpoints().isEmpty(), r.getEndpoints().toString());
        assertTrue(r.getNotes().stream().anyMatch(n -> n.contains("调的是它没改的方法")), r.getNotes().toString());
        assertTrue(r.getImplCallers().values().stream().anyMatch(v -> v.contains("recalc")), r.getImplCallers().toString());
    }

    /** 改的是私有方法 calc()，score() 调了它：/order/score 要进来，/order/detail 不进 */
    @Test
    void 改的私有方法被接口间接调到_要拉进来() throws Exception {
        Path base = side("base", TWO_CONTROLLER, TWO_SERVICE, TWO_IMPL);
        Path head = side("head", TWO_CONTROLLER, TWO_SERVICE, TWO_IMPL.replace("return 1;", "return 2;"));
        ApiChangeAnalyzer.Report r = new ApiChangeAnalyzer().analyzeDirs("p", base.toString(), head.toString(), true);

        assertEquals(1, byPath(r, "/order/score").size(), r.getEndpoints().toString());
        assertTrue(byPath(r, "/order/detail").isEmpty(), r.getEndpoints().toString());
    }

    private static List<ApiChangeAnalyzer.EndpointChange> byPath(ApiChangeAnalyzer.Report r, String path) {
        return r.getEndpoints().stream().filter(e -> path.equals(e.getPath())).toList();
    }

    @Test
    void 只改ServiceImpl时没动的Controller接口也要列出来() throws Exception {
        ApiChangeAnalyzer.Report r = analyze(LOMBOK_CONTROLLER, LOMBOK_CONTROLLER, IMPL_BEFORE, IMPL_AFTER);

        List<ApiChangeAnalyzer.EndpointChange> hit = byPath(r, "/order/detail");
        assertEquals(1, hit.size(), r.getEndpoints().toString());
        ApiChangeAnalyzer.EndpointChange e = hit.get(0);
        assertEquals("MODIFIED", e.getChangeType());
        assertEquals("IMPL", e.getRisk());
        assertEquals(List.of("OrderServiceImpl"), e.getChangedImpls());
        assertTrue(e.getFile().endsWith("OrderController.java"), e.getFile());
    }

    /** 接口对比分析页不开深度分析：结果和原来一样，不反查、不做方法级对比 */
    @Test
    void 不开深度分析时保持原样() throws Exception {
        ApiChangeAnalyzer.Report r = analyze(LOMBOK_CONTROLLER, LOMBOK_CONTROLLER, IMPL_BEFORE, IMPL_AFTER, false);

        assertTrue(r.getEndpoints().isEmpty(), r.getEndpoints().toString());
        assertTrue(r.getLogicChanges().isEmpty(), r.getLogicChanges().toString());
        assertTrue(r.getNotes().stream().noneMatch(n -> n.contains("Controller 没改")), r.getNotes().toString());
    }

    /** 同一个 Controller 里没调改动 Service 的接口不该被拉进来 */
    @Test
    void 没调改动Service的接口不拉进来() throws Exception {
        ApiChangeAnalyzer.Report r = analyze(LOMBOK_CONTROLLER, LOMBOK_CONTROLLER, IMPL_BEFORE, IMPL_AFTER);

        assertTrue(byPath(r, "/order/user").isEmpty(), r.getEndpoints().toString());
    }

    /** 已经连回接口的实现，不该再在「其它改动」里重复出现一次「没能连回接口」 */
    @Test
    void 连回接口的实现不再出现在其它改动里() throws Exception {
        ApiChangeAnalyzer.Report r = analyze(LOMBOK_CONTROLLER, LOMBOK_CONTROLLER, IMPL_BEFORE, IMPL_AFTER);

        assertTrue(r.getOtherChanges().stream().noneMatch(c -> c.getFile().endsWith("OrderServiceImpl.java")),
                r.getOtherChanges().toString());
        assertTrue(r.getNotes().stream().anyMatch(n -> n.contains("Controller 没改")), r.getNotes().toString());
    }

    @Test
    void 注解式注入也能反查() throws Exception {
        ApiChangeAnalyzer.Report r = analyze(RESOURCE_CONTROLLER, RESOURCE_CONTROLLER, IMPL_BEFORE, IMPL_AFTER);

        assertEquals(1, byPath(r, "/order/detail").size(), r.getEndpoints().toString());
    }

    /** 补一次注释就拉进一串「实现改了」的接口，必测清单会被噪音淹掉 */
    @Test
    void 实现只改了注释不拉接口进来() throws Exception {
        ApiChangeAnalyzer.Report r = analyze(LOMBOK_CONTROLLER, LOMBOK_CONTROLLER, IMPL_BEFORE, IMPL_COMMENT_ONLY);

        assertTrue(r.getEndpoints().isEmpty(), r.getEndpoints().toString());
    }

    /** Controller 自己也在变更里时由原来那一轮处理，反查不能再报一遍 */
    @Test
    void Controller也改了时不重复报() throws Exception {
        String headController = LOMBOK_CONTROLLER.replace("public class OrderController {",
                "public class OrderController {\n    private static final int LIMIT = 10;");
        ApiChangeAnalyzer.Report r = analyze(LOMBOK_CONTROLLER, headController, IMPL_BEFORE, IMPL_AFTER);

        assertEquals(1, byPath(r, "/order/detail").size(), r.getEndpoints().toString());
    }
}
