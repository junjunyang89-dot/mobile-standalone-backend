package com.mobile.standalone.service.analysis;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.mobile.standalone.config.ReleaseDocProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Component
@RequiredArgsConstructor
class JiraRequirementClient {
    private final ReleaseDocProperties props;
    private static final ObjectMapper JSON=new ObjectMapper();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    boolean configured(){return !props.getJiraBaseUrl().isBlank()&&!props.getUsername().isBlank()&&!props.getPassword().isBlank();}
    String base(){return props.getJiraBaseUrl().replaceAll("/+$","");}
    ObjectNode issue(String key) throws Exception {
        if(!configured())throw new IllegalStateException("请配置 release-doc.jira-base-url、username、password；公司证书须加入 Java 信任库");
        ObjectNode issue=(ObjectNode)get("/rest/api/2/issue/"+key+"?expand=names,renderedFields");
        if(!key.equalsIgnoreCase(issue.path("key").asText()))throw new IllegalStateException("JIRA 返回的单号不一致");
        ArrayNode comments=JSON.createArrayNode();ArrayNode gaps=issue.putArray("collectionGaps");
        try {
            int start=0;
            while(true){
                JsonNode page=get("/rest/api/2/issue/"+key+"/comment?startAt="+start+"&maxResults=50");
                JsonNode rows=page.path("comments"); if(!rows.isArray())throw new IllegalStateException("评论格式不正确");
                rows.forEach(comments::add);start+=rows.size();
                if(start>=page.path("total").asInt(start))break;
                if(rows.isEmpty()||start>=1000){gaps.add("JIRA 评论未完整读取，已读取 "+start+" 条");break;}
            }
        } catch(Exception e){gaps.add("JIRA 评论读取失败，请检查接口权限或连接");}
        issue.set("analysisComments",comments);return issue;
    }
    JsonNode get(String path) throws Exception {return JSON.readTree(bytes(base()+path,4_000_000));}
    byte[] bytes(String url,int limit) throws Exception {
        URI uri=URI.create(url),origin=URI.create(base());
        if(!sameOrigin(origin,uri)||uri.getUserInfo()!=null)throw new IllegalArgumentException("拒绝向非 JIRA 来源发送认证信息");
        String basic=Base64.getEncoder().encodeToString((props.getUsername()+":"+props.getPassword()).getBytes(StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(40)).header("Authorization","Basic "+basic).GET().build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
        try(var stream=response.body()){
            if(response.statusCode()!=200)throw new IllegalStateException("JIRA HTTP "+response.statusCode());
            byte[] data=stream.readNBytes(limit+1);if(data.length>limit)throw new IllegalStateException("JIRA 材料超过读取上限");return data;
        }
    }
    static boolean sameOrigin(URI a,URI b){return Objects.equals(a.getScheme(),b.getScheme())&&Objects.equals(a.getHost(),b.getHost())&&port(a)==port(b);}
    private static int port(URI u){return u.getPort()<0?("https".equals(u.getScheme())?443:80):u.getPort();}
}
