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

class ParamInsightTest {

    @TempDir
    Path root;

    private static final String PKG = "src/main/java/com/demo/";
    private static final String CTRL = PKG + "controller/LiveController.java";

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
                    public void setResult(E result) { this.result = result; }
                }
                """);
        write("enumeration/MessageCodeEnum.java", """
                package com.demo.enumeration;
                public enum MessageCodeEnum {
                    SUCCESS(1, "success"),
                    PARAM_ERROR(10001, "参数错误"),
                    SYSTEM_BUSY(99999, "系统繁忙"),
                    SYSTEM_TIME_OUT(99998, "超时");
                    private final int code;
                    private final String message;
                    MessageCodeEnum(int code, String message) { this.code = code; this.message = message; }
                }
                """);
        write("exception/AppEngineException.java", """
                package com.demo.exception;
                public class AppEngineException extends RuntimeException {
                    public AppEngineException(int code, String message) { }
                    public AppEngineException(MessageCodeEnum messageCode) { }
                }
                """);
        write("exception/RestExceptionHandler.java", """
                package com.demo.exception;
                import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
                @ControllerAdvice
                public class RestExceptionHandler {
                    @ExceptionHandler(AppEngineException.class)
                    @ResponseBody
                    public ResponseResult appEngineExceptionHandler(HttpServletRequest req, AppEngineException e) {
                        return e.getResponseResult();
                    }

                    @ExceptionHandler(MethodArgumentNotValidException.class)
                    @ResponseBody
                    public ResponseResult methodArgumentNotValidExceptionHandler(HttpServletRequest req, MethodArgumentNotValidException e) {
                        return new ResponseResult(MessageCodeEnum.PARAM_ERROR.getCode(), e.getBindingResult().getFieldError().getDefaultMessage());
                    }

                    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
                    @ResponseBody
                    public ResponseResult methodArgumentTypeMismatchExceptionHandler(HttpServletRequest req, MethodArgumentTypeMismatchException e) {
                        return new ResponseResult(MessageCodeEnum.PARAM_ERROR.getCode(), MessageCodeEnum.PARAM_ERROR.getMessage());
                    }

                    @ExceptionHandler(TimeoutException.class)
                    @ResponseBody
                    public ResponseResult restTimeOutExceptionHandler(HttpServletRequest req, Exception e) {
                        return new AppEngineException(MessageCodeEnum.SYSTEM_TIME_OUT).getResponseResult();
                    }

                    @ExceptionHandler(Exception.class)
                    @ResponseBody
                    public ResponseResult restExceptionHandler(HttpServletRequest req, Exception e) {
                        return new AppEngineException(MessageCodeEnum.SYSTEM_BUSY).getResponseResult();
                    }
                }
                """);
        write("controller/validator/CommonValidator.java", """
                package com.demo.controller.validator;
                import com.demo.enumeration.MessageCodeEnum;
                import static com.demo.enumeration.MessageCodeEnum.PARAM_ERROR;
                public class CommonValidator {
                    private static Integer MAX_PAGE_SIZE_LIMIT = 200;

                    public static void validateCommonPageRequest(CommonPageRequest request) {
                        if (request.getPageSize() > MAX_PAGE_SIZE_LIMIT || request.getPageSize() <= 0) {
                            throw new AppEngineException(PARAM_ERROR);
                        }
                    }

                    public static void validatePredicate(boolean predicate, int code, String message) {
                        if (!predicate) {
                            throw new AppEngineException(code, message);
                        }
                    }

                    public static void validatePredicate(boolean predicate, String message) {
                        if (!predicate) {
                            throw new AppEngineException(PARAM_ERROR.getCode(), message);
                        }
                    }

                    public static void validatePredicate(boolean predicate, MessageCodeEnum codeEnum) {
                        if (!predicate) {
                            throw new AppEngineException(codeEnum);
                        }
                    }
                }
                """);
        write("request/CommonPageRequest.java", """
                package com.demo.request;
                @Data
                public class CommonPageRequest {
                    /**
                     * 分页大小
                     */
                    private int pageSize;
                    /**
                     * 页码
                     */
                    private int pageNum;
                }
                """);
        write("request/LivePageRequest.java", """
                package com.demo.request;
                import javax.validation.constraints.NotBlank;
                @Data
                public class LivePageRequest extends CommonPageRequest {
                    @ApiModelProperty(value = "直播平台")
                    private Integer platform;
                    @ApiModelProperty(value = "直播游戏类型")
                    private Integer gameType;
                    @NotBlank(message = "userIdentity不能为空")
                    private String userIdentity;
                }
                """);
        write("request/BindCardRequest.java", """
                package com.demo.request;
                @Data
                public class BindCardRequest {
                    @NotNull(message = "卡号不能为空")
                    private Long cardId;
                    @Min(1)
                    @Max(value = 9)
                    private Integer count;
                    private String remark;
                }
                """);
        write("service/Dota2LiveService.java", """
                package com.demo.service;
                public interface Dota2LiveService {
                    MatchShareVO getMatchShare(Integer matchId, Integer gameType, Integer newsId);
                }
                """);
        write("service/impl/Dota2LiveServiceImpl.java", """
                package com.demo.service.impl;
                public class Dota2LiveServiceImpl implements Dota2LiveService {
                    @Override
                    public MatchShareVO getMatchShare(Integer matchId, Integer gameType, Integer newsId) {
                        if (matchId == null || gameType == null) {
                            return null;
                        }
                        MatchShareVO matchShareVO = getMatchShareVO(matchId, gameType);
                        if (newsId != null && matchShareVO != null) {
                            matchShareVO.setTitle("x");
                        }
                        return matchShareVO;
                    }

                    private MatchShareVO getMatchShareVO(Integer matchId, Integer gameType) {
                        MatchShareVO matchShareVO;
                        if (gameType == 1) {
                            matchShareVO = matchShareMapper.getMatchShareDefault(matchId);
                            if (matchShareVO == null) {
                                return null;
                            }
                        } else if (gameType == 2) {
                            matchShareVO = matchShareMapper.getCsMatchShareDefault(matchId);
                        } else {
                            return null;
                        }
                        return matchShareVO;
                    }
                }
                """);
        write("controller/LiveController.java", """
                package com.demo.controller;
                import com.demo.controller.validator.CommonValidator;
                @RestController
                public class LiveController {
                    private Dota2LiveService dota2LiveService;

                    @GetMapping("/getMatchShare")
                    @ApiImplicitParams({
                            @ApiImplicitParam(name = "matchId", value = "房间 id", defaultValue = "601514"),
                            @ApiImplicitParam(name = "gameType", value = "游戏类型（1-dota，2-csgo")
                    })
                    public ResponseResult<MatchShareVO> getMatchShare(Integer matchId, Integer gameType, Integer newsId) {
                        ResponseResult<MatchShareVO> responseResult = this.getResponseResult(MessageCodeEnum.SUCCESS);
                        responseResult.setResult(dota2LiveService.getMatchShare(matchId, gameType, newsId));
                        return responseResult;
                    }

                    @PostMapping(value = "/liveVideo/pageList")
                    public ResponseResult<List<LiveVideoVo>> getLiveVideoList(@RequestBody LivePageRequest request) {
                        ResponseResult<List<LiveVideoVo>> responseResult = this.getResponseResult(MessageCodeEnum.SUCCESS);
                        CommonValidator.validateCommonPageRequest(request);
                        CommonValidator.validatePredicate(Objects.nonNull(request.getGameType()), "lack of gameType");
                        CommonValidator.validatePredicate(Objects.nonNull(request.getPlatform()), "lack of platform");
                        return responseResult;
                    }

                    @GetMapping("/detail")
                    public ResponseResult<Object> detail(@RequestParam("id") Long id,
                                                         @RequestParam(value = "lang", defaultValue = "zh") String lang,
                                                         @RequestParam(required = false) Integer type,
                                                         int page) {
                        if (type > 3) {
                            throw new AppEngineException(MessageCodeEnum.PARAM_ERROR);
                        }
                        return null;
                    }

                    @PostMapping("/bind")
                    public ResponseResult<Object> bind(@Valid @RequestBody BindCardRequest req, Integer size) {
                        int s = size == null ? 20 : size;
                        CommonValidator.validatePredicate(StringUtils.isEmpty(req.getRemark()) || review(req.getRemark()),
                                MessageCodeEnum.PARAM_ERROR);
                        return null;
                    }
                }
                """);
    }

    private Map<String, ParamInsight.Param> analyze(String handler) throws Exception {
        EndpointInsight.Repo repo = new EndpointInsight.Repo(root, EndpointInsight.Repo.index(root));
        String src = Files.readString(root.resolve(CTRL));
        String method = JavaSnippets.method(src, handler);
        List<ParamInsight.Param> ps = ParamInsight.analyze(repo, CTRL, src, method, handler, new java.util.HashMap<>());
        return ps.stream().collect(Collectors.toMap(ParamInsight.Param::name, p -> p, (a, b) -> a,
                java.util.LinkedHashMap::new));
    }

    @Test
    void getMatchShareMissingParamsReturnSuccessWithNullData() throws Exception {
        Map<String, ParamInsight.Param> ps = analyze("getMatchShare");
        assertEquals(List.of("matchId", "gameType", "newsId"), List.copyOf(ps.keySet()));

        ParamInsight.Param matchId = ps.get("matchId");
        assertEquals("query", matchId.in());
        assertFalse(matchId.required());
        assertEquals("房间 id", matchId.comment());
        assertEquals(ParamInsight.SILENT, matchId.missing().kind());
        assertEquals("null", matchId.missing().returns());
        assertTrue(matchId.missing().suspect());
        assertEquals("Dota2LiveServiceImpl.java:5", matchId.missing().where());

        assertEquals(ParamInsight.REJECT, matchId.badType().kind());
        assertEquals("10001", matchId.badType().code());
        assertFalse(matchId.badType().suspect(), "类型错有专门的处理，返回参数错误，不算问题");

        ParamInsight.Param gameType = ps.get("gameType");
        assertEquals(ParamInsight.SILENT, gameType.missing().kind());
        assertEquals(List.of("1", "2"), gameType.values());
        assertNotNull(gameType.unsupported());
        assertEquals(ParamInsight.SILENT, gameType.unsupported().kind());

        assertEquals(ParamInsight.OPTIONAL, ps.get("newsId").missing().kind());
    }

    @Test
    void validatorCallsAndPrimitivePageSizeAreReadThroughCommonValidator() throws Exception {
        Map<String, ParamInsight.Param> ps = analyze("getLiveVideoList");

        ParamInsight.Param pageSize = ps.get("pageSize");
        assertEquals("body", pageSize.in());
        assertEquals("分页大小", pageSize.comment());
        assertEquals(ParamInsight.REJECT, pageSize.missing().kind(), "int 不传是 0，命中 <= 0");
        assertEquals("10001", pageSize.missing().code());
        List<String> values = pageSize.bounds().stream().map(ParamInsight.Bound::value).toList();
        assertTrue(values.contains("201"), values.toString());
        assertTrue(values.contains("0"), values.toString());

        ParamInsight.Param gameType = ps.get("gameType");
        assertEquals(ParamInsight.REJECT, gameType.missing().kind());
        assertEquals("10001", gameType.missing().code());
        assertEquals("lack of gameType", gameType.missing().message());
        assertEquals("LiveController.java:22", gameType.missing().where());
        assertTrue(gameType.missing().detail().contains("CommonValidator.validatePredicate()"),
                gameType.missing().detail());

        assertEquals("lack of platform", ps.get("platform").missing().message());

        ParamInsight.Param body = ps.get("gameType");
        assertEquals(ParamInsight.REJECT, body.badType().kind());
        assertEquals("99999", body.badType().code(), "JSON 反序列化失败没人接，落到兜底");
        assertTrue(body.badType().suspect());

        ParamInsight.Param uid = ps.get("userIdentity");
        assertNull(uid.missing(), "注解不生效、代码也没判，看不出来就不写");
        assertTrue(uid.warns().get(0).contains("没加 @Valid"), uid.warns().toString());
    }

    @Test
    void frameworkRequiredDefaultsPrimitivesAndNpe() throws Exception {
        Map<String, ParamInsight.Param> ps = analyze("detail");

        ParamInsight.Param id = ps.get("id");
        assertTrue(id.required());
        assertEquals(ParamInsight.REJECT, id.missing().kind());
        assertEquals("99999", id.missing().code(), "缺必填参数没有专门处理，落到 Exception 兜底");
        assertTrue(id.missing().suspect());

        assertEquals(ParamInsight.DEFAULT, ps.get("lang").missing().kind());

        ParamInsight.Param type = ps.get("type");
        assertEquals(ParamInsight.CRASH, type.missing().kind(), "type > 3 之前没判空");
        assertEquals("99999", type.missing().code());
        assertEquals("4", type.bounds().get(0).value());

        ParamInsight.Param page = ps.get("page");
        assertEquals(ParamInsight.CRASH, page.missing().kind(), "int 参数不传 Spring 直接报错");
    }

    @Test
    void validAnnotationsBecomeRejectsAndTernaryIsDefault() throws Exception {
        Map<String, ParamInsight.Param> ps = analyze("bind");

        ParamInsight.Param cardId = ps.get("cardId");
        assertTrue(cardId.required());
        assertEquals(ParamInsight.REJECT, cardId.missing().kind());
        assertEquals("10001", cardId.missing().code());
        assertEquals("卡号不能为空", cardId.missing().message());

        ParamInsight.Param count = ps.get("count");
        List<String> values = count.bounds().stream().map(ParamInsight.Bound::value).toList();
        assertEquals(List.of("0", "10"), values);

        assertEquals(ParamInsight.DEFAULT, ps.get("size").missing().kind());
        assertNull(ps.get("remark").missing(), "isEmpty(remark) || 审核通过：不传反而通过，不能读成「不传就报错」");
    }
}
