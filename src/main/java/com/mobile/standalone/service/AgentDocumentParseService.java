package com.mobile.standalone.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 把上传文档解析成纯文本，供知识库 / Agent 使用。
 */
@Service
@Slf4j
public class AgentDocumentParseService {

    private static final Set<String> TEXT_EXTS = Set.of(
            "txt", "md", "markdown", "json", "csv", "html", "htm", "xml", "log"
    );
    private static final Set<String> OFFICE_EXTS = Set.of("docx", "pdf", "xlsx", "xls");
    private static final Set<String> IMAGE_EXTS = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp");
    private static final long MAX_CHARS = 200_000;

    public String extractText(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }
        String name = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String ext = extension(name);
        if (isImageExt(ext) || isImageContentType(file.getContentType())) {
            throw new IllegalArgumentException("图片请走图片上传逻辑，勿按文本解析");
        }
        try (InputStream in = file.getInputStream()) {
            String text = switch (ext) {
                case "docx" -> extractDocx(in);
                case "pdf" -> extractPdf(in);
                case "xlsx", "xls" -> extractExcel(in);
                default -> {
                    if (!TEXT_EXTS.contains(ext) && !ext.isEmpty()) {
                        log.info("[agent-doc] 未知后缀 .{}，按 UTF-8 文本解析: {}", ext, name);
                    }
                    yield extractPlain(in);
                }
            };
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("未能从文件中解析出文本：" + name);
            }
            text = text.trim();
            if (text.length() > MAX_CHARS) {
                text = text.substring(0, (int) MAX_CHARS) + "\n\n…(内容过长已截断)";
            }
            return text;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解析文件失败「" + name + "」: " + e.getMessage(), e);
        }
    }

    public static String topicFromFilename(String filename) {
        if (filename == null || filename.isBlank()) return "上传文档";
        String n = filename.trim();
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        return n.isBlank() ? "上传文档" : n;
    }

    public static boolean isSupported(String filename) {
        String ext = extension(filename == null ? "" : filename);
        return TEXT_EXTS.contains(ext) || OFFICE_EXTS.contains(ext) || IMAGE_EXTS.contains(ext) || ext.isEmpty();
    }

    public static boolean isImage(MultipartFile file) {
        if (file == null) return false;
        if (isImageContentType(file.getContentType())) return true;
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        return isImageExt(extension(name));
    }

    public static boolean isImageExt(String ext) {
        return IMAGE_EXTS.contains(ext == null ? "" : ext.toLowerCase(Locale.ROOT));
    }

    public static boolean isImageContentType(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("image/");
    }

    public static String extension(String name) {
        int i = name.lastIndexOf('.');
        if (i < 0 || i == name.length() - 1) return "";
        return name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static String extractPlain(InputStream in) throws Exception {
        byte[] bytes = in.readAllBytes();
        Charset cs = StandardCharsets.UTF_8;
        // 简单探测 UTF-16 BOM
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xfe) {
            cs = StandardCharsets.UTF_16LE;
        } else if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xff) == 0xff) {
            cs = StandardCharsets.UTF_16BE;
        }
        return new String(bytes, cs);
    }

    private static String extractDocx(InputStream in) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(in)) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = p.getText();
                if (t != null && !t.isBlank()) sb.append(t).append('\n');
            }
            return sb.toString();
        }
    }

    private static String extractPdf(InputStream in) throws Exception {
        byte[] bytes = in.readAllBytes();
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }

    private static String extractExcel(InputStream in) throws Exception {
        DataFormatter fmt = new DataFormatter();
        StringBuilder sb = new StringBuilder();
        try (Workbook wb = WorkbookFactory.create(in)) {
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                sb.append("## ").append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    StringBuilder line = new StringBuilder();
                    for (Cell cell : row) {
                        if (line.length() > 0) line.append('\t');
                        line.append(fmt.formatCellValue(cell));
                    }
                    if (line.length() > 0) sb.append(line).append('\n');
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
