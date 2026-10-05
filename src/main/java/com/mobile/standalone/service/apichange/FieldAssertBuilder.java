package com.mobile.standalone.service.apichange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mobile.standalone.service.apitest.JsonPaths;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把对比报告里的一个返回字段变化变成一条 jsonPath 断言。
 * <p>
 * 报告里的字段名是相对 {@code ResponseResult<T>} 里那个 T 的（{@code winrate}、{@code heroLine[].heroId}），
 * 断言要的是从响应根开始的路径（{@code $.result[0].winrate}）。中间那一截——壳的键名、payload 是不是数组——
 * 源码里看不出来（壳在 jar 里），只能看真实响应：先看这条用例最近一次的响应，再看同项目别的接口的，都没有才按 {@code $.result} 猜。
 */
public final class FieldAssertBuilder {

    private FieldAssertBuilder() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 这几个项目的 ResponseResult 把数据挂在 result 上；别的壳常见键名也认 */
    static final List<String> DATA_KEYS = List.of("result", "data", "body", "obj", "content");
    static final String DEFAULT_PREFIX = "$.result";
    private static final int LOCATE_DEPTH = 5;
    private static final Pattern DATA_KEY_TEXT = Pattern.compile("\"(result|data|body|obj|content)\"\\s*:\\s*([\\[{])");

    private static final Set<String> NUM = Set.of("int", "Integer", "long", "Long", "double", "Double", "float",
            "Float", "short", "Short", "byte", "Byte", "BigDecimal", "BigInteger", "Number");
    private static final Set<String> STR = Set.of("String", "char", "Character", "CharSequence");
    private static final Set<String> BOOL = Set.of("boolean", "Boolean");
    private static final Set<String> ARR = Set.of("List", "ArrayList", "LinkedList", "Set", "HashSet",
            "LinkedHashSet", "TreeSet", "Collection", "Iterable", "JSONArray");
    private static final Set<String> MAP = Set.of("Map", "HashMap", "LinkedHashMap", "TreeMap", "JSONObject");
    /** 序列化格式由项目配置决定（时间戳还是字符串），断言类型容易误报，只断言存在 */
    private static final Set<String> DATE = Set.of("Date", "LocalDate", "LocalDateTime", "LocalTime",
            "Instant", "ZonedDateTime", "OffsetDateTime", "Timestamp");

    /** @param treePath 报告完整字段树里的路径，如 {@code heroLine[].heroId}；没有就用 name */
    public record Field(String name, String treePath, String changeType, String oldType, String newType) {
    }

    /** @param from 给人看的：前缀是怎么定出来的 */
    public record Prefix(String path, String from, boolean guessed) {
    }

    /**
     * @param check     type 断言类型 / exists 断言存在 / absent 断言不再返回
     * @param jsonType  check=type 时的 JSON 类型
     * @param duplicate 用例里已经有同一条，写入时会跳过
     */
    public record Proposal(String field, String path, String check, String jsonType, String note, boolean duplicate) {
    }

    /** Java 类型 → 断言用的 JSON 类型；拿不准返回 null（只断言存在） */
    public static String jsonType(String javaType) {
        if (javaType == null || javaType.isBlank()) {
            return null;
        }
        String t = javaType.replaceAll("\\s+", "");
        if (t.endsWith("[]")) {
            return "array";
        }
        int lt = t.indexOf('<');
        if (lt >= 0) {
            t = t.substring(0, lt);
        }
        int dot = t.lastIndexOf('.');
        if (dot >= 0) {
            t = t.substring(dot + 1);
        }
        if (NUM.contains(t)) return "number";
        if (STR.contains(t)) return "string";
        if (BOOL.contains(t)) return "boolean";
        if (ARR.contains(t)) return "array";
        if (MAP.contains(t)) return "object";
        if (DATE.contains(t) || "Object".equals(t) || t.length() <= 1) {
            return null;
        }
        return Character.isUpperCase(t.charAt(0)) ? "object" : null;
    }

    /**
     * @param caseBody    这条用例最近一次的响应（可能被截断成前 4000 字）
     * @param projectBody 同项目别的接口最近一次的响应，只用来认壳的键名
     */
    public static Prefix prefix(String caseBody, String projectBody) {
        String own = prefixOf(caseBody, true);
        if (own != null) {
            return new Prefix(own, "按这条用例最近一次的响应定位", false);
        }
        String other = prefixOf(projectBody, false);
        if (other != null) {
            return new Prefix(other, "这条用例还没跑过，按同项目其它接口的响应猜的", true);
        }
        return new Prefix(DEFAULT_PREFIX, "没有可参考的响应，按 ResponseResult 的 result 猜的", true);
    }

    /** @param withArray false 时不判断 payload 是不是数组——那是这个接口自己的事，别的接口说了不算 */
    static String prefixOf(String body, boolean withArray) {
        if (body == null || body.isBlank()) {
            return null;
        }
        JsonNode root = parse(body);
        if (root != null) {
            if (root.isArray()) {
                return withArray ? "$[0]" : null;
            }
            if (!root.isObject()) {
                return null;
            }
            for (String k : DATA_KEYS) {
                if (root.has(k)) {
                    return "$." + k + (withArray && root.get(k).isArray() ? "[0]" : "");
                }
            }
            return "$";
        }
        // 预览被截断成半截 JSON 解析不了，壳的键名和是不是数组在开头就能看出来
        Matcher m = DATA_KEY_TEXT.matcher(body);
        if (m.find()) {
            return "$." + m.group(1) + (withArray && "[".equals(m.group(2)) ? "[0]" : "");
        }
        return null;
    }

    public static List<Proposal> propose(List<Field> fields, String caseBody, String projectBody,
                                         String existingAssertions) {
        Prefix prefix = prefix(caseBody, projectBody);
        JsonNode sample = parse(caseBody);
        Set<String> existing = signatures(existingAssertions);
        List<Proposal> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Field f : fields == null ? List.<Field>of() : fields) {
            if (f == null || f.name() == null || f.name().isBlank()) {
                continue;
            }
            Proposal p = proposeOne(f, prefix, sample);
            String sig = signature(p.path(), p.check(), p.jsonType());
            if (!seen.add(sig)) {
                continue;
            }
            out.add(existing.contains(sig)
                    ? new Proposal(p.field(), p.path(), p.check(), p.jsonType(), "用例里已经有这条断言", true)
                    : p);
        }
        return out;
    }

    static Proposal proposeOne(Field f, Prefix prefix, JsonNode sample) {
        boolean removed = "REMOVED".equals(f.changeType());
        String type = removed ? null : jsonType(f.newType());
        String check = removed ? "absent" : (type == null ? "exists" : "type");
        String rel = relPath(f);
        String path = join(prefix.path(), rel);
        String note;
        if (removed) {
            note = "删掉的字段：断言它不再返回";
        } else if (sample != null && present(sample, path)) {
            note = "最近一次响应里有这个字段";
        } else if (sample != null && locate(sample, prefix.path(), f.name()) != null) {
            path = locate(sample, prefix.path(), f.name());
            note = "按最近一次响应里的位置";
        } else if (sample != null) {
            note = "最近一次响应里还没有这个字段（可能新代码还没部署），按推断的位置写";
        } else {
            note = prefix.from();
        }
        return new Proposal(f.name(), path, check, type, note, false);
    }

    /** {@code heroLine[].heroId} → {@code heroLine[0].heroId}；树里没有就用字段名 */
    static String relPath(Field f) {
        String p = f.treePath() == null || f.treePath().isBlank() ? f.name() : f.treePath();
        return p.trim().replace("[]", "[0]");
    }

    static String join(String prefix, String rel) {
        if (rel.startsWith("[")) {
            return prefix + rel;
        }
        return "$".equals(prefix) ? "$." + rel : prefix + "." + rel;
    }

    private static boolean present(JsonNode root, String path) {
        try {
            JsonNode n = JsonPaths.read(root.toString(), path);
            return n != null && !n.isMissingNode();
        } catch (Exception e) {
            return false;
        }
    }

    /** 在 prefix 那个节点下面找第一个带这个键的对象；数组只看第一项 */
    static String locate(JsonNode root, String prefix, String name) {
        JsonNode base;
        try {
            base = JsonPaths.read(root.toString(), prefix);
        } catch (Exception e) {
            return null;
        }
        if (base == null || base.isMissingNode()) {
            return null;
        }
        return walk(base, prefix, name, 0);
    }

    private static String walk(JsonNode n, String path, String name, int depth) {
        if (n == null || depth > LOCATE_DEPTH) {
            return null;
        }
        if (n.isArray()) {
            return n.isEmpty() ? null : walk(n.get(0), path + "[0]", name, depth + 1);
        }
        if (!n.isObject()) {
            return null;
        }
        if (n.has(name)) {
            return join(path, name);
        }
        Iterator<Map.Entry<String, JsonNode>> it = n.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String hit = walk(e.getValue(), join(path, e.getKey()), name, depth + 1);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** 转成用例里存的断言格式 */
    public static ObjectNode toAssertion(String path, String check, String jsonType) {
        ObjectNode a = JSON.createObjectNode();
        a.put("type", "jsonPath");
        a.put("path", path);
        if ("type".equals(check) && jsonType != null && !jsonType.isBlank()) {
            a.put("op", "type");
            a.put("value", jsonType);
        } else {
            a.put("exists", !"absent".equals(check));
        }
        return a;
    }

    public record Merge(String json, int added, int skipped) {
    }

    /** 追加到用例已有断言后面，同一条（路径 + 检查方式都一样）不重复加 */
    public static Merge append(String existingAssertions, List<ObjectNode> adds) {
        ArrayNode arr = JSON.createArrayNode();
        JsonNode cur = parse(existingAssertions);
        if (cur != null && cur.isArray()) {
            arr.addAll((ArrayNode) cur);
        }
        Set<String> have = signatures(arr.toString());
        int added = 0;
        int skipped = 0;
        for (ObjectNode a : adds) {
            String sig = signatureOf(a);
            if (sig == null || !have.add(sig)) {
                skipped++;
                continue;
            }
            arr.add(a);
            added++;
        }
        return new Merge(arr.toString(), added, skipped);
    }

    private static Set<String> signatures(String assertionsJson) {
        Set<String> out = new HashSet<>();
        JsonNode arr = parse(assertionsJson);
        if (arr != null && arr.isArray()) {
            for (JsonNode a : arr) {
                String s = signatureOf(a);
                if (s != null) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static String signatureOf(JsonNode a) {
        if (a == null || !"jsonPath".equals(a.path("type").asText()) || a.path("path").asText("").isBlank()) {
            return null;
        }
        String path = a.path("path").asText();
        if ("type".equals(a.path("op").asText())) {
            return signature(path, "type", a.path("value").asText());
        }
        if (a.has("exists")) {
            return signature(path, a.path("exists").asBoolean(true) ? "exists" : "absent", null);
        }
        return null;
    }

    private static String signature(String path, String check, String jsonType) {
        return path + "|" + check + "|" + ("type".equals(check) ? String.valueOf(jsonType).toLowerCase() : "");
    }

    private static JsonNode parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }
}
