package com.mobile.standalone.service;

import com.mobile.standalone.config.ReleaseDocProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseDocSqlExtractTest {

    private ReleaseDocService svc() {
        return new ReleaseDocService(new ReleaseDocProperties());
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
}
