package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SourceEndpointScannerTest {

    private static final String CONTROLLER = """
            package demo;
            @RestController
            @RequestMapping("/deadlock/hightlights")
            public class DeadlockHighLightsController {
                @ApiOperation("查询高光列表")
                @GetMapping("/list")
                public ResponseResult<List<String>> list() { return null; }

                @PostMapping("/upload")
                public ResponseResult<Void> upload(@RequestBody UploadReq req) { return null; }
            }
            """;

    @Test
    void scansControllersAndRecordsRelativeFile(@TempDir Path root) throws Exception {
        Path pkg = Files.createDirectories(root.resolve("svc/src/main/java/demo"));
        Files.writeString(pkg.resolve("DeadlockHighLightsController.java"), CONTROLLER);
        Files.writeString(pkg.resolve("Util.java"), "package demo; public class Util {}");

        var r = SourceEndpointScanner.scan(root);

        assertEquals(2, r.javaFiles());
        assertEquals(1, r.controllers());
        assertEquals(2, r.endpoints().size());
        var list = r.endpoints().stream().filter(e -> e.path().endsWith("/list")).findFirst().orElseThrow();
        assertEquals("GET", list.httpMethod());
        assertEquals("/deadlock/hightlights/list", list.path());
        assertEquals("查询高光列表", list.summary());
        assertEquals("svc/src/main/java/demo/DeadlockHighLightsController.java", list.file());
    }

    @Test
    void dropsSwaggerOfflineAndHealthButKeepsBusinessInSameController(@TempDir Path root) throws Exception {
        Path pkg = Files.createDirectories(root.resolve("svc/src/main/java/demo"));
        Files.writeString(pkg.resolve("HealthController.java"), """
                package demo;
                @RestController
                public class HealthController {
                    @GetMapping({"/acty/basic/healthCheck","/basic/healthCheck"})
                    public String healthCheck() { return "success"; }
                    @GetMapping("/loadMajorHotComments")
                    public String load() { return null; }
                }
                """);
        Files.writeString(pkg.resolve("NacosStopEndpoint.java"), """
                package demo;
                @RestController
                public class NacosStopEndpoint {
                    @GetMapping("/basic/offline")
                    public String offline(String secret) { return null; }
                }
                """);
        Files.writeString(pkg.resolve("DisableSwaggerUiController.java"), """
                package demo;
                @RestController
                public class DisableSwaggerUiController {
                    @GetMapping("${disable.swagger.path:/swagger-ui.html}")
                    public void getSwagger(HttpServletResponse r) { }
                }
                """);

        var paths = SourceEndpointScanner.scan(root).endpoints().stream().map(e -> e.path()).toList();

        assertEquals(java.util.List.of("/loadMajorHotComments"), paths);
    }

    @Test
    void skipsTestSources(@TempDir Path root) throws Exception {
        Path testPkg = Files.createDirectories(root.resolve("svc/src/test/java/demo"));
        Files.writeString(testPkg.resolve("DeadlockHighLightsController.java"), CONTROLLER);

        assertEquals(0, SourceEndpointScanner.scan(root).endpoints().size());
    }

    @Test
    void detectsAuthAnnotationsAndTokenHeader(@TempDir Path root) throws Exception {
        Path pkg = Files.createDirectories(root.resolve("svc/src/main/java/demo"));
        Files.writeString(pkg.resolve("AiController.java"), """
                package demo;
                /** @Author someone */
                @RestController
                @RequestMapping("/ai")
                public class AiController {
                    @GetMapping("/comment/{id}")
                    public ResponseResult<String> comment(@PathVariable String id) { return null; }

                    @GetMapping("/mine")
                    @NeedUserLogin
                    public ResponseResult<String> mine() { return comment("1"); }

                    // @Auth 注释掉的不算
                    @GetMapping("/open")
                    public ResponseResult<String> open() { return null; }
                }
                """);
        Files.writeString(pkg.resolve("CommonUtils.java"),
                "class CommonUtils { String t(HttpServletRequest r) { return r.getHeader(\"token\"); } }");

        var r = SourceEndpointScanner.scan(root);

        java.util.function.Function<String, Boolean> auth = p -> r.endpoints().stream()
                .filter(e -> e.path().equals(p)).findFirst().orElseThrow().auth();
        assertEquals(false, auth.apply("/ai/comment/{id}"));
        assertEquals(true, auth.apply("/ai/mine"));
        assertEquals(false, auth.apply("/ai/open"));
        assertEquals(java.util.List.of("token"), r.tokenHeaders());
    }

    @Test
    void authUnknownWhenRepoHasNoAuthAnnotations(@TempDir Path root) throws Exception {
        Path pkg = Files.createDirectories(root.resolve("svc/src/main/java/demo"));
        Files.writeString(pkg.resolve("DeadlockHighLightsController.java"), CONTROLLER);

        var r = SourceEndpointScanner.scan(root);

        assertEquals(true, r.endpoints().stream().allMatch(e -> e.auth() == null));
    }

    @Test
    void rejectsMissingDirectory(@TempDir Path root) {
        assertThrows(IllegalArgumentException.class, () -> SourceEndpointScanner.scan(root.resolve("nope")));
    }
}
