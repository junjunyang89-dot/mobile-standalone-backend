package com.mobile.standalone.service.analysis;

import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Confluence's Word export is MIME HTML, including octet-stream image parts. */
final class WikiMimeExportParser {
    private WikiMimeExportParser(){}
    static boolean looksLikeMime(byte[] raw){if(raw==null)return false;String head=new String(raw,0,Math.min(raw.length,1000),StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);return head.contains("mime-version")||head.contains("multipart/related");}
    static Parsed parse(byte[] raw){
        Parsed out=new Parsed();if(raw==null)return out;
        if(!looksLikeMime(raw)){out.html=new String(raw,StandardCharsets.UTF_8);return out;}
        try{walk(new MimeMessage(Session.getInstance(new Properties()),new ByteArrayInputStream(raw)),out);}
        catch(Exception e){throw new IllegalArgumentException("Wiki MIME 导出文件无法解析",e);}return out;
    }
    private static void walk(Part part,Parsed out)throws Exception{
        if(part.isMimeType("multipart/*")){Multipart multi=(Multipart)part.getContent();for(int i=0;i<multi.getCount();i++)walk(multi.getBodyPart(i),out);return;}
        if(part.isMimeType("text/html")){Object body=part.getContent();out.html=body instanceof String?(String)body:new String(part.getInputStream().readNBytes(10_000_000),StandardCharsets.UTF_8);return;}
        byte[] bytes;try(InputStream in=part.getInputStream()){bytes=in.readNBytes(20_000_001);}if(bytes.length>20_000_000){out.notes.add("嵌入资源过大，未解析");return;}
        boolean image=bytes.length>8&&((bytes[0]==(byte)0x89&&bytes[1]==0x50)||(bytes[0]==(byte)0xff&&bytes[1]==(byte)0xd8));
        if(image){String[] location=part.getHeader("Content-Location");String loc=location==null?Objects.toString(part.getFileName(),""):location[0];loc=loc.substring(loc.lastIndexOf('/')+1);if(!loc.isBlank())out.images.put(loc,bytes);}
    }
    static final class Parsed{String html="";Map<String,byte[]> images=new LinkedHashMap<>();List<String> notes=new ArrayList<>();}
}
