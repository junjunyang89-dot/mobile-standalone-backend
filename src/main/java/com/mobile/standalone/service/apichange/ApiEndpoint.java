package com.mobile.standalone.service.apichange;

import java.util.List;
import java.util.Set;

/**
 * 一个 HTTP 接口。
 *
 * @param httpMethod   GET/POST/...；@RequestMapping 没写 method 时是 ANY
 * @param path         类上 @RequestMapping 与方法路径拼接后的完整路径
 * @param handler      Java 方法名，定位代码用
 * @param returnType   原始返回类型，如 ResponseResult&lt;List&lt;XxxDTO&gt;&gt;
 * @param summary      @ApiOperation 的中文说明
 * @param payloadType  剥掉 ResponseResult / List / Map 之后真正承载字段的类型名，可能为空
 * @param params       入参
 * @param usedServices 这个方法体里用到的 Service 类型名——用来回答「接口签名没变，但实现改了吗」
 */
public record ApiEndpoint(
        String httpMethod,
        String path,
        String handler,
        String returnType,
        String summary,
        String payloadType,
        List<ApiParam> params,
        Set<String> usedServices
) {
    /** 同一个接口的身份：动词 + 路径。方法名改了不算换了接口 */
    public String key() {
        return httpMethod + " " + path;
    }

    /** @RequestBody 那个类型：它的字段变化跟返回体一样要 diff */
    public String bodyType() {
        for (ApiParam p : params) {
            if ("BODY".equals(p.source())) {
                return p.type();
            }
        }
        return null;
    }
}
