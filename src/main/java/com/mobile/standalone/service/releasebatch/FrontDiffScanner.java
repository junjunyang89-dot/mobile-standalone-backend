package com.mobile.standalone.service.releasebatch;

import com.mobile.standalone.service.releasebatch.CallSiteScanner.Scope;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.CallSite;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.FileRow;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.FrontChange;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 前端 / 客户端代码两份目录对比：改了哪些文件、哪些页面，接口调用加了哪些、删了哪些。
 * <p>
 * 后端仓库只看里面自带的前端（js / ts / vue），Java 那部分已经由接口对比管了。
 */
public final class FrontDiffScanner {

    private static final int MAX_FILES = 300;
    private static final int MAX_PAGES = 40;
    private static final int MAX_CALLS = 200;
    private static final Pattern PAGE = Pattern.compile(
            "(?i)((^|/)(views?|pages?|screens?|layouts?|components?|activity|activities|fragments?|controllers?)/"
                    + "|(ViewController|Activity|Fragment|Page|Screen)\\.(swift|kt|java|m|dart|ets)$|\\.vue$)");

    private FrontDiffScanner() {
    }

    /** 两边都没有前端 / 客户端代码时返回 null */
    public static FrontChange diff(Path base, Path head, Scope scope, String project) throws IOException {
        Map<String, String> baseFiles = files(base, scope);
        Map<String, String> headFiles = files(head, scope);
        if (baseFiles.isEmpty() && headFiles.isEmpty()) {
            return null;
        }
        FrontChange fc = new FrontChange();
        Set<String> all = new TreeSet<>();
        all.addAll(baseFiles.keySet());
        all.addAll(headFiles.keySet());
        List<FileRow> rows = new ArrayList<>();
        Map<String, CallSite> added = new LinkedHashMap<>();
        Map<String, CallSite> removed = new LinkedHashMap<>();
        Set<String> pages = new LinkedHashSet<>();
        for (String rel : all) {
            String bAbs = baseFiles.get(rel);
            String hAbs = headFiles.get(rel);
            String b = bAbs == null ? null : CallSiteScanner.read(Path.of(bAbs));
            String h = hAbs == null ? null : CallSiteScanner.read(Path.of(hAbs));
            if (Objects.equals(b, h)) {
                continue;
            }
            FileRow row = new FileRow();
            row.setProject(project);
            row.setPath(rel);
            row.setStatus(b == null ? "ADDED" : h == null ? "DELETED" : "MODIFIED");
            rows.add(row);
            if (PAGE.matcher(rel).find()) {
                pages.add(rel);
            }
            String side = CallSiteScanner.sideOf(rel, scope);
            Map<String, CallSite> before = byPath(CallSiteScanner.extract(b, rel, side));
            Map<String, CallSite> after = byPath(CallSiteScanner.extract(h, rel, side));
            after.forEach((k, v) -> {
                if (!before.containsKey(k)) {
                    added.putIfAbsent(rel + "|" + k, v);
                }
            });
            before.forEach((k, v) -> {
                if (!after.containsKey(k)) {
                    removed.putIfAbsent(rel + "|" + k, v);
                }
            });
        }
        fc.setChangedFiles(rows.size());
        fc.setFiles(rows.size() > MAX_FILES ? new ArrayList<>(rows.subList(0, MAX_FILES)) : rows);
        fc.setPages(pages.stream().limit(MAX_PAGES).toList());
        fc.setAddedCalls(added.values().stream().limit(MAX_CALLS).toList());
        fc.setRemovedCalls(removed.values().stream().limit(MAX_CALLS).toList());
        if (rows.size() > MAX_FILES) {
            fc.getNotes().add("改动文件 " + rows.size() + " 个，只列了前 " + MAX_FILES + " 个");
        }
        if (rows.size() >= 1500) {
            fc.getNotes().add("前端改动文件特别多，可能两份目录不是同一个仓库，或者带了打包产物");
        }
        return fc;
    }

    /** 一句话：改了几个文件、几个页面、接口调用增删 */
    public static String summary(FrontChange fc) {
        if (fc == null) {
            return null;
        }
        if (fc.getChangedFiles() == 0) {
            return "前端代码没有差异";
        }
        List<String> parts = new ArrayList<>();
        parts.add("改了 " + fc.getChangedFiles() + " 个文件");
        if (!fc.getPages().isEmpty()) {
            parts.add("页面 / 组件 " + fc.getPages().size() + " 个");
        }
        if (!fc.getAddedCalls().isEmpty()) {
            parts.add("新增接口调用 " + fc.getAddedCalls().size());
        }
        if (!fc.getRemovedCalls().isEmpty()) {
            parts.add("删掉接口调用 " + fc.getRemovedCalls().size());
        }
        return String.join(" · ", parts);
    }

    private static Map<String, String> files(Path root, Scope scope) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String[] f : CallSiteScanner.listFiles(root)) {
            String side = CallSiteScanner.sideOf(f[0], scope);
            if (side != null && !CallSiteScanner.BACK.equals(side)) {
                out.put(f[0], f[1]);
            }
        }
        return out;
    }

    private static Map<String, CallSite> byPath(List<CallSite> sites) {
        Map<String, CallSite> out = new LinkedHashMap<>();
        for (CallSite c : sites) {
            out.putIfAbsent(c.getPath(), c);
        }
        return out;
    }
}
