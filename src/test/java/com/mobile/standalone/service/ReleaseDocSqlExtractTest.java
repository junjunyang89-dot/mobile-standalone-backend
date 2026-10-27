package com.mobile.standalone.service;

import com.mobile.standalone.config.ReleaseDocProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseDocSqlExtractTest {

    private ReleaseDocService svc() {
        ReleaseDocProperties props = new ReleaseDocProperties();
        props.setBranchKeywords(List.of(
                "comment-service", "commentservice",
                "steam-admin", "app-admin", "appadmin", "admin",
                "app-swift", "app-engine", "event-center", "robot"));
        return new ReleaseDocService(props);
    }

    @Test
    void jiraCodeBlockWithHeader() {
        String text = """
                app_admin库下执行：
                {code:java}
                CREATE TABLE IF NOT EXISTS deadlock_hero_rank_daily (
                    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
                    PRIMARY KEY (id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                ALTER TABLE deadlock_hero_rank_stats
                    ADD COLUMN mode INT NOT NULL DEFAULT 1 COMMENT '1标准 2街斗' AFTER `rank`;
                ALTER TABLE deadlock_hero_rank_stats DROP INDEX uni_idx;
                ALTER TABLE deadlock_hero_rank_stats
                    ADD UNIQUE KEY uk_hero_rank_mode_days (hero_id, `rank`, mode, recent_days);
                {code}
                """;
        List<String> blocks = svc().extractSqlBlocks(text);
        assertFalse(blocks.isEmpty());
        String all = String.join("\n", blocks);
        assertTrue(all.contains("app_admin库下执行"));
        assertTrue(all.contains("CREATE TABLE IF NOT EXISTS deadlock_hero_rank_daily"));
        assertTrue(all.contains("ADD UNIQUE KEY uk_hero_rank_mode_days"));
        assertTrue(all.contains("DROP INDEX uni_idx"));
        assertFalse(all.trim().equals("CREATE TABLE IF NOT EXISTS deadlock_hero_rank_daily ("));
    }

    @Test
    void plainSqlAfterHint() {
        String text = """
                实际是app_admin库下执行：

                CREATE TABLE IF NOT EXISTS deadlock_hero_rank_daily (
                    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
                    PRIMARY KEY (id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='英雄段位日聚合';
                ALTER TABLE deadlock_hero_rank_stats
                    ADD COLUMN mode INT NOT NULL DEFAULT 1 COMMENT '1标准 2街斗' AFTER `rank`,
                    ADD COLUMN recent_days INT NOT NULL DEFAULT 7 COMMENT '统计窗口天数 7/15/30' AFTER mode;
                ALTER TABLE deadlock_hero_rank_stats DROP INDEX uni_idx;
                ALTER TABLE deadlock_hero_rank_stats
                    ADD UNIQUE KEY uk_hero_rank_mode_days (hero_id, `rank`, mode, recent_days);
                """;
        List<String> blocks = svc().extractSqlBlocks(text);
        assertFalse(blocks.isEmpty());
        String all = String.join("\n", blocks);
        assertTrue(all.contains("CREATE TABLE IF NOT EXISTS deadlock_hero_rank_daily"));
        assertTrue(all.contains("uk_hero_rank_mode_days"));
        assertTrue(all.contains("app_admin库下执行"));
        assertTrue(all.split("\\R").length > 5);
    }

    /** APP-9066：标题写「库sql」不是「库下执行」，且 INSERT…SELECT…FROM DUAL 多行 */
    @Test
    void appAdminKuSql_insertSelectFromDual() {
        String text = """
                app-admin库sql：

                INSERT INTO app_information_center_config (id, info_title, bg_pic_url, deleted, gmt_create, gmt_modified)
                SELECT 2, 'DOTA2新手攻略', '', 0, NOW(), NOW()
                FROM DUAL
                WHERE NOT EXISTS (SELECT 1 FROM app_information_center_config WHERE id = 2);

                INSERT INTO app_information_center_config (id, info_title, bg_pic_url, deleted, gmt_create, gmt_modified)
                SELECT 3, 'DEADLOCK新手攻略', '', 0, NOW(), NOW()
                FROM DUAL
                WHERE NOT EXISTS (SELECT 1 FROM app_information_center_config WHERE id = 3);
                """;
        assertTrue(ReleaseDocService.isSqlDbHint("app-admin库sql："));
        List<String> blocks = svc().extractSqlBlocks(text);
        assertEquals(1, blocks.size(), blocks.toString());
        String all = blocks.get(0);
        assertTrue(all.startsWith("app-admin库sql"), all);
        assertTrue(all.contains("DOTA2新手攻略"), all);
        assertTrue(all.contains("DEADLOCK新手攻略"), all);
        assertTrue(all.contains("FROM DUAL"), all);
        assertTrue(all.contains("WHERE NOT EXISTS"), all);
        assertTrue(all.contains("id = 2"), all);
        assertTrue(all.contains("id = 3"), all);

        var parsed = svc().parseComment(text);
        assertEquals(all, parsed.db);
    }

    /** APP-9135：直接贴 ALTER，不写「库下执行」；库名在 `steamcn_comment`.`comment_score` 里 */
    @Test
    void app9135_bareAlterWithSchema() {
        String text = """
                comment-service、app-admin：agent/2026-09-17/comment-score 按顺序发版

                ALTER TABLE `steamcn_comment`.`comment_score` ADD COLUMN `score_detail` VARCHAR(512) DEFAULT '' NULL COMMENT '得分快照明细' AFTER `new_score`;
                """;
        assertEquals("steamcn_comment", ReleaseDocService.schemaOfSql(
                "ALTER TABLE `steamcn_comment`.`comment_score` ADD COLUMN `score_detail` VARCHAR(512)"));
        List<String> blocks = svc().extractSqlBlocks(text);
        assertFalse(blocks.isEmpty(), blocks.toString());
        String all = String.join("\n", blocks);
        assertTrue(all.contains("steamcn_comment库"), all);
        assertTrue(all.contains("score_detail"), all);
        assertTrue(all.contains("得分快照明细"), all);

        var parsed = svc().parseComment(text);
        assertTrue(parsed.db.contains("score_detail"), parsed.db);
        assertTrue(parsed.db.contains("steamcn_comment"), parsed.db);
        assertTrue(parsed.branches.contains("comment-service: agent/2026-09-17/comment-score"), parsed.branches);
        assertTrue(parsed.branches.contains("app-admin: agent/2026-09-17/comment-score"), parsed.branches);
        assertFalse(parsed.config.contains("ALTER"), parsed.config);
        // SQL 里已有 `steamcn_comment`.`comment_score`，库名明确，不必红字提醒
        assertFalse(parsed.remarks.contains("SQL缺少库名"), parsed.remarks);
    }

    @Test
    void 写了库sql标题就不提醒缺库名() {
        var parsed = svc().parseComment("""
                app-admin库sql：
                INSERT INTO t (id) VALUES (1);
                """);
        assertTrue(parsed.db.contains("INSERT INTO"), parsed.db);
        assertFalse(parsed.remarks.contains("SQL缺少库名"), parsed.remarks);
    }

    /** 只有表名、无库名标题也无 `库.表` → 才红字提醒 */
    @Test
    void bareAlterOnlyTable_提醒缺库名() {
        var parsed = svc().parseComment("""
                ALTER TABLE `comment_score` ADD COLUMN `score_detail` VARCHAR(512) DEFAULT '' NULL;
                """);
        assertTrue(parsed.db.contains("comment_score"), parsed.db);
        assertTrue(parsed.remarks.contains(ReleaseDocService.REMARK_SQL_MISSING_DB), parsed.remarks);
    }

    /** APP-9066 原文：库 SQL + app-admin 分支 + swift 分支；库名行不能抢走分支 */
    @Test
    void app9066_branchAndSqlTogether() {
        String text = """
                app-admin库sql：

                INSERT INTO app_information_center_config (id, info_title, bg_pic_url, deleted, gmt_create, gmt_modified)
                SELECT 2, 'DOTA2新手攻略', '', 0, NOW(), NOW()
                FROM DUAL
                WHERE NOT EXISTS (SELECT 1 FROM app_information_center_config WHERE id = 2);

                INSERT INTO app_information_center_config (id, info_title, bg_pic_url, deleted, gmt_create, gmt_modified)
                SELECT 3, 'DEADLOCK新手攻略', '', 0, NOW(), NOW()
                FROM DUAL
                WHERE NOT EXISTS (SELECT 1 FROM app_information_center_config WHERE id = 3);

                app-admin：agent/2026-09-15/dota-deadlock-introduction
                {color:red}AI写的 多测测{color}

                app-swift：rjy/APP-9066/9-17/noviceStrategy
                """;
        var parsed = svc().parseComment(text);
        assertTrue(parsed.branches.contains("app-admin: agent/2026-09-15/dota-deadlock-introduction"), parsed.branches);
        assertTrue(parsed.branches.contains("app-swift: rjy/APP-9066/9-17/noviceStrategy"), parsed.branches);
        assertTrue(parsed.db.contains("DOTA2新手攻略") && parsed.db.contains("DEADLOCK新手攻略"), parsed.db);
        assertFalse(parsed.config.contains("INSERT INTO"), parsed.config);
        assertFalse(parsed.config.contains("WHERE NOT EXISTS"), parsed.config);
    }
}
