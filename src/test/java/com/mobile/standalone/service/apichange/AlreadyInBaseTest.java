package com.mobile.standalone.service.apichange;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分支的改动已经（以另一个提交）进了基线时，报告要说「没得比」，而不是「没变化」。
 * <p>
 * GitLab 压缩合并 / cherry-pick 之后，{@code master...分支} 仍会列出分支改过的文件
 * （分叉点没变），但基线最新版里内容已经一模一样。原来这种情况一路走到测试评估，
 * 结论是「低风险，冒烟即可」——实际是什么都没比。
 */
class AlreadyInBaseTest {

    @TempDir
    Path repo;

    private void git(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", repo.toString(),
                "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), String.join(" ", args) + "\n" + out);
    }

    private void write(String rel, String content) throws Exception {
        Path f = repo.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
    }

    private static final String IMPL = "src/main/java/a/service/impl/ScoreServiceImpl.java";
    private static final String TEST = "src/test/java/a/service/impl/ScoreServiceImplTest.java";

    private void branchSquashedIntoMaster() throws Exception {
        git("init", "-q", "-b", "master");
        write("pom.xml", "<project/>");
        write(IMPL, "package a;\npublic class ScoreServiceImpl {\n    int score() { return 1; }\n}\n");
        git("add", "-A");
        git("commit", "-q", "-m", "init");

        git("checkout", "-q", "-b", "feat");
        write(IMPL, "package a;\npublic class ScoreServiceImpl {\n    int score() { return 2; }\n}\n");
        write(TEST, "package a;\nclass ScoreServiceImplTest {\n}\n");
        git("add", "-A");
        git("commit", "-q", "-m", "评分规则调整");

        git("checkout", "-q", "master");
        git("merge", "-q", "--squash", "feat");
        git("commit", "-q", "-m", "squash 评分规则调整");
    }

    @Test
    void 改动已压缩合并进基线时标出来而不是当成没变() throws Exception {
        branchSquashedIntoMaster();

        ApiChangeAnalyzer.Report r = new ApiChangeAnalyzer().analyze("p", repo.toString(), "master", "feat", true);

        assertEquals(2, r.getChangedFiles(), "分叉点没变，三点 diff 仍会列出分支改过的文件");
        assertTrue(r.isAlreadyInBase());
        assertTrue(r.getNotes().stream().anyMatch(n -> n.contains("比不出差异")), r.getNotes().toString());
    }

    /** 单模块仓库路径从 src/test/ 起头，原来漏判成业务实现（路径里带 /service/） */
    @Test
    void 单模块仓库的单测不进其它改动() throws Exception {
        branchSquashedIntoMaster();

        ApiChangeAnalyzer.Report r = new ApiChangeAnalyzer().analyze("p", repo.toString(), "master", "feat", true);

        assertTrue(r.getOtherChanges().stream().noneMatch(c -> c.getFile().equals(TEST)),
                r.getOtherChanges().toString());
    }

    /** 接口对比分析页不开深度分析，不打「已合进基线」的标记 */
    @Test
    void 不开深度分析时不标已合并() throws Exception {
        branchSquashedIntoMaster();

        ApiChangeAnalyzer.Report r = new ApiChangeAnalyzer().analyze("p", repo.toString(), "master", "feat");

        assertFalse(r.isAlreadyInBase());
        assertTrue(r.getNotes().stream().noneMatch(n -> n.contains("比不出差异")), r.getNotes().toString());
    }

    @Test
    void 还有真实差异时不算已合并() throws Exception {
        branchSquashedIntoMaster();
        git("checkout", "-q", "feat");
        write(IMPL, "package a;\npublic class ScoreServiceImpl {\n    int score() { return 3; }\n}\n");
        git("commit", "-q", "-am", "再改一次");

        ApiChangeAnalyzer.Report r = new ApiChangeAnalyzer().analyze("p", repo.toString(), "master", "feat", true);

        assertFalse(r.isAlreadyInBase());
    }

    @Test
    void 认得出两种测试路径() {
        assertTrue(JavaApiSourceParser.isTestPath("src/test/java/a/FooTest.java"));
        assertTrue(JavaApiSourceParser.isTestPath("app-x/src/test/java/a/FooTest.java"));
        assertFalse(JavaApiSourceParser.isTestPath("src/main/java/a/test/Foo.java"));
    }
}
