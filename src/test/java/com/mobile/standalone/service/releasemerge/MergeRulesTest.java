package com.mobile.standalone.service.releasemerge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergeRulesTest {

    @Test
    void parsesProjectBranchLines() {
        List<MergeRules.Line> ls = MergeRules.parseLines("""
                1. app-comment-service：feature/comment-score，顺序发版
                - app-swift: release/2026-10-06
                app-user\tfix/login
                只有一个词
                app-x: bad..branch
                """);
        assertEquals(5, ls.size());
        assertEquals("app-comment-service", ls.get(0).project());
        assertEquals("feature/comment-score", ls.get(0).branch());
        assertEquals("app-swift", ls.get(1).project());
        assertEquals("release/2026-10-06", ls.get(1).branch());
        assertEquals("app-user", ls.get(2).project());
        assertEquals("fix/login", ls.get(2).branch());
        assertTrue(ls.get(3).error() != null);
        assertNull(ls.get(4).branch());
        assertTrue(ls.get(4).error().contains("分支名不对"));
    }

    @Test
    void splitsProjectsSharingOneBranch() {
        List<MergeRules.Line> ls = MergeRules.parseLines("app-community、data-center：dyl/2026-10-06/APP-1");
        assertEquals(2, ls.size());
        assertEquals("app-community", ls.get(0).project());
        assertEquals("data-center", ls.get(1).project());
        assertEquals("dyl/2026-10-06/APP-1", ls.get(1).branch());
    }

    @Test
    void rejectsOptionLikeBranch() {
        assertFalse(MergeRules.validBranch("--upload-pack=x"));
        assertFalse(MergeRules.validBranch("a b"));
        assertTrue(MergeRules.validBranch("agent/2026-09-17/comment-score"));
    }

    @Test
    void findsBranchesMergedIntoTheBranch() {
        List<String> got = MergeRules.mergedInBranches(List.of(
                "Merge branch 'master' into feature/a",
                "Merge remote-tracking branch 'origin/master' into feature/a",
                "Merge branch 'feature/b' into 'feature/a'",
                "Merge branch 'test' of gitlab.x.com:grp/app into feature/a",
                "Merge branch 'feature/a' into test"), "master", "feature/a");
        assertEquals(List.of("feature/b", "test"), got);
    }

    @Test
    void classifiesSqlAndConfigFiles() {
        MergeRules.Files f = MergeRules.classify(List.of(
                "A\tsrc/main/resources/db/migration/V12__score.sql",
                "M\tsrc/main/resources/application-prod.yml",
                "M\tsrc/test/resources/application.yml",
                "D\told.sql",
                "M\tsrc/main/java/A.java"));
        assertEquals(1, f.added());
        assertEquals(3, f.modified());
        assertEquals(1, f.deleted());
        assertEquals(List.of("src/main/resources/db/migration/V12__score.sql"), f.sql());
        assertEquals(List.of("src/main/resources/application-prod.yml"), f.config());
    }

    @Test
    void flagsRiskySql() {
        assertEquals(List.of(), MergeRules.riskySql("""
                CREATE TABLE t (id BIGINT NOT NULL, name VARCHAR(20) NOT NULL);
                ALTER TABLE t ADD COLUMN score INT NOT NULL DEFAULT 0;
                ALTER TABLE t ADD INDEX idx_s (score);
                UPDATE t SET score = 1 WHERE id = 3;
                -- DROP TABLE t;
                """));
        List<String> r = MergeRules.riskySql("""
                ALTER TABLE t DROP COLUMN old_name;
                ALTER TABLE t ADD COLUMN must INT NOT NULL;
                DELETE FROM t;
                """);
        assertTrue(r.contains("删列 DROP COLUMN"));
        assertTrue(r.contains("加了不带默认值的非空列"));
        assertTrue(r.contains("没带 WHERE 的 DELETE / UPDATE"));
        assertFalse(MergeRules.riskySql("ALTER TABLE t DROP INDEX idx_a;").contains("删列 DROP COLUMN"));
    }

    @Test
    void findsNewConfigKeysInAddedLines() {
        List<String> keys = MergeRules.newConfigKeys(List.of(
                "+    @Value(\"${comment.score.weights:1,2}\")",
                "-    @Value(\"${comment.old}\")",
                "+@ConfigurationProperties(prefix = \"comment.rank\")",
                "+++ b/A.java"));
        assertEquals(List.of("comment.score.weights", "comment.rank.*"), keys);
    }

    @Test
    void derivesGitLabApiBaseAndPath() {
        assertEquals("https://gitlab.x.com", MergeRules.apiBase("git@gitlab.x.com:grp/app-user.git", null));
        assertEquals("http://gitlab.x.com:8080", MergeRules.apiBase("http://gitlab.x.com:8080/grp/app-user.git", null));
        assertEquals("https://gitlab.x.com", MergeRules.apiBase("ssh://git@gitlab.x.com:2222/grp/app.git", null));
        assertEquals("https://g.x.com", MergeRules.apiBase("git@a:b/c.git", "https://g.x.com/api/v4/"));
        assertEquals("grp/sub/app-user", MergeRules.projectPath("git@gitlab.x.com:grp/sub/app-user.git"));
        assertEquals("grp/app", MergeRules.projectPath("ssh://git@gitlab.x.com:2222/grp/app.git"));
        assertEquals("grp/app", MergeRules.projectPath("https://u@gitlab.x.com/grp/app/"));
    }
}
