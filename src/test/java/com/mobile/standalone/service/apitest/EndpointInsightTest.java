package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointInsightTest {

    @TempDir
    Path root;

    private static final String PKG = "src/main/java/com/demo/";

    private void write(String rel, String content) throws Exception {
        Path p = root.resolve(PKG + rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @BeforeEach
    void repo() throws Exception {
        write("response/ResponseResult.java", """
                package com.demo.response;
                @Data
                public class ResponseResult<E> {
                    private int code;
                    private String message;
                    private E result;
                }
                """);
        write("enumeration/MessageCodeEnum.java", """
                package com.demo.enumeration;
                public enum MessageCodeEnum {
                    NEW_SUCCESS(0, "success"),
                    SUCCESS(1, "success"),
                    PARAM_ERROR(10001, "参数错误"),
                    INVALID_TOKEN_ERROR(1008, "登录失效，请重新登录"),
                    SYSTEM_BUSY(99999, "系统繁忙"),
                    MEMBER_NOT_EXIST(30001, "会员不存在"),
                    ACTIVATE_CARD_SUCCESS(90055, "激活成功"),
                    SERVICE_CALL_CODE_SUCCESS_ERROR(5009001, "服务调用失败");
                    private final int code;
                    private final String message;
                    MessageCodeEnum(int code, String message) { this.code = code; this.message = message; }
                }
                """);
        write("dto/VipNumSummaryDTO.java", """
                package com.demo.dto;
                @Data
                public class VipNumSummaryDTO {
                    /**
                     * 会员完美时刻总数量
                     */
                    private Integer videoNum;
                    /** 会员优惠券 */
                    private VipBoxSummaryDTO vipBoxSummaryDTO;
                    @ApiModelProperty("待领取改名卡数量")
                    private Integer renameCardNum;
                    // 是否有临期优惠券
                    private Boolean hasExpiringCoupons;
                    private List<CouponItem> coupons;
                    private static final long serialVersionUID = 1L;
                }
                """);
        write("dto/VipBoxSummaryDTO.java", """
                package com.demo.dto;
                @Data
                public class VipBoxSummaryDTO {
                    /** 箱子总数 */
                    private Integer totalBoxCnt;
                    private Boolean expire;
                }
                """);
        write("dto/CouponItem.java", """
                package com.demo.dto;
                @Data
                public class CouponItem extends BaseItem {
                    private String name;
                    private CouponType type;
                    public static class Inner {
                        private String shouldNotLeak;
                    }
                }
                """);
        write("dto/BaseItem.java", """
                package com.demo.dto;
                @Data
                public class BaseItem {
                    /** 主键 */
                    private Long id;
                }
                """);
        write("dto/CouponType.java", "package com.demo.dto;\npublic enum CouponType { A, B }\n");
        write("enumeration/VideoCountsCategoryEnum.java", """
                package com.demo.enumeration;
                public enum VideoCountsCategoryEnum {
                    GF_COUNTS(1, "国服完美时刻次数");
                }
                """);
        write("controller/MemberController.java", """
                package com.demo.controller;
                @RestController
                @RequestMapping("/member")
                public class MemberController extends BaseController {
                    @Resource
                    private MemberService memberService;

                    @GetMapping("/memberInfo")
                    @NeedUserLogin(byRedis = true)
                    public ResponseResult<String> memberInfo() {
                        return this.getResponseResult(MessageCodeEnum.SUCCESS);
                    }

                    @GetMapping("/memberNumSummary")
                    @ApiOperation("会员权益数量总览")
                //    @NeedUserLogin
                    public ResponseResult<VipNumSummaryDTO> memberNumSummary() {
                        ResponseResult<VipNumSummaryDTO> responseResult = this.getResponseResult(MessageCodeEnum.SUCCESS);
                        VipNumSummaryDTO dto = memberService.memberNumSummary();
                        responseResult.setResult(dto);
                        return responseResult;
                    }

                    @Deprecated
                    @GetMapping("/old")
                    @NeedUserLogin(strict = false)
                    public ResponseResult<VipNumSummaryDTO> old() {
                        if (memberService.missing()) {
                            throw new AppEngineException(MessageCodeEnum.MEMBER_NOT_EXIST);
                        }
                        return this.getResponseResult(MessageCodeEnum.NEW_SUCCESS);
                    }
                }
                """);
        write("service/MemberService.java", """
                package com.demo.service;
                public interface MemberService {
                    VipNumSummaryDTO memberNumSummary();
                    boolean missing();
                }
                """);
        write("service/impl/MemberServiceImpl.java", """
                package com.demo.service.impl;
                @Service
                public class MemberServiceImpl implements MemberService {
                    @Override
                    public VipNumSummaryDTO memberNumSummary() {
                        if (degradeSwitch) {
                            return new VipNumSummaryDTO();
                        }
                        TokenDetailResponse tokenDetail = getTokenDetailResponse();
                        if (tokenDetail == null){
                            return new VipNumSummaryDTO();
                        }
                        VipNumSummaryDTO vipNumSummaryDTO = new VipNumSummaryDTO();
                        Long steamId = tokenDetail.getSteamId();
                        remote.count(steamId, VideoCountsCategoryEnum.GF_COUNTS);
                        if (CommonUtils.isEmpty(steamId)) {
                            return vipNumSummaryDTO;
                        }
                        if (tokenDetail.getUserId() < 0) {
                            throw new AppEngineException(MessageCodeEnum.PARAM_ERROR);
                        }
                        vipNumSummaryDTO.setVideoNum(1);
                        return vipNumSummaryDTO;
                    }

                    @Override
                    public boolean missing() {
                        return false;
                    }
                }
                """);
        write("aop/NeedUserLoginAspect.java", """
                package com.demo.aop;
                @Aspect
                public class NeedUserLoginAspect {
                    @Around("@annotation(needUserLogin)")
                    public Object needUserLogin(ProceedingJoinPoint jp, NeedUserLogin needUserLogin) throws Throwable {
                        if (token == null) {
                            throw new AppEngineException(MessageCodeEnum.INVALID_TOKEN_ERROR);
                        }
                        return jp.proceed();
                    }
                }
                """);
        write("aop/annotation/NeedUserLogin.java", "package com.demo.aop.annotation;\npublic @interface NeedUserLogin {}\n");
        write("exception/RestExceptionHandler.java", """
                package com.demo.exception;
                @ControllerAdvice
                public class RestExceptionHandler {
                    @ExceptionHandler(Exception.class)
                    @ResponseBody
                    public ResponseResult handle(Exception e) {
                        return new AppEngineException(MessageCodeEnum.SYSTEM_BUSY).getResponseResult();
                    }
                }
                """);
    }

    private EndpointInsight.Report analyze(String handler, Boolean auth, String sample, String configured)
            throws Exception {
        var repo = new EndpointInsight.Repo(root, EndpointInsight.Repo.index(root));
        return EndpointInsight.analyze(repo, PKG + "controller/MemberController.java", handler, auth, sample,
                configured);
    }

    @Test
    void 注释掉的登录注解和拿不到token直接返回空数据都报出来() throws Exception {
        var r = analyze("memberNumSummary", false, null, null);
        assertNotNull(r);
        assertEquals("ResponseResult<VipNumSummaryDTO>", r.returnType());
        assertTrue(r.login().commented());
        assertEquals(Boolean.FALSE, r.login().required());
        assertEquals("@NeedUserLogin", r.login().annotation());
        assertEquals("MemberController.java:16", r.login().where());
        assertEquals("INVALID_TOKEN_ERROR", r.login().failCode().name());
        assertEquals("1008", r.login().failCode().code());

        Map<String, EndpointInsight.Risk> byKey = r.risks().stream()
                .collect(Collectors.toMap(EndpointInsight.Risk::key, x -> x, (a, b) -> a));
        assertEquals("HIGH", byKey.get("LOGIN_COMMENTED").level());
        assertTrue(byKey.get("LOGIN_COMMENTED").detail().contains("照样报成功"));
        EndpointInsight.Risk noLogin = byKey.get("SILENT_NO_LOGIN");
        assertEquals("HIGH", noLogin.level());
        assertTrue(noLogin.detail().contains("tokenDetail == null"), noLogin.detail());
        assertEquals("MemberServiceImpl.java:11", noLogin.where());
        long silent = r.risks().stream().filter(x -> "SILENT_EMPTY".equals(x.key())).count();
        assertEquals(2, silent, "降级开关和 steamId 为空两处");
        assertEquals("HIGH", r.risks().get(0).level());
        assertEquals("LOW", r.risks().get(r.risks().size() - 1).level());
    }

    @Test
    void 成功码错误码全局错误码() throws Exception {
        var r = analyze("memberNumSummary", false, null, null);
        assertEquals("SUCCESS", r.success().name());
        assertEquals("1", r.success().code());
        assertEquals(List.of("NEW_SUCCESS", "SUCCESS"),
                r.successCodes().stream().map(EndpointInsight.Code::name).toList(), "业务成功文案和 *_SUCCESS_ERROR 不算通用成功码");
        assertTrue(r.risks().stream().anyMatch(x -> "MULTI_SUCCESS".equals(x.key())));
        assertEquals(List.of("PARAM_ERROR"), r.errors().stream().map(EndpointInsight.Code::name).toList());
        assertTrue(r.errors().get(0).where().startsWith("MemberServiceImpl.java:"));
        assertEquals(List.of("SYSTEM_BUSY"), r.globalErrors().stream().map(EndpointInsight.Code::name).toList());
        assertEquals(Boolean.TRUE, r.httpAlways200());
        assertEquals(1, r.services().size());
        assertTrue(r.services().get(0).startsWith("MemberServiceImpl.memberNumSummary"));

        var mismatch = analyze("memberNumSummary", false, null, "0");
        assertTrue(mismatch.risks().stream().anyMatch(x -> "SUCCESS_MISMATCH".equals(x.key())));
    }

    @Test
    void 字段从泛型壳一路展开带上注释() throws Exception {
        var r = analyze("memberNumSummary", false, null, null);
        Map<String, EndpointInsight.Field> f = r.fields().stream()
                .collect(Collectors.toMap(EndpointInsight.Field::path, x -> x));
        assertEquals("number", f.get("$.code").type());
        assertEquals("object", f.get("$.result").type());
        assertEquals("会员完美时刻总数量", f.get("$.result.videoNum").comment());
        assertEquals("待领取改名卡数量", f.get("$.result.renameCardNum").comment());
        assertEquals("是否有临期优惠券", f.get("$.result.hasExpiringCoupons").comment());
        assertEquals("箱子总数", f.get("$.result.vipBoxSummaryDTO.totalBoxCnt").comment());
        assertEquals("array", f.get("$.result.coupons").type());
        assertEquals("主键", f.get("$.result.coupons[].id").comment());
        assertEquals("string", f.get("$.result.coupons[].type").type());
        assertFalse(f.containsKey("$.result.coupons[].shouldNotLeak"));
        assertFalse(f.containsKey("$.result.serialVersionUID"));
    }

    @Test
    void 字段和抓包对照() throws Exception {
        String sample = """
                {"code":1,"message":"success","result":{"videoNum":"3","vipBoxSummaryDTO":null,
                 "hasExpiringCoupons":false,"coupons":[{"id":1,"name":"x","type":"A","extra":7}],"legacyFlag":1}}
                """;
        var r = analyze("memberNumSummary", false, sample, null);
        Map<String, EndpointInsight.Field> f = r.fields().stream()
                .collect(Collectors.toMap(EndpointInsight.Field::path, x -> x));
        assertEquals("OK", f.get("$.code").status());
        assertEquals("TYPE", f.get("$.result.videoNum").status());
        assertEquals("NULL", f.get("$.result.vipBoxSummaryDTO").status());
        assertEquals("SKIP", f.get("$.result.vipBoxSummaryDTO.totalBoxCnt").status());
        assertEquals("MISSING", f.get("$.result.renameCardNum").status());
        assertEquals("OK", f.get("$.result.coupons[].name").status());
        assertEquals("EXTRA", f.get("$.result.legacyFlag").status());
        assertEquals("EXTRA", f.get("$.result.coupons[].extra").status());
        assertTrue(r.risks().stream().anyMatch(x -> "FIELD_TYPE".equals(x.key())));
        assertTrue(r.risks().stream().anyMatch(x -> "FIELD_EXTRA".equals(x.key())));
    }

    @Test
    void 挂了登录注解的不报登录风险_宽松和废弃要报() throws Exception {
        var info = analyze("memberInfo", true, null, null);
        assertEquals(Boolean.TRUE, info.login().required());
        assertFalse(info.login().commented());
        assertTrue(info.risks().stream().noneMatch(x -> x.key().startsWith("LOGIN")));

        var old = analyze("old", true, null, null);
        assertTrue(old.login().lenient());
        assertEquals("NEW_SUCCESS", old.success().name());
        assertEquals(List.of("MEMBER_NOT_EXIST"), old.errors().stream().map(EndpointInsight.Code::name).toList());
        var keys = old.risks().stream().map(EndpointInsight.Risk::key).toList();
        assertTrue(keys.contains("LOGIN_LENIENT"));
        assertTrue(keys.contains("DEPRECATED"));
    }

    @Test
    void 找不到方法返回空() throws Exception {
        assertNull(analyze("nope", null, null, null));
    }

    @Test
    void 返回类型() {
        assertEquals("ResponseResult<List<X>>", EndpointInsight.returnTypeOf("@Get\n  public ResponseResult<List<X>> "));
        assertEquals("String[]", EndpointInsight.returnTypeOf("public String[] "));
        assertEquals("Map<String,Integer>", EndpointInsight.returnTypeOf("public Map<String, Integer>"));
    }
}
