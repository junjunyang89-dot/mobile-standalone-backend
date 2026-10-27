package com.mobile.standalone.service.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.config.ReleaseDocProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
class WikiConfluenceClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ReleaseDocProperties wikiProps;
    private final HttpClient http = trustAll();

    boolean configured() {
        return wikiProps.getUsername() != null && !wikiProps.getUsername().isBlank()
                && wikiProps.getPassword() != null && !wikiProps.getPassword().isBlank();
    }

    String base() {
        String u = wikiProps.getWikiBaseUrl() == null ? "https://wiki.shdev.net" : wikiProps.getWikiBaseUrl().trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    JsonNode getJson(String pathAndQuery) throws Exception {
        return JSON.readTree(read(get(base()+pathAndQuery),10_000_000));
    }

    byte[] getBytes(String absoluteOrPath) throws Exception {
        String url=absoluteOrPath.startsWith("http")?absoluteOrPath:base()+absoluteOrPath;
        return read(get(url),20_000_000);
    }

    JsonNode page(String pageId) throws Exception {
        return getJson("/rest/api/content/" + pageId
                + "?expand=body.storage,body.view,version,history,space");
    }

    List<JsonNode> children(String pageId, String type) throws Exception {
        List<JsonNode> all = new ArrayList<>();
        int start = 0;
        while (true) {
            String expand = "comment".equals(type) ? "body.view,version,history,ancestors" : "version,container";
            JsonNode page = getJson("/rest/api/content/" + pageId + "/child/" + type
                    + "?limit=50&start=" + start + "&expand=" + expand
                    + ("comment".equals(type) ? "&depth=all" : ""));
            var results = page.path("results");
            if (!results.isArray() || results.isEmpty()) break;
            results.forEach(all::add);
            if (!page.path("_links").has("next") && results.size() < page.path("limit").asInt(50)) break;
            if (!page.path("_links").has("next") && results.size() < 50) break;
            start += results.size();
            if (start >= 1000) throw new IllegalStateException("Wiki 子列表超过 1000 项，未完整读取");
        }
        return all;
    }

    private HttpRequest get(String url) {
        URI target=URI.create(url);
        if (!JiraRequirementClient.sameOrigin(URI.create(base()),target) || target.getUserInfo()!=null) throw new IllegalArgumentException("拒绝向非 Wiki 来源发送认证信息");
        return HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(60))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Authorization", basic())
                .header("Accept", "application/json, */*")
                .header("User-Agent", "Mozilla/5.0 (compatible; AnalysisWiki/1.0)")
                .GET()
                .build();
    }

    private byte[] read(HttpRequest req,int limit)throws Exception {
        var response=http.send(req,HttpResponse.BodyHandlers.ofInputStream());
        try(var in=response.body()){
            if(response.statusCode()<200||response.statusCode()>=300)throw new IllegalStateException("Wiki HTTP "+response.statusCode());
            byte[] bytes=in.readNBytes(limit+1);if(bytes.length>limit)throw new IllegalStateException("Wiki 材料超过读取上限，请缩小范围");return bytes;
        }
    }

    private String basic() {
        String token = wikiProps.getUsername() + ":" + wikiProps.getPassword();
        return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    private static HttpClient trustAll() {
        try {
            TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ssl = SSLContext.getInstance("TLSv1.2");
            ssl.init(null, trustAll, new SecureRandom());
            return HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .sslContext(ssl)
                    .proxy(HttpClient.Builder.NO_PROXY)
                    .connectTimeout(Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Wiki HttpClient 初始化失败: " + e.getMessage(), e);
        }
    }
}
