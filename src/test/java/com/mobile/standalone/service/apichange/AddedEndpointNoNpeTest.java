package com.mobile.standalone.service.apichange;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 「新增一个接口，而它复用了没有变化的 DTO」——最常见的一种改动，
 * 之前会让整个分析抛 NPE、报告直接失败。
 * <p>
 * 它藏得深是因为有短路：只要新接口的返回类型<b>也</b>改了，就走不到出问题那一步。
 * 所以随手测一次多半是好的，偏偏真实场景里复用已有 DTO 才是常态。
 */
class AddedEndpointNoNpeTest {

    private void write(Path dir, String name, String body) throws IOException {
        Path f = dir.resolve("src/main/java/demo").resolve(name);
        Files.createDirectories(f.getParent());
        Files.writeString(f, body);
    }

    private static final String VO = """
            package demo;
            public class LogVo { private String id; private String content; }
            """;

    @Test
    void 新增接口且返回类型没变时不抛异常(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve("base");
        Path head = tmp.resolve("head");

        write(base, "LogVo.java", VO);
        write(base, "LogController.java", """
                package demo;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/changelog")
                public class LogController {
                    @GetMapping("/list")
                    public LogVo list(@RequestParam String appId) { return null; }
                }
                """);

        // head：多了一个 /copy，而 LogVo 一个字段都没改
        write(head, "LogVo.java", VO);
        write(head, "LogController.java", """
                package demo;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/changelog")
                public class LogController {
                    @GetMapping("/list")
                    public LogVo list(@RequestParam String appId) { return null; }
                    @PostMapping("/copy")
                    public LogVo copy(@RequestParam String id) { return null; }
                }
                """);

        ApiChangeAnalyzer analyzer = new ApiChangeAnalyzer();
        ApiChangeAnalyzer.Report[] report = new ApiChangeAnalyzer.Report[1];

        assertThatCode(() ->
                report[0] = analyzer.analyzeDirs("t", base.toString(), head.toString()))
                .doesNotThrowAnyException();

        // 不光不能崩，新增的那个接口必须真的出现在报告里
        assertThat(report[0].getEndpoints())
                .anyMatch(e -> "/changelog/copy".equals(e.getPath())
                        && "ADDED".equals(e.getChangeType()));
    }
}
