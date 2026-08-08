package com.mobile.standalone.service.apichange;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接口 → 表 / 缓存 key 的关联。
 * <p>
 * 存在的理由是那句每次都要问的话：「这数据不对，是逻辑错了还是缓存没刷？」
 * 知道这个接口用了哪几个 key，清一下就能一步分清，不用猜。
 * <p>
 * 反面同样要守住：常量类里混着哨兵值（{@code REDIS_NOT_HAS_VALUE = "0"}），
 * 那不是 key；把它报出来只会让人去 redis 里找一个不存在的东西。
 */
class EndpointDependencyResolverTest {

    private final Map<String, String> head = new HashMap<>();
    private final Map<String, String> base = new HashMap<>();
    private final Map<String, Set<String>> mapperTables = new HashMap<>();

    private EndpointDependencyResolver.Deps resolve(Set<String> services) {
        return EndpointDependencyResolver.resolve(
                services, head::get, base::get, m -> mapperTables.getOrDefault(m, Set.of()));
    }

    private EndpointDependencyResolver.Deps resolve(Set<String> services,
                                                    Set<String> changedTables,
                                                    Set<String> changedTypes) {
        return EndpointDependencyResolver.resolve(
                services, head::get, base::get,
                m -> mapperTables.getOrDefault(m, Set.of()), changedTables, changedTypes);
    }

    // ==================== 表 ====================

    /** 注入的是接口，SQL 写在实现类里，只看接口会一张表都找不到 */
    @Test
    void 顺着实现类里注入的Mapper找到表() {
        head.put("FollowServiceImpl", """
                @Service
                public class FollowServiceImpl implements FollowService {
                    @Autowired
                    private DeadlockUserFollowMapper followMapper;
                }
                """);
        mapperTables.put("DeadlockUserFollowMapper", Set.of("deadlock_user_follow"));

        assertEquals(List.of("deadlock_user_follow"), resolve(Set.of("FollowService")).getTables());
    }

    @Test
    void 没有Service时什么都不返回() {
        assertTrue(resolve(Set.of()).getTables().isEmpty());
        assertTrue(resolve(null).getRedisKeys().isEmpty());
    }

    /**
     * 一个接口连着十几张表时，人要的是「这次动了哪几张」，
     * 而不是「它历史上碰过哪些」——后者等于没有结论。
     */
    @Test
    void 本次变更涉及的表排在前面() {
        head.put("SvcImpl", """
                @Service
                public class SvcImpl {
                    @Autowired private AMapper a;
                    @Autowired private BMapper b;
                }
                """);
        mapperTables.put("AMapper", Set.of("t_old"));
        mapperTables.put("BMapper", Set.of("t_new"));

        EndpointDependencyResolver.Deps d = resolve(Set.of("Svc"), Set.of("t_new"), Set.of());

        assertEquals("t_new", d.getTables().get(0));
        assertEquals(1, d.getRelatedTableCount());
    }

    // ==================== 缓存 key ====================

    @Test
    void 找到接口用到的缓存key及其字面值() {
        head.put("SvcImpl", """
                @Service
                public class SvcImpl {
                    void go() { redisTemplate.opsForValue().get(RedisConstants.USER_CACHE); }
                }
                """);
        head.put("RedisConstants", """
                public class RedisConstants {
                    public static final String USER_CACHE = "user_cache_prefix";
                }
                """);

        List<EndpointDependencyResolver.RedisKeyRef> keys = resolve(Set.of("Svc")).getRedisKeys();

        assertEquals(1, keys.size());
        assertEquals("USER_CACHE", keys.get(0).getConstant());
        assertEquals("user_cache_prefix", keys.get(0).getValue());
    }

    /** key 的字面值变了：老缓存整批失效，第一次访问会全量回源 */
    @Test
    void key的值变了要标出来并带上旧值() {
        head.put("SvcImpl", "@Service class SvcImpl { void g(){ RedisConstants.C; } }");
        head.put("RedisConstants", "class RedisConstants { static final String C = \"new_key\"; }");
        base.put("RedisConstants", "class RedisConstants { static final String C = \"old_key\"; }");

        EndpointDependencyResolver.RedisKeyRef k = resolve(Set.of("Svc")).getRedisKeys().get(0);

        assertTrue(k.isValueChanged());
        assertEquals("old_key", k.getOldValue());
        assertTrue(k.isRelated(), "key 变了必然与本次相关");
    }

    /** 哨兵值不是 key，报出来会让人去 redis 找一个不存在的东西 */
    @Test
    void 哨兵值不算缓存key() {
        assertFalse(EndpointDependencyResolver.looksLikeKey("0"));
        assertFalse(EndpointDependencyResolver.looksLikeKey("1"));
        assertFalse(EndpointDependencyResolver.looksLikeKey("12345"));
        assertFalse(EndpointDependencyResolver.looksLikeKey(null));
        assertTrue(EndpointDependencyResolver.looksLikeKey("user_cache"));
        assertTrue(EndpointDependencyResolver.looksLikeKey("deadlock:robot:cache:"));
    }

    /** 只认缓存相关的常量类，否则所有业务枚举都会被当成 key */
    @Test
    void 非缓存常量类的引用不算key() {
        head.put("SvcImpl", "@Service class SvcImpl { void g(){ MessageConstants.PARAM_ERROR; } }");
        head.put("MessageConstants",
                "class MessageConstants { static final String PARAM_ERROR = \"param_error\"; }");

        assertTrue(resolve(Set.of("Svc")).getRedisKeys().isEmpty());
    }

    @Test
    void 同一个key引用多次只报一条() {
        head.put("SvcImpl", """
                @Service class SvcImpl {
                    void a(){ RedisConstants.C; }
                    void b(){ RedisConstants.C; }
                }
                """);
        head.put("RedisConstants", "class RedisConstants { static final String C = \"the_key\"; }");

        assertEquals(1, resolve(Set.of("Svc")).getRedisKeys().size());
    }

    @Test
    void 常量取值() {
        String src = "class C { static final String A = \"x\"; static final String B = \"y\"; }";

        assertEquals("y", EndpointDependencyResolver.constantValue(src, "B"));
        assertEquals(null, EndpointDependencyResolver.constantValue(src, "NOPE"));
        assertEquals(null, EndpointDependencyResolver.constantValue(null, "A"));
    }
}
