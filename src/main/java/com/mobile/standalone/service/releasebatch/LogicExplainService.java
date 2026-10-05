package com.mobile.standalone.service.releasebatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.service.ai.QwenChatClient;
import com.mobile.standalone.service.apichange.MethodLogicDiff;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Assessment;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Batch;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Factor;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.LogicExplain;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.LogicItem;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Ticket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 把一个方法的逻辑改动翻译成测试看得懂的业务规则：旧规则 / 新规则 / 计算因子 / 影响 / 测试重点。
 * <p>
 * 规则引擎（{@link MethodLogicDiff}）只能认出「判断条件改了」「公式改了」这类形状，
 * 说不出「综合互动分拆成了点赞分 + 回复分」这种业务含义；这一步交给大模型读两版完整方法来说。
 * 读不到完整方法时退回到改动行，并在结果里写明，不装作看全了。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogicExplainService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_SRC = 8000;
    private static final Set<String> SKIP_DIRS = Set.of(".git", "target", "build", "out", "node_modules", ".idea");

    private final ReleaseBatchService batches;
    private final QwenChatClient qwen;

    public Batch explain(Long id, String key, String project, String className, String member) {
        if (!qwen.isConfigured()) {
            throw new IllegalStateException("AI 还没配置（千问密钥），翻译不了；先看下面的改动行");
        }
        Batch b = batches.get(id);
        Ticket t = b.getTickets().stream().filter(x -> x.getKey().equalsIgnoreCase(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("批次里没有单 " + key));
        String gk = groupKey(project, className, member);
        List<LogicItem> items = itemsOf(t.getAssessment(), gk);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("没找到 " + className + "." + member + " 的改动，重新分析一下再试");
        }
        Sources src = sources(id, key, project, className, member);
        String answer;
        try {
            answer = qwen.chat(prompt(t, className, member, items, src));
        } catch (Exception e) {
            throw new IllegalStateException("AI 调用失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        LogicExplain ex = parse(answer);
        ex.setCodeSeen(src.seen());
        ex.setFingerprint(fingerprint(items));
        ex.setModel(qwen.modelId());
        ex.setAt(LocalDateTime.now());
        return batches.saveLogicExplain(id, key, gk, ex);
    }

    // ==================== 分组和指纹（前端按同样的 key 取） ====================

    static String groupKey(String project, String className, String member) {
        return project + "|" + className + "." + member;
    }

    static List<LogicItem> itemsOf(Assessment a, String groupKey) {
        List<LogicItem> out = new ArrayList<>();
        if (a == null) {
            return out;
        }
        for (LogicItem li : a.getLogicDetails()) {
            if (groupKey(li.getProject(), li.getClassName(), li.getMember()).equals(groupKey)) {
                out.add(li);
            }
        }
        return out;
    }

    static String fingerprint(List<LogicItem> items) {
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (LogicItem li : items) {
            sb.append(li.getKind()).append('\u0001').append(li.getBefore()).append('\u0001').append(li.getAfter()).append('\u0002');
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    // ==================== 取两版完整方法 ====================

    record Sources(String before, String after, String seen) {
    }

    private Sources sources(Long id, String key, String project, String className, String member) {
        ReleaseBatchService.CodeDirs dirs = batches.codeOf(id, key, project);
        if (dirs.missing() != null) {
            return new Sources(null, null, "没拿到代码（" + dirs.missing() + "），只看了改动行");
        }
        try {
            Path headFile = find(dirs.head(), className, member);
            if (headFile == null) {
                return new Sources(null, null, "代码里没找到 " + className + ".java，只看了改动行");
            }
            String after = MethodLogicDiff.memberSource(read(headFile), member);
            Path rel = dirs.head().relativize(headFile);
            Path baseFile = Files.isRegularFile(dirs.base().resolve(rel)) ? dirs.base().resolve(rel) : find(dirs.base(), className, member);
            String before = baseFile == null ? null : MethodLogicDiff.memberSource(read(baseFile), member);
            String seen = after != null && before != null ? "读了改前、改后两版完整方法"
                    : after != null ? "读了改后完整方法（改前没有这个方法 / 没找到）" : "没定位到方法源码，只看了改动行";
            return new Sources(cut(before), cut(after), seen);
        } catch (IOException e) {
            return new Sources(null, null, "读代码失败（" + e.getMessage() + "），只看了改动行");
        }
    }

    private static Path find(Path root, String className, String member) throws IOException {
        if (root == null || !Files.isDirectory(root)) {
            return null;
        }
        String name = className + ".java";
        List<Path> hits = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(p -> p.getFileName().toString().equals(name))
                    .filter(p -> {
                        for (Path part : root.relativize(p)) {
                            if (SKIP_DIRS.contains(part.toString())) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .limit(10)
                    .forEach(hits::add);
        }
        for (Path p : hits) {
            if (read(p).contains(member)) {
                return p;
            }
        }
        return hits.isEmpty() ? null : hits.get(0);
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static String cut(String s) {
        return s == null || s.length() <= MAX_SRC ? s : s.substring(0, MAX_SRC) + "\n// ……后面截断了";
    }

    // ==================== 提示词 / 解析 ====================

    static String prompt(Ticket t, String className, String member, List<LogicItem> items, Sources src) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                你是资深测试工程师，要把一次后端代码改动翻译成测试同学看得懂的业务规则。测试不看代码，只看你的结论来设计用例。
                要求：
                1. 只根据下面给的代码说，不要编；看不全或拿不准的写进 unsure。
                2. 用业务语言（「点赞分」「回复分」「信用贡献」），不要贴代码；变量名必须提时用中文解释它是什么，再在括号里带上原名。
                3. oldRule / newRule 各 1～4 条，一条一句，左右能对照着看：旧的怎么算 / 怎么判断，新的怎么算 / 怎么判断。
                   新增方法 oldRule 写「原来没有」；只是拆中间变量、结果不变的，要明确写「结果和原来一样」。
                4. factors 列计算因子 / 判断条件的增减（新增 / 去掉 / 改了），没有就给空数组。
                5. focus 2～4 条测试重点，要能照着做：造什么数据、传什么值（含边界）、断言哪个字段、查哪张表 / 哪个配置、找谁确认。
                6. impact 一句话说影响谁：哪些接口的返回、哪些数据、定时任务 / 消息消费等。
                7. vsTicket：需求里对这块怎么说、代码是否一致；需求没提就空字符串。
                只输出一个 JSON 对象，不要别的文字：
                {"title":"≤16字的业务名，如：评论得分算法重构","oldRule":[],"newRule":[],"factors":[{"name":"","change":"新增|去掉|改了","detail":""}],"impact":"","focus":[],"vsTicket":"","unsure":""}

                """);
        sb.append("需求单：").append(t.getKey()).append(' ').append(nz(t.getSummary())).append('\n');
        if (t.getNotes() != null && !t.getNotes().isBlank()) {
            String n = t.getNotes();
            sb.append("需求描述 / 备注（节选）：\n").append(n.length() > 2000 ? n.substring(0, 2000) + "…" : n).append('\n');
        }
        Set<String> eps = new LinkedHashSet<>();
        items.forEach(i -> eps.addAll(i.getEndpoints()));
        sb.append("\n改动的方法：").append(className).append('.').append(member).append("（项目 ").append(items.get(0).getProject()).append("）\n");
        sb.append(eps.isEmpty() ? "经由的接口：没连到接口（可能是定时任务 / 消息消费 / 公共方法）\n"
                : "经由的接口：" + String.join("、", eps) + "\n");
        sb.append("\n规则引擎认出来的改动：\n");
        for (LogicItem i : items) {
            sb.append("- [").append(i.getKind()).append("] ").append(nz(i.getTitle())).append('\n');
            if (i.getBefore() != null) {
                sb.append("  改前：").append(i.getBefore().replace("\n", "\n        ")).append('\n');
            }
            if (i.getAfter() != null) {
                sb.append("  改后：").append(i.getAfter().replace("\n", "\n        ")).append('\n');
            }
        }
        if (src.before() != null) {
            sb.append("\n改前的完整方法：\n```java\n").append(src.before()).append("\n```\n");
        }
        if (src.after() != null) {
            sb.append("\n改后的完整方法：\n```java\n").append(src.after()).append("\n```\n");
        }
        if (src.before() == null && src.after() == null) {
            sb.append("\n（没拿到完整方法，只有上面的改动行：").append(src.seen()).append("）\n");
        }
        return sb.toString();
    }

    static LogicExplain parse(String answer) {
        String s = answer == null ? "" : answer.trim();
        int from = s.indexOf('{');
        int to = s.lastIndexOf('}');
        if (from < 0 || to <= from) {
            throw new IllegalStateException("AI 没按格式返回，再点一次试试");
        }
        JsonNode n;
        try {
            n = JSON.readTree(s.substring(from, to + 1));
        } catch (Exception e) {
            throw new IllegalStateException("AI 返回的内容解析不了，再点一次试试");
        }
        LogicExplain ex = new LogicExplain();
        ex.setTitle(text(n, "title"));
        ex.setOldRule(list(n, "oldRule"));
        ex.setNewRule(list(n, "newRule"));
        for (JsonNode f : n.path("factors")) {
            String name = text(f, "name");
            if (name != null) {
                Factor x = new Factor();
                x.setName(name);
                x.setChange(text(f, "change"));
                x.setDetail(text(f, "detail"));
                ex.getFactors().add(x);
            }
        }
        ex.setImpact(text(n, "impact"));
        ex.setFocus(list(n, "focus"));
        ex.setVsTicket(text(n, "vsTicket"));
        ex.setUnsure(text(n, "unsure"));
        if (ex.getOldRule().isEmpty() && ex.getNewRule().isEmpty()) {
            throw new IllegalStateException("AI 没给出新旧规则，再点一次试试");
        }
        return ex;
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.path(f);
        String s = v.isTextual() ? v.asText().trim() : null;
        return s == null || s.isEmpty() ? null : s;
    }

    private static List<String> list(JsonNode n, String f) {
        List<String> out = new ArrayList<>();
        JsonNode v = n.path(f);
        if (v.isArray()) {
            v.forEach(x -> {
                if (x.isTextual() && !x.asText().isBlank()) {
                    out.add(x.asText().trim());
                }
            });
        } else if (v.isTextual() && !v.asText().isBlank()) {
            out.add(v.asText().trim());
        }
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
