package com.mobile.standalone.service.releasemerge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * 用个人令牌调 GitLab：建合并请求、等 GitLab 检查完、按核对时的提交合并。
 * 令牌只放在请求头里，报错信息里不带令牌。
 */
final class GitLabClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Set<String> PENDING = Set.of(
            "unchecked", "checking", "preparing", "approvals_syncing", "cannot_be_merged_recheck");

    record Mr(long iid, String url, String state, String mergeStatus, String detailed, String mergeSha, String headSha) {
    }

    private final String base;
    private final String token;

    GitLabClient(String base, String token) {
        this.base = base.replaceAll("/+$", "");
        this.token = token;
    }

    /** 测令牌：返回令牌对应的 GitLab 用户名 */
    String whoami() {
        JsonNode u = call("GET", "/user", null);
        return u.path("username").asText("") + (u.hasNonNull("name") ? "（" + u.path("name").asText() + "）" : "");
    }

    Mr findOpen(String project, String source, String target) {
        JsonNode list = call("GET", "/projects/" + enc(project) + "/merge_requests?state=opened&source_branch="
                + enc(source) + "&target_branch=" + enc(target), null);
        return list.isArray() && !list.isEmpty() ? mr(list.get(0)) : null;
    }

    Mr create(String project, String source, String target, String title, String description) {
        return mr(call("POST", "/projects/" + enc(project) + "/merge_requests", Map.of(
                "source_branch", source,
                "target_branch", target,
                "title", title,
                "description", description,
                "remove_source_branch", false)));
    }

    Mr get(String project, long iid) {
        return mr(call("GET", "/projects/" + enc(project) + "/merge_requests/" + iid, null));
    }

    /** GitLab 建完合并请求要花几秒算能不能合，等它算完 */
    Mr waitChecked(String project, long iid, long maxMs) {
        long end = System.currentTimeMillis() + maxMs;
        Mr m = get(project, iid);
        while (System.currentTimeMillis() < end
                && (PENDING.contains(nz(m.detailed())) || PENDING.contains(nz(m.mergeStatus())))) {
            sleep(1500);
            m = get(project, iid);
        }
        return m;
    }

    /** 带上 sha：分支在核对之后又被推了新提交，GitLab 会拒绝，不会合进没核对过的代码 */
    Mr merge(String project, long iid, String sha) {
        return mr(call("PUT", "/projects/" + enc(project) + "/merge_requests/" + iid + "/merge", Map.of(
                "sha", sha,
                "should_remove_source_branch", false,
                "squash", false)));
    }

    private JsonNode call(String method, String path, Map<String, Object> body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/api/v4" + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("PRIVATE-TOKEN", token)
                    .header("Accept", "application/json");
            if (body == null) {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                b.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8));
            }
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() / 100 != 2) {
                throw new GitLabException(r.statusCode(), explain(r.statusCode(), r.body()));
            }
            return JSON.readTree(r.body().isBlank() ? "{}" : r.body());
        } catch (GitLabException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitLabException(0, "被中断");
        } catch (Exception e) {
            throw new GitLabException(0, "连不上 GitLab（" + base + "）：" + e.getMessage());
        }
    }

    private static String explain(int code, String body) {
        String msg = "";
        try {
            JsonNode n = JSON.readTree(body);
            msg = n.has("message") ? n.get("message").toString() : n.path("error").asText("");
        } catch (Exception ignored) {
            msg = body == null ? "" : body;
        }
        msg = msg.length() > 300 ? msg.substring(0, 300) : msg;
        return switch (code) {
            case 401 -> "GitLab 令牌无效或过期，去设置里重新填";
            case 403 -> "你在 GitLab 上没有这个项目的合并权限";
            case 404 -> "GitLab 上找不到（项目、分支或合并请求）：" + msg;
            case 405, 406 -> "GitLab 不让合：" + msg;
            case 409 -> "分支在核对之后又有新提交，或者已有同样的合并请求：" + msg;
            case 422 -> "GitLab 拒绝了请求：" + msg;
            default -> "GitLab 返回 " + code + "：" + msg;
        };
    }

    private static Mr mr(JsonNode n) {
        return new Mr(n.path("iid").asLong(), n.path("web_url").asText(null), n.path("state").asText(null),
                n.path("merge_status").asText(null), n.path("detailed_merge_status").asText(null),
                n.path("merge_commit_sha").asText(null), n.path("sha").asText(null));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static final class GitLabException extends RuntimeException {
        final int status;

        GitLabException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
