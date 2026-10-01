package com.mobile.standalone.service.h5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;

/**
 * 在当前 H5 文档里按文案或 CSS 找到节点，返回中心点（CSS 像素）并已尝试 click/focus。
 */
public final class H5DomClick {

    private static final ObjectMapper JSON = new ObjectMapper();

    private H5DomClick() {
    }

    public record Hit(boolean ok, double x, double y, double viewW, double viewH, String tag, String message) {
    }

    public static String expression(String text, String css) {
        return expression(text, css, null);
    }

    public static String expression(String text, String css, String typeValue) {
        String t = jsonString(text == null ? "" : text.trim());
        String s = jsonString(css == null ? "" : css.trim());
        String v = jsonString(typeValue == null ? "" : typeValue);
        return "(function(){"
                + "const text=" + t + ";"
                + "const css=" + s + ";"
                + "const typeValue=" + v + ";"
                + "function vis(el){if(!el)return false;const r=el.getBoundingClientRect();"
                + "const st=getComputedStyle(el);"
                + "return r.width>1&&r.height>1&&st.visibility!=='hidden'&&st.display!=='none';}"
                + "function scoreOf(el){"
                + "const ph=(el.getAttribute('placeholder')||el.getAttribute('aria-label')||el.getAttribute('title')||'');"
                + "const val=(el.value!=null?String(el.value):'');"
                + "let label='';try{label=(el.innerText||el.textContent||'').replace(/\\s+/g,' ').trim();}catch(e){}"
                + "if(css)return vis(el)?0:99999;"
                + "if(!text)return 99999;"
                + "if(ph.indexOf(text)>=0)return 0;"
                + "if(val.indexOf(text)>=0)return 1;"
                + "if(label===text)return 2;"
                + "if(label.indexOf(text)>=0&&label.length<48)return 10+label.length;"
                + "return 99999;}"
                + "let el=null;"
                + "if(css){el=document.querySelector(css);}"
                + "else{"
                + "const nodes=document.querySelectorAll('input,textarea,[contenteditable],button,a,[role=button],span,div,p');"
                + "let best=null,bestScore=99999;"
                + "for(const n of nodes){if(!vis(n))continue;const sc=scoreOf(n);if(sc<bestScore){best=n;bestScore=sc;}}"
                + "el=best;}"
                + "if(!el)return {ok:false,msg:'找不到'};"
                + "try{el.scrollIntoView({block:'center',inline:'nearest'});}catch(e){}"
                + "const r=el.getBoundingClientRect();"
                + "try{el.focus();}catch(e){}"
                + "try{el.click();}catch(e){}"
                + "if(typeValue!==''){"
                + "try{"
                + "if(el.isContentEditable){el.focus();document.execCommand('selectAll',false,null);"
                + "document.execCommand('insertText',false,typeValue);}"
                + "else{const proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;"
                + "const desc=Object.getOwnPropertyDescriptor(proto,'value');"
                + "if(desc&&desc.set){desc.set.call(el,typeValue);}else{el.value=typeValue;}"
                + "el.dispatchEvent(new Event('input',{bubbles:true}));"
                + "el.dispatchEvent(new Event('change',{bubbles:true}));}"
                + "}catch(e){return {ok:false,msg:'写入失败:'+e.message};}}"
                + "return {ok:true,x:r.x+r.width/2,y:r.y+r.height/2,vw:innerWidth,vh:innerHeight,"
                + "dpr:devicePixelRatio||1,tag:el.tagName,typed:typeValue!==''};"
                + "})()";
    }

    public static Hit run(String wsUrl, String text, String css, Duration timeout) throws Exception {
        return run(wsUrl, text, css, null, timeout);
    }

    public static Hit run(String wsUrl, String text, String css, String typeValue, Duration timeout)
            throws Exception {
        JsonNode v = H5CdpClient.evaluate(wsUrl, expression(text, css, typeValue), timeout);
        return fromNode(v, "CDP 无返回");
    }

    public static Hit fromRaw(Object raw) {
        if (raw == null) {
            return new Hit(false, 0, 0, 0, 0, "", "脚本无返回");
        }
        return fromNode(JSON.valueToTree(raw), "脚本无返回");
    }

    static Hit fromNode(JsonNode v, String emptyMsg) {
        if (v == null || v.isMissingNode() || v.isNull()) {
            return new Hit(false, 0, 0, 0, 0, "", emptyMsg);
        }
        if (!v.path("ok").asBoolean(false)) {
            return new Hit(false, 0, 0, 0, 0, "", v.path("msg").asText("找不到"));
        }
        return new Hit(true,
                v.path("x").asDouble(),
                v.path("y").asDouble(),
                v.path("vw").asDouble(0),
                v.path("vh").asDouble(0),
                v.path("tag").asText(""),
                "");
    }

    static String jsonString(String raw) {
        try {
            return JSON.writeValueAsString(raw);
        } catch (Exception e) {
            return "\"\"";
        }
    }
}
