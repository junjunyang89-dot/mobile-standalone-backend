package com.mobile.standalone.service.releasemerge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MasterRepoTest {

    @TempDir
    Path tmp;

    @Test
    void checksBranchesAgainstMasterWithoutTouchingThem() throws Exception {
        Path remote = tmp.resolve("grp/app-demo.git");
        Files.createDirectories(remote);
        git(remote, "init", "--bare", "-b", "master");
        Path work = tmp.resolve("work");
        git(tmp, "clone", remote.toString(), work.toString());
        Files.writeString(work.resolve("a.txt"), "one\n");
        commit(work, "init");
        git(work, "push", "origin", "master");

        git(work, "checkout", "-b", "feature/ok");
        Files.writeString(work.resolve("b.sql"), "ALTER TABLE t DROP COLUMN x;\n");
        commit(work, "add sql");
        git(work, "push", "origin", "feature/ok");

        git(work, "checkout", "master");
        git(work, "checkout", "-b", "feature/conflict");
        Files.writeString(work.resolve("a.txt"), "branch\n");
        commit(work, "branch edit");
        git(work, "push", "origin", "feature/conflict");
        git(work, "checkout", "master");
        Files.writeString(work.resolve("a.txt"), "master\n");
        commit(work, "master edit");
        git(work, "push", "origin", "master");

        Path root = tmp.resolve("release-repos");
        String url = "file://" + remote;
        Path dir = MasterRepo.ensure(root, url, "master");
        assertEquals(root.resolve("app-demo"), dir);
        assertEquals(dir, MasterRepo.ensure(root, url, "master"));

        assertFalse(MasterRepo.fetchBranch(dir, "feature/nope"));

        assertTrue(MasterRepo.fetchBranch(dir, "feature/ok"));
        assertFalse(MasterRepo.isAncestor(dir, "origin/feature/ok", "origin/master"));
        assertEquals(1, MasterRepo.count(dir, "origin/master..origin/feature/ok"));
        assertNull(MasterRepo.conflicts(dir, "origin/master", "origin/feature/ok"));
        MergeRules.Files f = MergeRules.classify(MasterRepo.nameStatus(dir, "origin/master", "origin/feature/ok"));
        assertEquals(List.of("b.sql"), f.sql());
        assertTrue(MergeRules.riskySql(MasterRepo.show(dir, "origin/feature/ok", "b.sql", 1000)).contains("删列 DROP COLUMN"));

        assertTrue(MasterRepo.fetchBranch(dir, "feature/conflict"));
        List<String> c = MasterRepo.conflicts(dir, "origin/master", "origin/feature/conflict");
        assertNotNull(c);
        assertEquals(List.of("a.txt"), c);

        assertEquals("master", git(dir, "rev-parse", "--abbrev-ref", "HEAD").trim());
    }

    private static void commit(Path dir, String msg) throws Exception {
        git(dir, "add", "-A");
        git(dir, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", msg);
    }

    private static String git(Path dir, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        MasterRepo.Out o = MasterRepo.run(dir, 60, cmd);
        if (!o.ok()) {
            throw new IllegalStateException(String.join(" ", cmd) + "：" + o.text());
        }
        return o.text();
    }
}
