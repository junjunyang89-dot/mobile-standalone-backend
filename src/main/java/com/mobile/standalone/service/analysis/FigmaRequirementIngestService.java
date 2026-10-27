package com.mobile.standalone.service.analysis;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.mobile.standalone.config.AnalysisBridgeProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;

@Service
@RequiredArgsConstructor
class FigmaRequirementIngestService {
    private final AnalysisBridgeProperties props;
    private static final ObjectMapper JSON=new ObjectMapper();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    record NodeRef(String file,String node){}
    static NodeRef nodeRef(String link){
        URI uri=URI.create(link);String host=Objects.toString(uri.getHost(),"").toLowerCase(Locale.ROOT);
        if(!Set.of("figma.com","www.figma.com","app.figma.com").contains(host)||!"https".equals(uri.getScheme())||uri.getUserInfo()!=null)throw new IllegalArgumentException("不支持的 Figma 地址");
        Matcher m=Pattern.compile("^/(?:design|file|proto)/([A-Za-z0-9_-]+)/").matcher(uri.getPath());if(!m.find())throw new IllegalArgumentException("Figma 地址缺少文件 ID");
        String node="";for(String pair:Objects.toString(uri.getRawQuery(),"").split("&")){String[] kv=pair.split("=",2);if(kv.length==2&&kv[0].equals("node-id"))node=URLDecoder.decode(kv[1],java.nio.charset.StandardCharsets.UTF_8).replace('-',':');}
        if(!node.matches("[0-9]+:[0-9]+"))throw new IllegalArgumentException("请提供带 node-id 的具体 Figma 节点链接，避免读取整个文件");return new NodeRef(m.group(1),node);
    }
    ObjectNode fetch(String url,Path dir){
        ObjectNode snap=JSON.createObjectNode();snap.put("sourceUrl",url);snap.put("fetchedAt",Instant.now().toString());snap.putArray("gaps");
        try {
            NodeRef ref=nodeRef(url);snap.put("fileKey",ref.file());snap.put("nodeId",ref.node());
            if(props.getFigmaToken().isBlank())throw new IllegalStateException("未配置 analysis-bridge.figma-token，设计内容尚未获取");
            Files.createDirectories(dir);
            JsonNode data=JSON.readTree(bytes("https://api.figma.com/v1/files/"+ref.file()+"/nodes?ids="+encode(ref.node()),true,10_000_000));
            JsonNode document=data.path("nodes").path(ref.node()).path("document");if(document.isMissingNode()||document.isNull())throw new IllegalStateException("Figma 节点不存在或没有读取权限");
            snap.put("name",data.path("name").asText());snap.put("version",data.path("version").asText());snap.put("lastModified",data.path("lastModified").asText());
            Path raw=dir.resolve("nodes.json");Files.writeString(raw,data.toString());snap.put("nodesPath",raw.toString());
            ArrayNode text=snap.putArray("textNodes");collect(document,text,0);
            JsonNode render=JSON.readTree(bytes("https://api.figma.com/v1/images/"+ref.file()+"?ids="+encode(ref.node())+"&format=png&scale=1",true,2_000_000));
            String imageUrl=render.path("images").path(ref.node()).asText("");if(imageUrl.isBlank())throw new IllegalStateException("Figma 未返回节点设计图");
            URI image=URI.create(imageUrl);String host=Objects.toString(image.getHost(),"");
            if(!"https".equals(image.getScheme())||image.getUserInfo()!=null||!(host.endsWith(".figma.com")||host.endsWith(".amazonaws.com")))throw new IllegalStateException("Figma 返回了非预期图片来源");
            byte[] png=bytes(imageUrl,false,20_000_000);if(png.length<8||png[0]!=(byte)0x89||png[1]!=0x50)throw new IllegalStateException("设计图不是有效 PNG");
            Path file=dir.resolve("design.png");Files.write(file,png);snap.put("imagePath",file.toString());snap.put("imageHash",AnalysisContract.digest(Base64.getEncoder().encodeToString(png)));snap.put("status","ready");
            snap.withArray("gaps").add("已读取指定节点文字与静态设计图，未自动遍历其他节点或验证所有原型交互");
        }catch(Exception e){snap.put("status",snap.has("nodesPath")?"partial":"failed");snap.withArray("gaps").add(e instanceof IllegalStateException||e instanceof IllegalArgumentException?e.getMessage():"Figma 网络、证书或权限读取失败");}
        return snap;
    }
    private static void collect(JsonNode node,ArrayNode out,int depth){if(depth>80||out.size()>10000)return; if(node.has("characters")){var row=out.addObject();row.put("id",node.path("id").asText());row.put("name",node.path("name").asText());row.put("text",node.path("characters").asText());}for(JsonNode child:node.path("children"))collect(child,out,depth+1);}
    private static String encode(String s){return URLEncoder.encode(s,java.nio.charset.StandardCharsets.UTF_8);}
    private byte[] bytes(String url,boolean auth,int limit)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(45)).GET();if(auth)builder.header("X-Figma-Token",props.getFigmaToken());
        var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());try(var in=response.body()){
            if(response.statusCode()!=200)throw new IllegalStateException("Figma HTTP "+response.statusCode());byte[] data=in.readNBytes(limit+1);if(data.length>limit)throw new IllegalStateException("Figma 材料过大，请选择更小的节点");return data;
        }
    }
}
