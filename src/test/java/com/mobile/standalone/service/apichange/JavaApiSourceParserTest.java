package com.mobile.standalone.service.apichange;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java 接口/字段/表名解析。
 * <p>
 * 样本全部照着 data-center / global-data / app-video / appuser / event-center 五个仓库的
 * <b>真实写法</b>写，不是照 Spring 文档的标准写法——两者差得远，按文档写出来的解析器
 * 在真实代码上认不出几个。
 * <p>
 * 其中两条是拿真仓库跑出来才发现的回归，单看代码想不到：类上的 {@code @RequestMapping}
 * 会被当成接口（路径还拼成 /a/b/a/b），以及 {@code @ApiOperation} 抓到隔壁方法的说明。
 */
class JavaApiSourceParserTest {

    /** 照抄 DeadlockFinalScoreMonitorController 的结构 */
    private static final String CONTROLLER = """
            package com.steamcn.datacenter.controller;

            import org.springframework.web.bind.annotation.*;

            @RestController
            @RequestMapping("/appdatacenter/deadlock/finalscore")
            @Api(tags = "Deadlock final_score 监控")
            public class DeadlockFinalScoreMonitorController {

                @Resource
                private DeadlockResourceService deadlockResourceService;

                @GetMapping("/sample-pools")
                @ApiOperation("查询所有理论样本池 Key 及其样本数量")
                public ResponseResult<Map<String, Long>> getSamplePoolSizes() {
                    return new ResponseResult<>(null);
                }

                @PostMapping("/rebuild")
                @ApiOperation("重建样本池")
                public ResponseResult<List<DeadlockHeroStatsDTO>> rebuild(@RequestParam int mode) {
                    return new ResponseResult<>(null);
                }
            }
            """;

    // ==================== 接口 ====================

    @Test
    void 类路径与方法路径拼成完整路径() {
        List<ApiEndpoint> eps = JavaApiSourceParser.parseEndpoints(CONTROLLER);

        assertEquals(2, eps.size());
        assertEquals("GET", eps.get(0).httpMethod());
        assertEquals("/appdatacenter/deadlock/finalscore/sample-pools", eps.get(0).path());
        assertEquals("POST", eps.get(1).httpMethod());
        assertEquals("/appdatacenter/deadlock/finalscore/rebuild", eps.get(1).path());
    }

    /**
     * 类上的 @RequestMapping 不是接口。
     * 早先按注解<b>终点</b>判断，而 METHOD_MAPPING 会把整个 (...) 一起吃掉、终点更靠后，
     * 于是它被当成一个接口，路径拼成「/a/b/a/b」——五个仓库里冒出来一堆这种。
     */
    @Test
    void 类上的RequestMapping不算接口且路径不重复() {
        List<ApiEndpoint> eps = JavaApiSourceParser.parseEndpoints(CONTROLLER);

        for (ApiEndpoint e : eps) {
            assertFalse(e.path().contains("finalscore/appdatacenter"), "路径重复了：" + e.path());
        }
        assertEquals(2, eps.size(), "多出来的那个就是类注解被误认成接口");
    }

    /**
     * @ApiOperation 不能串行。早先在 ±400 字符窗口里搜，一个接口会抓到隔壁方法的说明，
     * 真实仓库里表现为「四个不同接口都叫同步绑定数据」——比没有说明更误导。
     */
    @Test
    void 每个接口取自己的中文说明不串到隔壁() {
        List<ApiEndpoint> eps = JavaApiSourceParser.parseEndpoints(CONTROLLER);

        assertEquals("查询所有理论样本池 Key 及其样本数量", eps.get(0).summary());
        assertEquals("重建样本池", eps.get(1).summary());
    }

    @Test
    void 方法上没写路径时用类路径() {
        List<ApiEndpoint> eps = JavaApiSourceParser.parseEndpoints("""
                @RestController
                @RequestMapping("/basic")
                public class C {
                    @GetMapping
                    public ResponseResult<String> ping() { return null; }
                }
                """);

        assertEquals("/basic", eps.get(0).path());
    }

    /** @RequestMapping 不写 method 其实是「全部动词」，标 ANY 比瞎猜一个 GET 诚实 */
    @Test
    void RequestMapping按method参数取动词否则标ANY() {
        List<ApiEndpoint> eps = JavaApiSourceParser.parseEndpoints("""
                @RestController
                @RequestMapping("/a")
                public class C {
                    @RequestMapping(value = "/x", method = RequestMethod.POST)
                    public ResponseResult<String> x() { return null; }
                    @RequestMapping("/y")
                    public ResponseResult<String> y() { return null; }
                }
                """);

        assertEquals("POST", eps.get(0).httpMethod());
        assertEquals("ANY", eps.get(1).httpMethod());
    }

    @Test
    void 注释掉的接口不算() {
        List<ApiEndpoint> eps = JavaApiSourceParser.parseEndpoints("""
                @RestController
                @RequestMapping("/a")
                public class C {
                    // @GetMapping("/old")
                    // public ResponseResult<String> old() { return null; }
                    /* @PostMapping("/older") */
                    @GetMapping("/live")
                    public ResponseResult<String> live() { return null; }
                }
                """);

        assertEquals(1, eps.size());
        assertEquals("/a/live", eps.get(0).path());
    }

    @Test
    void 非Controller文件不产出接口() {
        assertTrue(JavaApiSourceParser.parseEndpoints("public class Foo { }").isEmpty());
        assertTrue(JavaApiSourceParser.parseEndpoints(null).isEmpty());
    }

    // ==================== 剥 ResponseResult ====================

    /** 这几个项目统一用 ResponseResult<T> 包一层，不剥就永远找不到 DTO */
    @Test
    void 剥掉ResponseResult与容器取到真正的返回体类型() {
        assertEquals("DeadlockHeroStatsDTO",
                JavaApiSourceParser.unwrapPayloadType("ResponseResult<List<DeadlockHeroStatsDTO>>"));
        assertEquals("UserAchievementStatsVO",
                JavaApiSourceParser.unwrapPayloadType("ResponseResult<UserAchievementStatsVO>"));
        // Map 的 value 才是承载字段的那个，key 基本是 String
        assertEquals("DeadlockMatchPlayerMetrics",
                JavaApiSourceParser.unwrapPayloadType(
                        "ResponseResult<Map<String, List<DeadlockMatchPlayerMetrics>>>"));
    }

    /** 返回基本类型时没有可 diff 的字段，返回 null 让上层跳过，而不是硬报一个 String */
    @Test
    void 基本类型返回体识别为无字段() {
        assertNull(JavaApiSourceParser.unwrapPayloadType("ResponseResult<Boolean>"));
        assertNull(JavaApiSourceParser.unwrapPayloadType("ResponseResult<String>"));
        assertNull(JavaApiSourceParser.unwrapPayloadType("ResponseResult<JSONObject>"));
        assertNull(JavaApiSourceParser.unwrapPayloadType("ResponseResult<Void>"));
        assertNull(JavaApiSourceParser.unwrapPayloadType(null));
    }

    // ==================== 字段 ====================

    /** 照抄 DeadlockHeroStatsDTO：Lombok @Data + 每个字段上方一句中文 javadoc */
    private static final String DTO = """
            package com.steamcn.datacenter.vo.deadlock;

            import lombok.Data;

            /**
             * 英雄胜率统计数据DTO
             */
            @Data
            public class DeadlockHeroStatsDTO {

                /**
                 * 主键id
                 */
                private Long id;

                /**
                 * 英雄id
                 */
                private Integer heroId;

                private String heroIcon;

                @JsonFormat(pattern = "yyyy-MM-dd")
                private Date createTime;

                private static final long serialVersionUID = 1L;
            }
            """;

    /** javadoc 就是字段中文名——变更清单里能直接标「新增 winRate（胜率）」，比只给英文名有用得多 */
    @Test
    void 字段带出javadoc里的中文名() {
        List<DtoField> fields = JavaApiSourceParser.parseFields(DTO);

        assertEquals("id", fields.get(0).name());
        assertEquals("主键id", fields.get(0).comment());
        assertEquals("heroId（英雄id）", fields.get(1).display());
    }

    @Test
    void 没有javadoc的字段也要认出来() {
        List<DtoField> fields = JavaApiSourceParser.parseFields(DTO);

        DtoField icon = fields.stream().filter(f -> "heroIcon".equals(f.name())).findFirst().orElseThrow();
        assertEquals("String", icon.type());
        assertNull(icon.comment());
        assertEquals("heroIcon", icon.display());
    }

    @Test
    void 字段上有注解也能认出来() {
        List<DtoField> fields = JavaApiSourceParser.parseFields(DTO);

        assertTrue(fields.stream().anyMatch(f -> "createTime".equals(f.name())), fields.toString());
    }

    /** serialVersionUID 不是业务字段，报进变更清单纯属噪音 */
    @Test
    void serialVersionUID被排除() {
        List<DtoField> fields = JavaApiSourceParser.parseFields(DTO);

        assertFalse(fields.stream().anyMatch(f -> "serialVersionUID".equals(f.name())));
    }

    @Test
    void 泛型字段的类型完整保留() {
        List<DtoField> fields = JavaApiSourceParser.parseFields("""
                @Data
                public class A {
                    private List<Map<String, Integer>> buckets;
                }
                """);

        assertEquals("List<Map<String,Integer>>", fields.get(0).type());
    }

    // ==================== 表名 ====================

    /** 这几个项目没有 @TableName / JPA @Table，表名全写在 mapper XML 的 SQL 里 */
    @Test
    void 从mapperXML的SQL里认出表名() {
        Set<String> tables = JavaApiSourceParser.parseTables("""
                <select id="get" resultMap="M">
                    select id, name from app_account where id = #{id}
                </select>
                <insert id="add">insert into app_account (id) values (#{id})</insert>
                <update id="up">update `app_user_ext` set name = #{n}</update>
                <select id="j">select * from app_account a left join user_profile p on a.id = p.uid</select>
                """);

        assertTrue(tables.contains("app_account"), tables.toString());
        assertTrue(tables.contains("app_user_ext"), "反引号要能剥掉：" + tables);
        assertTrue(tables.contains("user_profile"), "join 的表也要认：" + tables);
    }

    /** SQL 关键字后面跟的未必是表，报一堆 set/where 会把清单淹掉 */
    @Test
    void SQL关键字不会被当成表名() {
        Set<String> tables = JavaApiSourceParser.parseTables(
                "update set where values dual from select");

        assertTrue(tables.isEmpty(), tables.toString());
    }

    @Test
    void 空内容不报表名() {
        assertTrue(JavaApiSourceParser.parseTables(null).isEmpty());
        assertTrue(JavaApiSourceParser.parseTables("   ").isEmpty());
    }

    // ==================== 文件归类 ====================

    @Test
    void 按路径与Lombok注解认出返回体文件() {
        assertTrue(JavaApiSourceParser.looksLikeDto(
                "src/main/java/com/x/vo/deadlock/DeadlockHeroStatsDTO.java", "private Long id;"));
        assertTrue(JavaApiSourceParser.looksLikeDto(
                "src/main/java/com/x/response/InvoiceInfoResponse.java", "private Long id;"));
        assertTrue(JavaApiSourceParser.looksLikeDto(
                "src/main/java/com/x/whatever/Foo.java", "@Data\npublic class Foo { private Long id; }"));
        assertFalse(JavaApiSourceParser.looksLikeDto(
                "src/main/java/com/x/util/StringUtils.java", "public static String trim(String s){return s;}"));
    }
}
