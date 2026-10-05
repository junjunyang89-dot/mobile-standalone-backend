package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 按分支读源码：读到的必须是那个提交的内容，本地没提交的改动、当前切在哪个分支都不能影响结果。
 */
class GitSnapshotServiceTest {

    @TempDir
    Path tmp;

    private static void git(Path repo, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@t",
                "-c", "commit.gpgsign=false", "-C", repo.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(out).isZero();
    }

    private Path repoWithTwoBranches() throws Exception {
        Path repo = tmp.resolve("app-demo");
        Files.createDirectories(repo);
        git(repo, "init", "-q", "-b", "master");
        Files.writeString(repo.resolve("A.java"), "master-version");
        git(repo, "add", ".");
        git(repo, "commit", "-q", "-m", "m1");
        git(repo, "checkout", "-q", "-b", "testing");
        Files.writeString(repo.resolve("A.java"), "testing-version");
        Files.writeString(repo.resolve("B.java"), "only-on-testing");
        git(repo, "add", ".");
        git(repo, "commit", "-q", "-m", "t1");
        // 停在 testing 上，还带着没提交的改动——平时本地仓库就是这样
        Files.writeString(repo.resolve("A.java"), "dirty-local-edit");
        return repo;
    }

    @Test
    void 读的是分支上的提交_不是工作区() throws Exception {
        Path repo = repoWithTwoBranches();
        var svc = new GitSnapshotService(tmp.resolve("snapshots"));

        var master = svc.snapshot(repo, "master", false, false);
        assertThat(Files.readString(master.dir().resolve("A.java"))).isEqualTo("master-version");
        assertThat(master.dir().resolve("B.java")).doesNotExist();
        assertThat(master.sha()).hasSize(40);
        assertThat(master.committed()).isNotNull();

        var testing = svc.snapshot(repo, "testing", false, false);
        assertThat(Files.readString(testing.dir().resolve("A.java"))).isEqualTo("testing-version");
        assertThat(testing.dir().resolve("B.java")).exists();

        // 工作区原样不动
        assertThat(Files.readString(repo.resolve("A.java"))).isEqualTo("dirty-local-edit");
    }

    @Test
    void 没有远程时基线退到本地同名分支() throws Exception {
        Path repo = repoWithTwoBranches();
        var svc = new GitSnapshotService(tmp.resolve("snapshots"));
        assertThat(svc.baseRef(repo, null)).isEqualTo("master");
        assertThat(svc.baseRef(repo, "origin/release")).isEqualTo("origin/release");
    }

    @Test
    void 分支不存在给人看得懂的错() throws Exception {
        Path repo = repoWithTwoBranches();
        var svc = new GitSnapshotService(tmp.resolve("snapshots"));
        assertThatThrownBy(() -> svc.snapshot(repo, "origin/nope", false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("找不到分支");
        assertThat(svc.resolve(repo, "--upload-pack=x")).isNull();
    }

    @Test
    void 同一个提交复用() throws Exception {
        Path repo = repoWithTwoBranches();
        var svc = new GitSnapshotService(tmp.resolve("snapshots"));
        var a = svc.snapshot(repo, "master", false, false);
        Files.writeString(a.dir().resolve("marker"), "x");
        var b = svc.snapshot(repo, "master", false, false);
        assertThat(b.dir()).isEqualTo(a.dir());
        assertThat(b.dir().resolve("marker")).exists();
    }
}
