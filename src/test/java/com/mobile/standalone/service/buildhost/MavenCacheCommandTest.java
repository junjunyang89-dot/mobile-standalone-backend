package com.mobile.standalone.service.buildhost;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenCacheCommandTest {

    @Test
    void 坐标只能是字母数字和点划线() {
        assertThrows(IllegalArgumentException.class, () -> MavenCacheCommand.requireToken("../etc", "version"));
        assertThrows(IllegalArgumentException.class, () -> MavenCacheCommand.requireToken("1.0;rm -rf", "version"));
        assertEquals("1.0.74-SNAPSHOT", MavenCacheCommand.requireToken("1.0.74-SNAPSHOT", "version"));
        assertEquals("com/steamcn", MavenCacheCommand.groupPath("com.steamcn"));
    }

    @Test
    void 检查脚本带上坐标且不含分号注入() {
        String sh = MavenCacheCommand.inspectScript("", "", "com.steamcn", "appuser-api", "1.0.74-SNAPSHOT");
        assertTrue(sh.contains("com/steamcn/appuser-api/1.0.74-SNAPSHOT"));
        assertFalse(sh.contains("rm -rf"));
        assertThrows(IllegalArgumentException.class,
                () -> MavenCacheCommand.inspectScript("", "", "com.steamcn", "appuser-api", "1.0 && reboot"));
    }

    @Test
    void 解析ls的iso时间() {
        String out = """
                REPO=/root/.m2/repository
                DIR=/root/.m2/repository/com/steamcn/appuser-api/1.0.74-SNAPSHOT
                BEGIN_JARS
                -rw-r--r-- 1 root root 12345 2026-04-08 08:47 appuser-api-1.0.74-SNAPSHOT.jar
                -rw-r--r-- 1 root root  1000 2026-04-08 08:47 appuser-api-1.0.74-SNAPSHOT-sources.jar
                END_JARS
                """;
        List<MavenCacheCommand.JarRow> jars = MavenCacheCommand.parseJars(out);
        assertEquals(1, jars.size());
        assertEquals("appuser-api-1.0.74-SNAPSHOT.jar", jars.get(0).name());
        assertEquals("2026-04-08", jars.get(0).date());
        assertEquals("08:47", jars.get(0).time());
    }

    @Test
    void ssh必须是密钥模式() {
        List<String> argv = MavenCacheCommand.sshArgv("yangjun", "192.168.10.5", 22, "", "echo ok");
        assertEquals("ssh", argv.get(0));
        assertTrue(argv.contains("BatchMode=yes"));
        assertTrue(argv.contains("yangjun@192.168.10.5"));
        assertFalse(argv.contains("-i"));
    }
}
