package com.mobile.standalone.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把仓库命中整理成测试能直接读的结论，不再交给千问转述。
 */
final class CaseAskLocalAnalysis {

    private static final Pattern CONDITION = Pattern.compile(
            "(?i)(v-if|v-else-if|v-show)\\s*=|\\bisParsing\\b|\\bdemoStatus\\b");
    private static final Pattern URL_IN_CODE = Pattern.compile("url:\\s*['\"](/[^'\"]+)['\"]");
    private static final Pattern METHOD_IN_CODE = Pattern.compile(
            "method:\\s*['\"]?(get|post|put|delete)['\"]?", Pattern.CASE_INSENSITIVE);
    private static final Pattern MATCHDATA_ASSIGN = Pattern.compile("matchData\\.value\\s*=\\s*[^;\\n]+");
    private static final Pattern GET_MATCH_DETAIL = Pattern.compile("\\bgetMatchDetail\\s*\\(");
    private static final Pattern DEMO_IN_JSON = Pattern.compile("\"demoStatus\"\\s*:\\s*(\\d+)");

    private CaseAskLocalAnalysis() {
    }

    static String answer(List<CaseAskCodeSearch.Hit> hits, List<String> endpointPaths, String question) {
        if (hits == null || hits.isEmpty()) {
            return "本机仓库这一轮没有搜到相关代码。可以把上一轮提到的文件名、字段（例如 matchData、getMatchDetail）再说一遍，会带着一起搜。";
        }
        String q = question == null ? "" : question;
        boolean apiQ = CaseAskCodeSearch.looksLikeApiQuestion(q);
        List<String> apis = frontendApis(hits);
        List<String> assigns = assignments(hits);
        List<String> conditions = new ArrayList<>();
        for (CaseAskCodeSearch.Hit h : hits) {
            conditions.addAll(conditionLines(h.excerpt()));
        }

        StringBuilder sb = new StringBuilder();
        if (apiQ) {
            if (!apis.isEmpty() || assigns.stream().anyMatch(a -> a.contains("matchData"))) {
                sb.append("结论：");
                if (assigns.stream().anyMatch(a -> a.contains("matchData")) && apis.stream().anyMatch(a -> a.contains("/gld/deadlock/match/detail"))) {
                    sb.append("matchData 来自比赛详情页 GET /gld/deadlock/match/detail（getMatchDetail）。");
                    sb.append("axios 把整段 JSON 当作 res，页面执行 matchData.value = res.result || {}，");
                    sb.append("所以 matchData 就是响应里的 result 整棵对象，不是另有一个 matchData 字段。");
                } else if (!apis.isEmpty()) {
                    sb.append("从前端代码里读到这些请求。");
                } else {
                    sb.append("找到了 matchData 的赋值，但这一轮还没扫到对应的 url。");
                }
                sb.append('\n');
                if (!apis.isEmpty()) {
                    sb.append("前端请求：\n");
                    int n = 0;
                    for (String a : apis) {
                        if (n++ >= 8) {
                            break;
                        }
                        sb.append("- ").append(a).append('\n');
                    }
                }
                if (!assigns.isEmpty()) {
                    sb.append("赋值 / 调用：\n");
                    int n = 0;
                    for (String a : assigns) {
                        if (n++ >= 6) {
                            break;
                        }
                        sb.append("- ").append(a).append('\n');
                    }
                }
                sb.append("平台「涉及接口」是抓包/分支分析挂上的，不等于这个页面实际请求的 url 列表。\n");
            } else {
                sb.append("这一轮还没从前端 api 文件里读到 url。可以把文件名（例如 matchDetail.js）再说一遍。\n");
            }
        }

        boolean foundUi = conditions.stream().anyMatch(c ->
                c.contains("v-if") || c.contains("isParsing") || c.contains("demoStatus"))
                || excerptContains(hits, "demoStatus");
        String demoNote = demoStatusNote(hits, q, conditions);
        if (!demoNote.isEmpty()) {
            sb.append(demoNote);
        } else if (foundUi && !apiQ) {
            sb.append("结论：当比赛详情接口返回后满足相关条件时页面才露出该文案；其他场次没有，是因为字段不满足这些条件。\n");
            sb.append("从代码读到的判断 / 赋值：\n");
            int n = 0;
            for (String c : conditions) {
                if (n++ >= 12) {
                    break;
                }
                sb.append("- ").append(c).append('\n');
            }
        } else if (foundUi && apiQ) {
            sb.append("露出条件（上一轮已定位）：\n");
            int n = 0;
            for (String c : conditions) {
                if (n++ >= 8) {
                    break;
                }
                sb.append("- ").append(c).append('\n');
            }
        } else if (!apiQ) {
            sb.append("目前只定位到文案或接口，还没找到 v-if / demoStatus。可以补充文件名或字段继续搜。\n");
        }

        if (endpointPaths != null && !endpointPaths.isEmpty() && apiQ) {
            sb.append("本单已挂接口（抓包侧，供对照）：");
            sb.append(String.join("、", endpointPaths.stream().limit(6).toList()));
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    static String memo(List<CaseAskCodeSearch.Hit> hits, List<String> endpointPaths, String question) {
        StringBuilder sb = new StringBuilder();
        sb.append("【本机代码分析】\n");
        sb.append("测试问的是：").append(question == null ? "" : question.trim()).append('\n');
        if (endpointPaths != null && !endpointPaths.isEmpty()) {
            sb.append("对照接口：");
            sb.append(String.join("、", endpointPaths.stream().limit(8).toList()));
            sb.append('\n');
        }
        if (hits == null || hits.isEmpty()) {
            sb.append("本机仓库没有搜到相关代码。\n");
            return sb.toString();
        }
        sb.append(answer(hits, endpointPaths, question)).append('\n');
        sb.append("命中文件：\n");
        LinkedHashSet<String> files = new LinkedHashSet<>();
        for (CaseAskCodeSearch.Hit h : hits) {
            files.add(h.repo() + "/" + h.file() + ":" + h.line());
        }
        for (String f : files) {
            sb.append("- ").append(f).append('\n');
        }
        sb.append("证据片段：\n");
        sb.append(CaseAskCodeSearch.formatHits(hits)).append('\n');
        return sb.toString();
    }

    static List<String> frontendApis(List<CaseAskCodeSearch.Hit> hits) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (hits == null) {
            return List.of();
        }
        for (CaseAskCodeSearch.Hit h : hits) {
            String ex = h.excerpt() == null ? "" : h.excerpt();
            String[] lines = ex.split("\n");
            for (int i = 0; i < lines.length; i++) {
                Matcher um = URL_IN_CODE.matcher(stripLineNumber(lines[i]));
                if (!um.find()) {
                    continue;
                }
                String method = "GET";
                for (int j = i; j < Math.min(lines.length, i + 4); j++) {
                    Matcher mm = METHOD_IN_CODE.matcher(stripLineNumber(lines[j]));
                    if (mm.find()) {
                        method = mm.group(1).toUpperCase();
                        break;
                    }
                }
                out.add(method + " " + um.group(1) + "  (" + h.file() + ")");
            }
        }
        return new ArrayList<>(out);
    }

    static List<String> assignments(List<CaseAskCodeSearch.Hit> hits) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (hits == null) {
            return List.of();
        }
        for (CaseAskCodeSearch.Hit h : hits) {
            String ex = h.excerpt() == null ? "" : h.excerpt();
            Matcher am = MATCHDATA_ASSIGN.matcher(ex);
            while (am.find()) {
                out.add(trimLine(am.group()) + "  (" + h.file() + ")");
            }
            if (GET_MATCH_DETAIL.matcher(ex).find()) {
                out.add("getMatchDetail(...)  (" + h.file() + ")");
            }
        }
        return new ArrayList<>(out);
    }

    static List<String> conditionLines(String excerpt) {
        if (excerpt == null || excerpt.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String raw : excerpt.split("\n")) {
            String line = stripLineNumber(raw).trim();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("*") || line.startsWith("…")) {
                continue;
            }
            Matcher m = CONDITION.matcher(line);
            if (m.find()) {
                out.add(line.length() > 160 ? line.substring(0, 160) + "…" : line);
            }
        }
        return out;
    }

    /**
     * 文案露出和接口字段是同一条链：result.demoStatus → isParsing → 「数据陆续获取中」。
     * 问「哪个接口」时也要写出来，不能只答 res.result。
     */
    static String demoStatusNote(List<CaseAskCodeSearch.Hit> hits, String question, List<String> conditions) {
        boolean hasDemo = excerptContains(hits, "demoStatus")
                || (conditions != null && conditions.stream().anyMatch(c -> c.contains("demoStatus") || c.contains("isParsing")));
        Matcher json = DEMO_IN_JSON.matcher(question == null ? "" : question);
        boolean pasted = json.find();
        if (!hasDemo && !pasted) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("文案「数据陆续获取中，请耐心等待」由 isParsing 控制：");
        sb.append("isParsing.value = matchData.value.demoStatus == 0。");
        sb.append("demoStatus 在 result.demoStatus。");
        sb.append("值为 0 时显示这段字；非 0（例如 1）不显示。\n");
        if (pasted) {
            String v = json.group(1);
            sb.append("你贴的响应里 demoStatus 是 ").append(v).append("，");
            if ("0".equals(v)) {
                sb.append("所以这场会出这段字。\n");
            } else {
                sb.append("所以这场不会出这段字。\n");
            }
        }
        return sb.toString();
    }

    private static boolean excerptContains(List<CaseAskCodeSearch.Hit> hits, String needle) {
        if (hits == null || needle == null) {
            return false;
        }
        for (CaseAskCodeSearch.Hit h : hits) {
            String ex = h.excerpt();
            if (ex != null && ex.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String trimLine(String s) {
        String t = s.trim();
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }

    private static String stripLineNumber(String raw) {
        int bar = raw.indexOf('|');
        if (bar > 0 && bar < 8) {
            return raw.substring(bar + 1);
        }
        return raw;
    }
}
