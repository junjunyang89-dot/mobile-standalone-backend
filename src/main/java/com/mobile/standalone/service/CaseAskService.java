package com.mobile.standalone.service;

import com.mobile.standalone.entity.AiTestCase;
import com.mobile.standalone.service.apitest.RequirementEndpointService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用例「问 AI」对着本机业务仓库检索后直接作答，不经过千问。
 * 弹窗里的对话进不了 Cursor IDE 那个聊天窗；这里做的是同一类事：读仓库、给证据。
 */
@Service
@RequiredArgsConstructor
public class CaseAskService {

    private static final Pattern SOURCE_REF = Pattern.compile(
            "([A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)+\\.[A-Za-z]{1,8}):(\\d+)");

    private final AiTestCaseService aiTestCaseService;
    private final RequirementEndpointService requirementEndpointService;
    private final CaseAskCodeSearch codeSearch;

    public Map<String, Object> ask(Long caseId, String question) {
        return ask(caseId, null, question, List.of());
    }

    public Map<String, Object> ask(Long caseId, String requirementNo, String question) {
        return ask(caseId, requirementNo, question, List.of());
    }

    /**
     * {@code caseId} 和 {@code requirementNo} 至少一个。
     * {@code history} 是此前对话，方便没找全时接着补一句再搜。
     */
    public Map<String, Object> ask(Long caseId, String requirementNo, String question,
                                   List<Map<String, String>> history) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("要问什么？把现象写清楚，比如哪一页、哪一场、和其他场次差在哪");
        }
        List<Map<String, String>> turns = history == null ? List.of() : history;
        if (caseId != null) {
            AiTestCase c = aiTestCaseService.getById(caseId);
            if (c == null) {
                throw new IllegalArgumentException("用例不存在");
            }
            return complete(c, blankToNull(c.getRequirementNo()), question.trim(), turns);
        }
        String reqNo = blankToNull(requirementNo);
        if (reqNo == null) {
            throw new IllegalArgumentException("先选一条用例，或在需求页上问");
        }
        return complete(null, reqNo, question.trim(), turns);
    }

    private Map<String, Object> complete(AiTestCase c, String reqNo, String question,
                                         List<Map<String, String>> history) {
        List<Map<String, Object>> endpoints = reqNo == null
                ? List.of()
                : requirementEndpointService.listByRequirement(reqNo);
        List<String> paths = endpointPaths(endpoints);
        String searchBlob = searchBlob(question, history);
        List<CaseAskCodeSearch.Hit> hits = new ArrayList<>(codeSearch.search(searchBlob, paths));
        List<CaseAskCodeSearch.Hit> prior = codeSearch.reopen(sourceRefs(history));
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (CaseAskCodeSearch.Hit h : hits) {
            seen.add(h.file() + ":" + h.line());
        }
        for (CaseAskCodeSearch.Hit h : prior) {
            if (seen.add(h.file() + ":" + h.line())) {
                hits.add(h);
            }
        }
        hits.sort((a, b) -> Integer.compare(CaseAskCodeSearch.score(b), CaseAskCodeSearch.score(a)));
        String answer = CaseAskLocalAnalysis.answer(hits, paths, question);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("answer", answer);
        out.put("model", "本机代码分析（不经千问）");
        out.put("caseId", c == null ? "" : nvl(c.getCaseId()));
        out.put("requirementNo", reqNo == null ? "" : reqNo);
        out.put("sources", hits.stream()
                .limit(8)
                .map(h -> h.repo() + "/" + h.file() + ":" + h.line())
                .toList());
        return out;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    static List<String> sourceRefs(List<Map<String, String>> history) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (history == null) {
            return List.of();
        }
        for (Map<String, String> turn : history) {
            if (turn == null) {
                continue;
            }
            collectSourceRefs(out, turn.get("sources"));
            if (!"user".equals(turn.getOrDefault("role", "user"))) {
                collectSourceRefs(out, turn.get("content"));
            }
        }
        return new ArrayList<>(out);
    }

    private static void collectSourceRefs(LinkedHashSet<String> out, String blob) {
        if (blob == null || blob.isBlank()) {
            return;
        }
        Matcher m = SOURCE_REF.matcher(blob);
        while (m.find()) {
            out.add(m.group(1) + ":" + m.group(2));
        }
    }

    static String searchBlob(String question, List<Map<String, String>> history) {
        StringBuilder sb = new StringBuilder(question == null ? "" : question);
        if (history != null) {
            for (Map<String, String> turn : history) {
                if (turn == null) {
                    continue;
                }
                String role = turn.getOrDefault("role", "user");
                if (!"user".equals(role)) {
                    continue;
                }
                String content = turn.get("content");
                if (content != null && !content.isBlank()) {
                    sb.append('\n').append(content);
                }
            }
        }
        return sb.toString();
    }

    public static List<Map<String, String>> parseHistory(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            Object role = m.get("role");
            Object content = m.get("content");
            if (content == null) {
                continue;
            }
            Map<String, String> turn = new LinkedHashMap<>();
            turn.put("role", role == null ? "user" : String.valueOf(role));
            turn.put("content", String.valueOf(content));
            String sources = stringifySources(m.get("sources"));
            if (sources != null) {
                turn.put("sources", sources);
            }
            out.add(turn);
            if (out.size() >= 16) {
                break;
            }
        }
        return out;
    }

    private static String stringifySources(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    parts.add(String.valueOf(o).trim());
                }
            }
            return parts.isEmpty() ? null : String.join("\n", parts);
        }
        String s = String.valueOf(raw).trim();
        return s.isEmpty() ? null : s;
    }

    private static List<String> endpointPaths(List<Map<String, Object>> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (Map<String, Object> ep : endpoints) {
            String path = str(ep.get("path"));
            if (path.length() >= 6) {
                paths.add(path);
            }
        }
        return paths;
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() || "null".equalsIgnoreCase(t) ? null : t;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
