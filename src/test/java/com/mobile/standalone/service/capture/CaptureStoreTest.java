package com.mobile.standalone.service.capture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CaptureStoreTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private CaptureStore store;

    private Path useTempRoot() throws Exception {
        Path root = Files.createTempDirectory("capture-store-test");
        ReflectionTestUtils.setField(store, "storeDirRoot", root.toString());
        return root;
    }

    /**
     * sessionId 会拼进文件路径。真正要保证的性质是「结果落在根目录之内」，
     * 而不是「不含某些字符」——所以直接按这个性质断言。
     */
    @Test
    void safe_keepsPathInsideRoot() {
        Path root = Path.of("/var/captures");
        for (String evil : List.of("../../etc/passwd", "..", ".", "...", "a/../../b", "")) {
            // 比字符串而不是用 Path.startsWith：后者会调 toRealPath()，要求文件真实存在，
            // 而这里要验的是词法层面的性质，跟磁盘上有没有这个目录无关
            String resolved = root.resolve(CaptureStore.safe(evil)).normalize().toString();
            assertThat(resolved).as("输入 %s 不该跑出根目录", evil)
                    .startsWith(root.toString() + "/");
        }
        assertThat(CaptureStore.safe("cap-123")).isEqualTo("cap-123");
        assertThat(CaptureStore.safe(null)).isEqualTo("unknown");
    }

    /** 落盘在稳定目录而不是 /tmp——/tmp 会被系统清理，那正是要避免的 */
    @Test
    void flowFileOf_createsDirectoryUnderConfiguredRoot() throws Exception {
        Path root = useTempRoot();

        Path f = store.flowFileOf("cap-1");

        assertThat(f.getParent()).isDirectory().hasParent(root);
        assertThat(f.getFileName().toString()).isEqualTo("flows.jsonl");
    }

    /**
     * 抓包进程是后端的子进程，后端一没了它也没了。不对账的话库里会永远躺着一场
     * 「进行中」，而 start 见到进行中的就拒绝开新的——一次异常退出就让功能报废。
     */
    @Test
    void reconcileOnBoot_marksLeftoverRunningSessionsAborted() throws Exception {
        useTempRoot();
        when(jdbcTemplate.update(contains("capture_sessions"), anyString(), anyString())).thenReturn(2);

        store.reconcileOnBoot();

        verify(jdbcTemplate).update(contains("status = ?"),
                eq(CaptureStore.STATUS_ABORTED), eq(CaptureStore.STATUS_RUNNING));
    }

    /** 登记失败不该让抓包起不来：抓包本身有用，登记只是为了以后能翻出来 */
    @Test
    void open_swallowsRegistrationFailure() throws Exception {
        Path root = useTempRoot();
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenThrow(new RuntimeException("库挂了"));

        store.open("cap-1", "dev", "ANDROID", "APP-8999", null, "a.com", root);
        // 没有抛出即通过
    }

    @Test
    void list_clampsLimitAndFiltersByRequirement() throws Exception {
        useTempRoot();
        when(jdbcTemplate.queryForList(anyString(), anyString())).thenReturn(List.of(Map.of("id", "cap-1")));

        assertThat(store.list("APP-8999", 10)).hasSize(1);
        verify(jdbcTemplate).queryForList(contains("requirement_no = ?"), eq("APP-8999"));
    }

    /** 关了清理就不该动任何文件 */
    @Test
    void cleanup_doesNothingWhenDisabled() throws Exception {
        useTempRoot();
        ReflectionTestUtils.setField(store, "cleanupEnabled", false);

        store.cleanup();

        verify(jdbcTemplate, never()).queryForList(anyString(), any(Object[].class));
    }

    /**
     * 清理只删文件、留元数据：「几月几号抓过一场、多少条」这种记录很小，
     * 留着能回答「那次到底抓没抓」，占盘的是响应体。
     */
    @Test
    void cleanup_removesFilesButKeepsSessionRows() throws Exception {
        useTempRoot();
        ReflectionTestUtils.setField(store, "cleanupEnabled", true);
        ReflectionTestUtils.setField(store, "retainDays", 30);

        Path old = Files.createTempDirectory("capture-old");
        Files.writeString(old.resolve("flows.jsonl"), "{}");
        when(jdbcTemplate.queryForList(contains("started_at <"), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", "cap-old", "store_dir", old.toString())));

        store.cleanup();

        assertThat(old).doesNotExist();
        // 元数据行不删，只把 store_dir 置空
        verify(jdbcTemplate).update(contains("SET store_dir = NULL"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("DELETE FROM capture_sessions"), any(Object[].class));
    }
}
