package com.mobile.standalone.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 真正的权限边界。
 * <p>
 * 前端把菜单藏起来只解决「看不到」：客户端是 Electron 打包分发的，后端地址在客户端里可填，
 * 打开 DevTools 或者直接 curl 就绕过去了。所以拦截必须在这里。
 * <p>
 * 两个刻意的放行口子，都是为了不把人锁在门外：
 * <ul>
 *   <li><b>一个账号都没有时全放行</b>——否则第一个管理员没法创建</li>
 *   <li><b>登录、健康检查不拦</b>——健康检查要能分清「服务挂了」和「令牌过期」</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class AuthFilter extends OncePerRequestFilter {

    public static final String ATTR_USER = "platformUser";

    /**
     * 必须用它取路径，不能用 {@code getRequestURI()}。
     * <p>
     * {@code getRequestURI()} 返回的是<b>原始未解码</b>的 URI，而 Spring 的路由映射用的是解码归一化后的路径。
     * 两者不一致就等于鉴权形同虚设：{@code /api/health/../platform-users} 在这里看着像公开的 /health，
     * 打到的却是建账号接口。
     */
    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

    private final AuthTokenService tokenService;
    private final PlatformUserService userService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 浏览器的跨域预检不带任何请求头，包括 Authorization。在这里拦掉的话，
        // 每一个带 JSON body 的请求都会先死在预检上——而报错是「跨域失败」，
        // 完全看不出跟鉴权有关。客户端是 Electron，页面和后端天然不同源，
        // 所以这条路径是必经的
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String path = pathOf(request);

        // 先挡穿越再谈放行：正常业务路径不会有 . / .. / %，有就是在试探边界
        if (PermissionRules.looksTraversal(path)) {
            log.warn("[auth] 拒绝可疑路径 method={} path={} from={}",
                    request.getMethod(), path, request.getRemoteAddr());
            deny(response, 400, "非法请求路径");
            return;
        }

        if (PermissionRules.isPublic(path)) {
            chain.doFilter(request, response);
            return;
        }
        // 还没建账号：整套鉴权当作没开启。这也是升级时的过渡态——
        // 新版本部署上去、还没人建账号，平台照常用，不会一上线就全员打不开。
        // 但「查不到账号数」绝不能当成「还没建账号」：那样 DB 抖一下，
        // 264 个端点、真机控制、抓包和 Jira/Wiki 凭据就对内网所有人敞开了
        boolean bootstrapping;
        try {
            bootstrapping = userService.isBootstrapping();
        } catch (PlatformUserService.AuthUnavailableException e) {
            log.error("[auth] 账号服务不可用，本次拒绝（绝不放行）: {}", e.getMessage());
            deny(response, 503, "账号服务暂时不可用，请稍后重试");
            return;
        }
        if (bootstrapping) {
            chain.doFilter(request, response);
            return;
        }

        String token = bearerToken(request);
        String userId = tokenService.verify(token);
        if (userId == null) {
            deny(response, 401, "未登录或登录已过期，请重新登录");
            return;
        }
        PlatformUser user = userService.get(userId);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            // 令牌是签名有效的，但账号已经被停用/删除。令牌不存服务端，
            // 所以「停用」的生效点就在这里——每次请求现查一次账号状态
            deny(response, 401, "账号已停用，请联系管理员");
            return;
        }

        PlatformRole required = PermissionRules.required(request.getMethod(), path);
        if (!user.roleEnum().satisfies(required)) {
            deny(response, 403, "当前账号是「" + user.roleEnum().label()
                    + "」，这个操作需要「" + required.label() + "」及以上权限");
            return;
        }

        request.setAttribute(ATTR_USER, user);
        chain.doFilter(request, response);
    }

    private String pathOf(HttpServletRequest request) {
        return PATH_HELPER.getPathWithinApplication(request);
    }

    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        // 截图、报告这类资源是直接写进 <img src> 的，带不上请求头
        return request.getHeader("X-Auth-Token");
    }

    private void deny(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"message\":\"" + message.replace("\"", "'") + "\"}");
    }
}
