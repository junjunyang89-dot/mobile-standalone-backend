package com.mobile.standalone.service.apitest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 体检结论变成用例：目前是「不带 token」这一条。
 * <p>
 * 源码确认要登录的，期望登录失败码；源码里登录被放开、或者没登录也返回成功的，
 * 写成「疑似问题」——断言按「应该拦住」写，跑失败就说明问题真实存在。
 * 但「应该拦住」是我们的推断，不是需求，所以一律先停用，人确认过再启用。
 */
public final class InsightCases {

    private InsightCases() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** @param suspect 疑似问题：断言的是「应该」，不是现状 */
    public record Planned(String name, boolean suspect, Map<String, String> query, Map<String, String> headers,
                          String assertions, String basis, String note) {
    }

    /**
     * @param capturedQuery   抓包的 query（token 参数会被去掉）
     * @param capturedHeaders 抓包里留下的请求头
     * @param authHeaders     项目从哪些头读 token；用例里把它们置空，免得执行时又自动带上
     * @param codePath        业务码路径；项目没配时用返回体里的 $.code
     * @param configuredSuccess 项目配的成功码，源码里没认出成功码时兜底
     * @return 体检看不出登录要求时返回 null
     */
    public static Planned noToken(EndpointInsight.Report r, Map<String, String> capturedQuery,
                                  Map<String, String> capturedHeaders, Collection<String> authHeaders,
                                  String codePath, String configuredSuccess) {
        if (r == null || r.login() == null) {
            return null;
        }
        String path = codePath != null && !codePath.isBlank() ? codePath.trim()
                : r.fields().stream().anyMatch(f -> "$.code".equals(f.path())) ? "$.code" : null;
        if (path == null) {
            return null;
        }
        String success = r.success() != null && r.success().code() != null ? r.success().code()
                : configuredSuccess == null || configuredSuccess.isBlank() ? null : configuredSuccess.trim();
        EndpointInsight.Login login = r.login();
        EndpointInsight.Risk silent = r.risks().stream()
                .filter(x -> "SILENT_NO_LOGIN".equals(x.key())).findFirst().orElse(null);

        ArrayNode asserts = JSON.createArrayNode();
        String name;
        String basis;
        String note;
        boolean suspect;
        if (Boolean.TRUE.equals(login.required()) && !login.lenient()) {
            EndpointInsight.Code fail = login.failCode();
            ObjectNode a = asserts.addObject().put("type", "jsonPath").put("path", path);
            if (fail != null && fail.code() != null) {
                a.put("op", "eq").put("value", fail.code());
                AssertionSource.mark(a, AssertionSource.CODE, "源码：" + login.annotation() + " 拦截时抛 "
                        + fail.name() + "（" + fail.code() + " " + nz(fail.message()) + "），见 " + fail.where());
            } else if (success != null) {
                a.put("op", "ne").put("value", success);
                AssertionSource.mark(a, AssertionSource.CODE, "源码：挂了 " + login.annotation() + "，不带 token 不该是成功码");
            } else {
                return null;
            }
            suspect = false;
            name = "不带 token · 应提示登录";
            basis = "源码里挂了 " + login.annotation() + (login.where() == null ? "" : "（" + login.where() + "）");
            note = "按源码推出来的，还没跑过；试跑一次通过再启用";
        } else if (login.commented() || silent != null) {
            if (success == null) {
                return null;
            }
            ObjectNode a = asserts.addObject().put("type", "jsonPath").put("path", path)
                    .put("op", "ne").put("value", success);
            StringBuilder why = new StringBuilder();
            if (login.commented()) {
                why.append("登录注解「// ").append(login.annotation()).append("」被注释（").append(login.where()).append("）");
            }
            if (silent != null) {
                why.append(why.isEmpty() ? "" : "；").append(silent.detail()).append("（").append(silent.where()).append("）");
            }
            AssertionSource.mark(a, AssertionSource.CODE, "疑似问题，按「没登录不该报成功」写的：" + why);
            suspect = true;
            name = "疑似问题 · 不带 token 仍返回成功";
            basis = why.toString();
            note = "疑似问题：" + why + "。这条按「应该拦住」写，跑失败说明问题真实存在——"
                    + "先找开发确认是 bug 还是故意放开的；是故意的就删掉这条";
        } else {
            return null;
        }

        Map<String, String> query = new LinkedHashMap<>();
        if (capturedQuery != null) {
            capturedQuery.forEach((k, v) -> {
                if (!isAuthKey(k) && (v == null || !v.contains("{{token}}"))) {
                    query.put(k, v);
                }
            });
        }
        Set<String> blank = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        blank.addAll(List.of("token", "Authorization"));
        if (authHeaders != null) {
            authHeaders.stream().filter(h -> h != null && !h.isBlank()).forEach(blank::add);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (capturedHeaders != null) {
            capturedHeaders.forEach((k, v) -> {
                if (!blank.contains(k) && !isAuthKey(k)) {
                    headers.put(k, v);
                }
            });
        }
        // 置空而不是不写：执行器只在用例没写这个头时才自动补登录 token
        blank.forEach(h -> headers.put(h, ""));
        return new Planned(name, suspect, query, headers, asserts.toString(), basis, note);
    }

    private static boolean isAuthKey(String k) {
        String s = k == null ? "" : k.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return s.equals("token") || s.equals("accesstoken") || s.equals("authorization");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
