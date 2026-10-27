package com.mobile.standalone.service.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class WikiRequirementIngestService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final WikiConfluenceClient wiki;

    String baseUrl(){return wiki.base();}

    public boolean configured() {
        return wiki.configured();
    }

    /** Preview for operators; does not require a job. */
    public ObjectNode preview(String urlOrPageId) {
        String pageId = WikiPageIds.extract(urlOrPageId)
                .orElseThrow(() -> new IllegalArgumentException("无法从地址解析 Wiki pageId"));
        return fetch(pageId, null);
    }

    /** Server-side only: pull Wiki URLs found in the task materials. */
    public void attachToJob(ObjectNode input, Path jobDir) {
        Set<String> ids = new LinkedHashSet<>();
        WikiPageIds.extract(input.path("requirementUrl").asText("")).ifPresent(ids::add);
        for (String url : WikiRequirementParser.wikiUrlsFromMaterials(input.path("materials"))) {
            WikiPageIds.extract(url).ifPresent(ids::add);
        }
        if (ids.isEmpty()) return;
        ArrayNode snaps = input.putArray("wikiSnapshots");
        ArrayNode gaps = input.has("coverageGaps") && input.path("coverageGaps").isArray()
                ? (ArrayNode) input.path("coverageGaps") : input.putArray("materialGaps");
        for (String pageId : ids) {
            try {
                Path dir = jobDir.resolve("wiki").resolve(pageId);
                Files.createDirectories(dir);
                ObjectNode snap = fetch(pageId, dir);
                Files.writeString(dir.resolve("snapshot.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(snap));
                ObjectNode ref = snaps.addObject();
                ref.put("pageId", pageId);
                ref.put("source", snap.path("source").asText());
                ref.put("title", snap.path("page").path("title").asText());
                ref.put("version", snap.path("page").path("version").asInt());
                ref.put("tableCount", snap.path("tableCount").asInt());
                ref.put("codeBlockCount", snap.path("codeBlockCount").asInt());
                ref.put("commentCount", snap.path("comments").size());
                ref.put("attachmentCount", snap.path("attachments").size());
                ref.put("imageCount", snap.path("images").size());
                ref.put("snapshotPath", dir.resolve("snapshot.json").toString());
                ref.put("status",snap.path("status").asText("partial"));
                ref.set("comments", snap.path("comments"));
                ref.set("gaps", snap.path("gaps"));
            } catch (Exception e) {
                log.warn("[analysis] Wiki 读取失败 pageId={}: {}", pageId, e.getMessage());
                gaps.add("Wiki pageId=" + pageId + " 读取失败：" + e.getMessage());
            }
        }
    }

    ObjectNode fetch(String pageId, Path imageDir) {
        if (!wiki.configured()) {
            throw new IllegalStateException("未配置 Wiki 账号（release-doc.username/password）");
        }
        try {
            JsonNode page = wiki.page(pageId);
            String storage = page.path("body").path("storage").path("value").asText("");
            String view = page.path("body").path("view").path("value").asText("");
            List<JsonNode> comments=List.of(),attachments=List.of();
            java.util.List<String> readGaps=new java.util.ArrayList<>();
            try{comments=wiki.children(pageId,"comment");}catch(Exception e){readGaps.add("Wiki 评论未完整读取");}
            try{attachments=wiki.children(pageId,"attachment");}catch(Exception e){readGaps.add("Wiki 附件清单未完整读取");}
            ObjectNode snap = WikiRequirementParser.snapshot(pageId, page, storage, view, comments, attachments, "rest");
            for(String gap:readGaps)snap.withArray("gaps").add(gap);
            if (imageDir != null) downloadImages(snap, imageDir);
            snap.put("status",snap.path("gaps").isEmpty()?"ready":"partial");
            return snap;
        } catch (Exception restFailed) {
            log.warn("[analysis] Wiki REST 失败，尝试 word 导出兜底 pageId={}: {}", pageId, restFailed.getMessage());
            try {
                byte[] exported = wiki.getBytes("/exportword?pageId=" + pageId);
                WikiMimeExportParser.Parsed parsed = WikiMimeExportParser.parse(exported);
                if (parsed.html.isBlank() || (!parsed.html.contains("Section1") && !parsed.html.contains("main-content"))) throw new IllegalStateException("导出内容不是有效需求正文，可能返回了登录页");
                JsonNode fakePage = JSON.createObjectNode().put("title", "exportword-" + pageId);
                ObjectNode snap = WikiRequirementParser.snapshot(pageId, fakePage, parsed.html, parsed.html,
                        List.of(), List.of(), "export-fallback");
                snap.putArray("gaps").add("REST 失败已改用导出解析：" + restFailed.getMessage());
                snap.put("exportImageCount", parsed.images.size());
                snap.put("status","partial");
                snap.withArray("gaps").add("导出兜底未包含完整评论及附件清单");
                if (imageDir != null) {
                    java.util.Map<String,Path> embedded=new java.util.HashMap<>();
                    for (var e:parsed.images.entrySet()) {
                        Path file=imageDir.resolve(safeName(e.getKey())+".png");Files.write(file,e.getValue());embedded.put(e.getKey(),file);
                    }
                    java.util.Map<String,Path> byFilename=new java.util.HashMap<>();
                    for(JsonNode item:snap.path("images")) {
                        String source=item.path("originalUrl").asText();Path file=embedded.get(source.substring(source.lastIndexOf('/')+1));
                        if(file!=null&&!item.path("placeholder").asBoolean()){((ObjectNode)item).put("localPath",file.toString());((ObjectNode)item).put("status","ready");byFilename.put(item.path("filename").asText(),file);}
                    }
                    for(JsonNode item:snap.path("images")) {
                        Path existing=byFilename.get(item.path("filename").asText());
                        if(existing!=null&&!item.has("localPath")){((ObjectNode)item).put("localPath",existing.toString());((ObjectNode)item).put("status","ready");}
                    }
                    for(JsonNode item:snap.path("images"))if(!item.path("placeholder").asBoolean()&&!item.has("localPath"))snap.withArray("gaps").add("导出中的图片未映射："+item.path("filename").asText());
                }
                return snap;
            } catch (Exception exportFailed) {
                throw new IllegalStateException("Wiki REST 与导出均失败: " + restFailed.getMessage()
                        + " / " + exportFailed.getMessage());
            }
        }
    }

    private void downloadImages(ObjectNode snap, Path imageDir) {
        for (JsonNode img : snap.path("images")) {
            if (img.path("placeholder").asBoolean(false)) continue;
            String filename = img.path("filename").asText("");
            String src = img.path("originalUrl").asText("");
            if (filename.toLowerCase().endsWith(".exe")) continue;
            try {
                byte[] bytes;
                if (src.startsWith("http")) bytes = wiki.getBytes(src);
                else if (src.startsWith("/")) bytes = wiki.getBytes(src);
                else {
                    String dl = null;
                    for (JsonNode a : snap.path("attachments")) {
                        if (filename.equalsIgnoreCase(a.path("title").asText())) {
                            dl = a.path("downloadPath").asText("");
                            break;
                        }
                    }
                    if (dl == null || dl.isBlank()) throw new IllegalStateException("未找到图片附件来源");
                    bytes = wiki.getBytes(dl.startsWith("http") ? dl : dl);
                }
                if (!isPngOrJpeg(bytes)) throw new IllegalStateException("返回内容不是 PNG/JPEG 图片");
                Path file = imageDir.resolve(AnalysisContract.digest(src+"\n"+img.path("attachmentVersion").asText()).substring(0,16)+"-"+safeName(filename.isBlank() ? img.path("assetId").asText("img") : filename));
                Files.write(file, bytes);
                ((ObjectNode) img).put("localPath", file.toString());
                ((ObjectNode) img).put("status", "ready");
            } catch (Exception e) {
                ((ObjectNode) img).put("status", "download-failed");
                snap.withArray("gaps").add("图片 " + filename + " 下载失败: " + e.getMessage());
            }
        }
    }

    private static boolean isPngOrJpeg(byte[] b) {
        if (b.length >= 8 && b[0] == (byte) 0x89 && b[1] == 0x50) return true;
        return b.length >= 3 && b[0] == (byte) 0xFF && b[1] == (byte) 0xD8 && b[2] == (byte) 0xFF;
    }

    private static String safeName(String name) {
        String n = name == null ? "file" : name.replaceAll("[^a-zA-Z0-9._-]", "_");
        return n.isBlank() ? "file" : n;
    }
}
