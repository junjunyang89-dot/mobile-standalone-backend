package com.mobile.standalone.service.analysis;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.mobile.standalone.config.AnalysisBridgeProperties;
import org.jsoup.Jsoup;
import java.util.*;
import java.util.regex.*;

final class JiraAnalysisParser {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Pattern URL=Pattern.compile("https?://[^\\s<>\\\"\\]\\[|]+",Pattern.CASE_INSENSITIVE);
    static ObjectNode parse(String key,String group,JsonNode issue,AnalysisBridgeProperties props,String jiraBase){
        ObjectNode out=JSON.createObjectNode();out.put("contractVersion","2");out.put("businessGroup",group);out.put("requirementKey",key);
        out.put("materialVersion","jira-"+Objects.toString(issue.path("fields").path("updated").asText(),"")+"-"+UUID.randomUUID());
        ArrayNode materials=out.putArray("materials"),repos=out.putArray("repositories"),gaps=out.putArray("discoveryGaps");
        for(JsonNode gap:issue.path("collectionGaps"))gaps.add(gap);
        String summary=issue.path("fields").path("summary").asText(key);out.put("summary",summary);
        ObjectNode main=material(materials,"jira-description","requirement",summary);
        main.put("url",jiraBase+"/browse/"+key);main.put("content",text(issue.path("fields").path("description")));
        StringBuilder all=new StringBuilder(main.path("content").asText());
        var fields=issue.path("fields").fields();
        while(fields.hasNext()){
            var f=fields.next();String name=issue.path("names").path(f.getKey()).asText(f.getKey());
            if(f.getKey().startsWith("customfield_") && name.matches("(?is).*(需求|设计|分支|项目|提测|验收|sql|数据库|配置|文档|branch|repository|requirement|figma).*") && !f.getValue().isNull()){
                String value=text(f.getValue());if(!value.isBlank()){var m=material(materials,"jira-"+f.getKey(),"changeNote",name);m.put("content",value);m.put("confirmation","需确认字段适用范围");all.append("\n").append(value);}
            }
        }
        for(JsonNode c:issue.path("analysisComments")){
            String value=text(c.path("body"));if(value.isBlank())continue;
            var m=material(materials,"jira-comment-"+c.path("id").asText(),"changeNote","开发及产品评论补充");m.put("content",value);m.put("author",c.path("author").path("displayName").asText());m.put("version",c.path("updated").asText());m.put("confirmation","评论补充，需确认当前有效性");all.append("\n").append(value);
        }
        Set<String> links=new LinkedHashSet<>();Matcher urls=URL.matcher(all);
        while(urls.find()){String url=urls.group().replaceAll("[),，。；;]+$","");if(links.add(url)){
            try {var u=java.net.URI.create(url);String host=Objects.toString(u.getHost(),"");
                if(host.equalsIgnoreCase("wiki.shdev.net") || host.equalsIgnoreCase("figma.com") || host.endsWith(".figma.com")){
                    var m=material(materials,"linked-"+AnalysisContract.digest(url).substring(0,12),host.contains("figma")?"design":"requirement",host.contains("figma")?"Figma 设计稿":"Wiki 需求文档");m.put("url",url);
                }
            }catch(Exception ignored){}
        }}
        Map<String,String> aliases=new LinkedHashMap<>();
        Set<String> projects=new LinkedHashSet<>(props.getRepositories().keySet());projects.addAll(props.getRepositoryUrls().keySet());
        projects.removeIf(p->props.getRepositoryGroups().containsKey(p) && !Set.of(group,"SHARED").contains(props.getRepositoryGroups().get(p)));
        projects.forEach(p->aliases.put(p,p));props.getRepositoryAliases().forEach((a,p)->{
            if(!projects.contains(p))return;
            if(a.contains(":")){if(a.startsWith(group+":"))aliases.put(a.substring(group.length()+1),p);}
            else aliases.put(a,p);
        });
        Set<String> seen=new LinkedHashSet<>();
        for(var alias:aliases.entrySet()){
            Pattern match=Pattern.compile("(?im)(?<![A-Za-z0-9_./-])"+Pattern.quote(alias.getKey())+"[ \\t]*(?:项目)?[ \\t]*[:：|]?[ \\t]*(?:(?:分支|branch)[ \\t]*[:：]?[ \\t]*)?(?:\\R[ \\t]*)?([A-Za-z0-9][A-Za-z0-9_./-]{0,239})");
            Matcher found=match.matcher(all);
            while(found.find()){
                String branch=found.group(1);if(!looksLikeBranch(branch)||!seen.add(alias.getValue()+"\n"+branch))continue;
                var r=repos.addObject();r.put("projectId",alias.getValue());r.put("baseRef",props.getRepositoryBaselines().getOrDefault(alias.getValue(),""));r.put("targetRef",branch);r.put("evidence",found.group());
            }
        }
        Matcher unknown=Pattern.compile("(?im)^\\s*([A-Za-z][A-Za-z0-9_.-]*)\\s*[:：]\\s*([A-Za-z0-9][A-Za-z0-9_./-]{0,239})").matcher(all);
        while(unknown.find())if(looksLikeBranch(unknown.group(2))&&!aliases.containsKey(unknown.group(1)))gaps.add("发现未映射项目候选："+unknown.group()+"；请确认项目 ID 并配置仓库");
        if(repos.isEmpty())gaps.add("未可靠识别项目与分支，请手动补充；不自动选择所有仓库");
        for(JsonNode repo:repos)if(repo.path("baseRef").asText().isBlank())gaps.add("项目 "+repo.path("projectId").asText()+" 缺少比较基线，请确认后填写");
        // Preserve attachments as a manifest, do not silently claim binary documents were read.
        out.set("jiraAttachments",issue.path("fields").path("attachment").deepCopy());
        if(materials.size()>50)throw AnalysisContract.invalid("JIRA 材料超过 50 项，请先缩小本单材料范围");
        return out;
    }
    static boolean looksLikeBranch(String s){return !s.contains("://")&&!s.contains("..")&&(s.contains("/")||s.matches("(?i)(dev|release|feature|hotfix|fix|testing|test|master|main|develop)[A-Za-z0-9_.-]*"));}
    static String text(JsonNode value){
        if(value.isTextual())return value.asText();
        if(value.isArray()){List<String> all=new ArrayList<>();value.forEach(v->all.add(text(v)));return String.join("\n",all);}
        if(value.isObject()){
            if(value.has("text"))return value.path("text").asText();if(value.has("content"))return text(value.get("content"));
            for(String key:List.of("value","name","url"))if(value.path(key).isTextual())return value.path(key).asText();
        }
        return "";
    }
    private static ObjectNode material(ArrayNode list,String id,String type,String title){var m=list.addObject();m.put("id",id);m.put("type",type);m.put("title",title);return m;}
}
