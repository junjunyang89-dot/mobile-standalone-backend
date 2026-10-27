package com.mobile.standalone.service.smartapp;

import com.fasterxml.jackson.databind.*;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.*;
import java.util.regex.*;
import static com.mobile.standalone.service.smartapp.SmartAppModels.*;

/** Android / WDA XML and Harmony hierarchy JSON are normalized before matching. */
public final class SmartAppTree {
    private static final Pattern NUMBER = Pattern.compile("-?\\d+");
    private SmartAppTree() {}
    public static List<Node> parse(String source) {
        if (source == null || source.isBlank()) throw new IllegalStateException("设备未返回页面结构，请检查驱动连接");
        if (source.length() > 8_000_000) throw new IllegalStateException("页面结构过大");
        List<Node> out = new ArrayList<>();
        try {
            if (source.stripLeading().startsWith("<")) {
                var f = DocumentBuilderFactory.newInstance();
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                f.setFeature("http://xml.org/sax/features/external-general-entities", false);
                f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                f.setXIncludeAware(false); f.setExpandEntityReferences(false);
                xml(f.newDocumentBuilder().parse(new InputSource(new StringReader(source))).getDocumentElement(), "", out, 0);
            } else json(new ObjectMapper().readTree(source), "/root[1]", out, 0);
            return List.copyOf(out);
        } catch (Exception e) { throw new IllegalStateException("无法解析页面结构: " + e.getMessage(), e); }
    }
    private static void limit(int depth, List<Node> out) {
        if (depth > 250 || out.size() > 25000) throw new IllegalStateException("页面层级超出解析上限");
    }
    private static void xml(Element e, String parent, List<Node> out, int depth) {
        limit(depth, out);
        int nth = 1;
        for (org.w3c.dom.Node p=e.getPreviousSibling(); p!=null; p=p.getPreviousSibling())
            if (p instanceof Element pe && pe.getTagName().equals(e.getTagName())) nth++;
        String path = parent + "/" + e.getTagName() + "[" + nth + "]";
        String type = first(e.getAttribute("class"), e.getAttribute("type"), e.getTagName());
        boolean hidden = "false".equals(e.getAttribute("displayed")) || "false".equals(e.getAttribute("visible"));
        if (!hidden) {
            int[] b = bounds(e.getAttribute("bounds"));
            if (b == null && e.hasAttribute("x")) b = new int[]{num(e.getAttribute("x")),num(e.getAttribute("y")),num(e.getAttribute("x"))+num(e.getAttribute("width")),num(e.getAttribute("y"))+num(e.getAttribute("height"))};
            if (b==null) b=new int[4];
            out.add(new Node(path, first(e.getAttribute("text"),e.getAttribute("label"),e.getAttribute("value")),
                    first(e.getAttribute("resource-id"),e.getAttribute("identifier"),e.getAttribute("name")),
                    first(e.getAttribute("content-desc"),e.getAttribute("label"),e.getAttribute("name")),type, editable(type),b[0],b[1],b[2],b[3]));
            for (org.w3c.dom.Node c=e.getFirstChild();c!=null;c=c.getNextSibling()) if(c instanceof Element ce) xml(ce,path,out,depth+1);
        }
    }
    private static void json(JsonNode n, String path, List<Node> out, int depth) {
        limit(depth,out);
        if(n.isArray()) { for(int i=0;i<n.size();i++) json(n.get(i),path+"/item["+(i+1)+"]",out,depth+1); return; }
        if(!n.isObject()) return;
        JsonNode a=n.has("attributes") ? n.get("attributes") : n;
        String type=a.path("type").asText("");
        int[] b=bounds(a.path("bounds").asText(""));
        if(b==null && a.path("bounds").isObject()) {var r=a.path("bounds");b=new int[]{r.path("left").asInt(),r.path("top").asInt(),r.path("right").asInt(),r.path("bottom").asInt()};}
        if(b==null)b=new int[4];
        if(!a.path("visible").asText("true").equals("false")) out.add(new Node(path,a.path("text").asText(""),first(a.path("id").asText(""),a.path("key").asText("")),a.path("description").asText(""),type,editable(type),b[0],b[1],b[2],b[3]));
        for(String key:List.of("children","hierarchy","root")) if(n.has(key)) json(n.get(key),path+"/"+key,out,depth+1);
    }
    public static List<Node> matches(List<Node> nodes, Target t) {
        if(t==null || (empty(t.text())&&empty(t.contains())&&empty(t.id())&&empty(t.description())&&empty(t.role()))) throw new IllegalArgumentException("目标需要 text、contains、id、description 或 role");
        List<Node> scopes=empty(t.withinText())? List.of():nodes.stream().filter(n->t.withinText().equals(n.text())).toList();
        return nodes.stream().filter(n->
                (empty(t.text()) || t.text().equals(n.text())) && (empty(t.contains()) || n.text().contains(t.contains())) &&
                (empty(t.id()) || t.id().equals(n.id())) && (empty(t.description()) || t.description().equals(n.description())) &&
                (empty(t.role()) || switch(t.role()){case "input"->n.editable();case "text"->!n.text().isBlank();default->throw new IllegalArgumentException("role 仅支持 input / text");}) &&
                (empty(t.withinText()) || scopes.stream().anyMatch(s->n.path().startsWith(s.path()+"/") || (s.bounded()&&n.bounded()&&n.left()>=s.left()&&n.right()<=s.right()&&n.top()>=s.top()&&n.bottom()<=s.bottom()))))
                .toList();
    }
    public static Node resolve(List<Node> nodes, Target t) {
        List<Node> found=matches(nodes,t);
        if(found.isEmpty()) throw new Missing("当前页面未找到目标");
        if(t.index()!=null){if(t.index()<0||t.index()>=found.size())throw new Missing("目标下标超出当前匹配数量 "+found.size());return found.get(t.index());}
        if(found.size()!=1) throw new Ambiguous("目标匹配 "+found.size()+" 个控件，请增加类型、区域或明确 index（从 0 开始）");
        return found.get(0);
    }
    public static class Missing extends IllegalStateException {public Missing(String m){super(m);}}
    public static class Ambiguous extends IllegalStateException {public Ambiguous(String m){super(m);}}
    private static boolean editable(String type){return type.contains("EditText")||type.contains("TextField")||type.contains("TextView")&&type.startsWith("XCUI")||type.contains("TextInput")||type.contains("TextArea");}
    private static int num(String s){try{return (int)Double.parseDouble(s);}catch(Exception e){return 0;}}
    private static int[] bounds(String s){Matcher m=NUMBER.matcher(s);int[] b=new int[4];int i=0;while(m.find()&&i<4)b[i++]=Integer.parseInt(m.group());return i==4?b:null;}
    static boolean empty(String s){return s==null||s.isBlank();}
    private static String first(String... ss){for(String s:ss)if(!empty(s))return s;return "";}
}
