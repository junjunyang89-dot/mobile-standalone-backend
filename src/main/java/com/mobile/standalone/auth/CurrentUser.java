package com.mobile.standalone.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 当前登录人是谁。署名唯一的来源。
 *
 * <p><b>为什么不能让客户端传</b>:`created_by` 这个字段原先是 `@RequestBody` 里的一个普通字段,
 * 于是谁都能填任何名字——署名等于没有。客户端是 Electron 打包分发的,
 * 打开 DevTools 或者直接 curl 就能写别人的名字进去,而这种伪造<b>没有任何痕迹</b>。
 *
 * <p>所以服务端必须自己盖章,并且<b>忽略请求体里的同名字段</b>。
 * 身份来自 {@link AuthFilter} 放进 request 的那个已验证过的用户。
 *
 * <p><b>为什么用 RequestContextHolder 而不是到处传 HttpServletRequest</b>:
 * 要盖章的地方在 service 层,而 service 方法签名里塞一个 `HttpServletRequest`
 * 会让它没法从定时任务、组件复制这些非 HTTP 路径调用。
 * 取不到请求上下文时返回 null——那说明这次调用不是人点的(定时任务、启动初始化),
 * 留 null 比编一个名字诚实。
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /**
     * 当前登录人的显示名,取不到返回 null。
     *
     * <p>优先 displayName、退回 username:报表和列表上「张三」比「zhangsan」好认,
     * 但 displayName 是可以为空的。
     */
    public static String nameOrNull() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        return nameOrNull(request);
    }

    /** 已经拿到 request 的地方(controller)直接用这个,省一次 ThreadLocal 查找 */
    public static String nameOrNull(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object u = request.getAttribute(AuthFilter.ATTR_USER);
        if (u instanceof PlatformUser pu) {
            if (pu.getDisplayName() != null && !pu.getDisplayName().isBlank()) {
                return pu.getDisplayName().trim();
            }
            if (pu.getUsername() != null && !pu.getUsername().isBlank()) {
                return pu.getUsername().trim();
            }
        }
        return null;
    }

    /** 当前登录人的账号名(不会重名、不会被改),按人存东西时用它当键;取不到返回 null */
    public static String usernameOrNull() {
        HttpServletRequest request = currentRequest();
        Object u = request == null ? null : request.getAttribute(AuthFilter.ATTR_USER);
        if (u instanceof PlatformUser pu && pu.getUsername() != null && !pu.getUsername().isBlank()) {
            return pu.getUsername().trim();
        }
        return null;
    }

    /**
     * 盖章用:当前登录人,取不到就用给定的兜底值。
     *
     * <p>兜底值由调用方决定要写什么(比如 {@code "system"}、{@code "定时任务"}),
     * 而不是在这里统一编一个——「谁都查不到」和「系统自己建的」是两件事,
     * 混成同一个值以后就分不开了。
     */
    public static String nameOr(String fallback) {
        String name = nameOrNull();
        return name != null ? name : fallback;
    }

    private static HttpServletRequest currentRequest() {
        try {
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) {
                return sra.getRequest();
            }
        } catch (Exception ignored) {
            // 非 HTTP 线程(定时任务、异步执行)拿不到,返回 null 让调用方兜底
        }
        return null;
    }
}
