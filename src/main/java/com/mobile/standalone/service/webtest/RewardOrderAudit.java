package com.mobile.standalone.service.webtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.service.capture.CaptureStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 活动页奖励顺序自动校验。
 * <p>
 * 从一场抓包里读奖励接口的响应，自动抽出每个任务的奖品，按需求文档的优先级校验顺序。
 * <b>人一个字都不用抄。</b>
 * <p>
 * 关键取舍：读的是<b>接口 JSON</b>，不是页面 DOM。活动页每期换皮，DOM 的 class、结构全变，
 * 拿选择器抓奖品名每期都得改；而奖品名、任务结构在接口响应里，跟页面长什么样无关——
 * 换皮打不穿。这也是为什么用抓包而不是让 Playwright 去点页面。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RewardOrderAudit {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 奖励接口路径里的特征。这几个活动页都是 userTask 那套 */
    private static final String[] REWARD_API_HINTS = {"listByCategory", "userTask", "taskList"};
    /** 任务列表可能的字段名 */
    private static final String[] TASK_LIST_KEYS = {"taskList", "list", "tasks", "data"};
    /** 一个任务里奖品数组的字段名 */
    private static final String[] AWARDS_KEYS = {"awards", "rewards", "awardList", "prizeList"};
    /** 奖品名字段 */
    private static final String[] NAME_KEYS = {"name", "awardName", "rewardName", "title"};
    /** 任务名字段 */
    private static final String[] TASK_NAME_KEYS = {"taskName", "name", "title"};

    private final CaptureStore captureStore;

    public record TaskAudit(String taskName, List<String> rewards,
                            boolean ok, List<String> violations, List<String> unmatched) {
    }

    public record Result(boolean ok, int taskCount, int problemCount,
                         List<TaskAudit> tasks, String hint, String note) {
    }

    /**
     * 从抓包会话里找奖励接口、抽任务奖品、校验顺序。
     *
     * @param sessionId 抓包会话
     * @param ladder    优先级阶梯；null 用需求文档那条默认的
     * @param aliases   页面名 → 类目 的人工映射，兜住自动认不出的
     */
    public Result auditFromCapture(String sessionId, RewardPriorityChecker.Ladder ladder,
                                   Map<String, String> aliases) {
        JsonNode rewardBody = findRewardResponse(sessionId);
        if (rewardBody == null) {
            return new Result(false, 0, 0, List.of(), "",
                    "这场抓包里没找到奖励接口的响应。确认抓包时打开了活动页、且登录态正常"
                            + "（页面停在「去登录」时不会请求奖励接口）");
        }
        List<Map<String, Object>> tasks = extractTasks(rewardBody);
        if (tasks.isEmpty()) {
            return new Result(false, 0, 0, List.of(), "",
                    "找到了奖励接口，但没能从响应里抽出任务列表。可能字段结构和预期不同，"
                            + "把响应发我看一下就能对上");
        }

        List<TaskAudit> audits = new ArrayList<>();
        int problems = 0;
        for (Map<String, Object> t : tasks) {
            @SuppressWarnings("unchecked")
            List<String> rewards = (List<String>) t.get("rewards");
            RewardPriorityChecker.Result r = RewardPriorityChecker.check(rewards, ladder, aliases);
            List<String> vio = r.violations().stream().map(RewardPriorityChecker.Violation::message).toList();
            if (!r.ok()) {
                problems++;
            }
            audits.add(new TaskAudit(String.valueOf(t.get("taskName")), rewards, r.ok(), vio, r.unmatched()));
        }

        boolean ok = problems == 0;
        String hint = ok
                ? "共 " + tasks.size() + " 个任务，奖励顺序都符合优先级"
                : problems + " / " + tasks.size() + " 个任务的奖励顺序不对，逐个看下面";
        return new Result(ok, tasks.size(), problems, audits, hint, null);
    }

    /** 在抓包流水里找奖励接口的响应体 */
    private JsonNode findRewardResponse(String sessionId) {
        Path flows = captureStore.dirOf(sessionId).resolve("flows.jsonl");
        Path details = Path.of(flows.toAbsolutePath() + ".details");
        if (!Files.isRegularFile(flows)) {
            return null;
        }
        try {
            // 后出现的覆盖先出现的：轮询时以最后一次为准
            JsonNode best = null;
            for (String line : Files.readAllLines(flows, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                JsonNode row = JSON.readTree(line);
                String path = row.path("path").asText("");
                if (!looksLikeRewardApi(path) || row.path("status").asInt(0) != 200) {
                    continue;
                }
                JsonNode body = readBody(details, row.path("seq").asInt(-1));
                if (body != null && !extractTasks(body).isEmpty()) {
                    best = body;
                }
            }
            return best;
        } catch (Exception e) {
            log.warn("[reward-audit] 读抓包 {} 失败: {}", sessionId, e.getMessage());
            return null;
        }
    }

    private static boolean looksLikeRewardApi(String path) {
        String p = path.toLowerCase();
        for (String h : REWARD_API_HINTS) {
            if (p.contains(h.toLowerCase())) return true;
        }
        return false;
    }

    private JsonNode readBody(Path detailsDir, int seq) {
        if (seq < 0) return null;
        Path f = detailsDir.resolve(seq + ".json");
        if (!Files.isRegularFile(f)) return null;
        try {
            JsonNode detail = JSON.readTree(Files.readString(f, StandardCharsets.UTF_8));
            JsonNode body = detail.get("respBody");
            if (body == null || body.isNull()) return null;
            return JSON.readTree(body.asText());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从响应体里抽「任务 → 奖品名列表」。
     * <p>
     * 不写死路径：后端统一 {@code {code,message,result}} 包一层，而 result 下的任务数组
     * 字段名各活动可能不同。所以顺着候选字段名找，找到第一个「数组、且元素里有奖品数组」的
     * 就是任务列表。这样换一个活动、字段名换了也能兜住，不用逐个配。
     */
    List<Map<String, Object>> extractTasks(JsonNode root) {
        JsonNode taskArray = findTaskArray(root, 4);
        List<Map<String, Object>> out = new ArrayList<>();
        if (taskArray == null || !taskArray.isArray()) {
            return out;
        }
        for (JsonNode task : taskArray) {
            JsonNode awards = firstArrayField(task, AWARDS_KEYS);
            if (awards == null || !awards.isArray()) {
                continue;
            }
            List<String> names = new ArrayList<>();
            for (JsonNode a : awards) {
                String name = firstTextField(a, NAME_KEYS);
                if (name != null && !name.isBlank()) {
                    names.add(name.trim());
                }
            }
            if (names.isEmpty()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            String tn = firstTextField(task, TASK_NAME_KEYS);
            m.put("taskName", tn == null ? "任务" + (out.size() + 1) : tn);
            m.put("rewards", names);
            out.add(m);
        }
        return out;
    }

    /** 递归找「元素里带奖品数组」的那个数组——那就是任务列表 */
    private JsonNode findTaskArray(JsonNode node, int depth) {
        if (node == null || depth <= 0) {
            return null;
        }
        if (node.isArray()) {
            if (node.size() > 0 && firstArrayField(node.get(0), AWARDS_KEYS) != null) {
                return node;
            }
            return null;
        }
        if (node.isObject()) {
            // 先按常见任务字段名找，找不到再全字段递归
            for (String k : TASK_LIST_KEYS) {
                if (node.has(k)) {
                    JsonNode hit = findTaskArray(node.get(k), depth - 1);
                    if (hit != null) return hit;
                }
            }
            var it = node.fields();
            while (it.hasNext()) {
                JsonNode hit = findTaskArray(it.next().getValue(), depth - 1);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static JsonNode firstArrayField(JsonNode node, String[] keys) {
        if (node == null || !node.isObject()) return null;
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && v.isArray()) return v;
        }
        return null;
    }

    private static String firstTextField(JsonNode node, String[] keys) {
        if (node == null || !node.isObject()) return null;
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && v.isValueNode() && !v.asText().isBlank()) {
                return v.asText();
            }
        }
        return null;
    }
}
