package com.mobile.standalone.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * CodeWords STORAGE_API_KEY 鉴权（可选）。
 * 仅拦截 /knowledge、/conversation；未配置 key 时放行。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AgentStorageAuthFilter extends OncePerRequestFilter {

    @Value("${codewords.storage-api-key:}")
    private String storageApiKey;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        String ctx = request.getContextPath() == null ? "" : request.getContextPath();
        String p = path.startsWith(ctx) ? path.substring(ctx.length()) : path;
        return !(p.startsWith("/knowledge") || p.startsWith("/conversation"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (storageApiKey == null || storageApiKey.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        String provided = extractKey(request);
        if (provided != null && provided.equals(storageApiKey.trim())) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"message\":\"Unauthorized: invalid or missing STORAGE_API_KEY\"}");
    }

    private static String extractKey(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && !auth.isBlank()) {
            String v = auth.trim();
            if (v.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return v.substring(7).trim();
            }
            return v;
        }
        String x = request.getHeader("X-API-Key");
        if (x != null && !x.isBlank()) return x.trim();
        String q = request.getParameter("api_key");
        return (q != null && !q.isBlank()) ? q.trim() : null;
    }
}
