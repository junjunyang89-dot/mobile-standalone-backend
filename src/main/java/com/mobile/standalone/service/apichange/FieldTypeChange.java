package com.mobile.standalone.service.apichange;

import java.util.Locale;
import java.util.Set;

/**
 * 一个字段的类型变了,<b>在 JSON 上到底变了什么</b>。
 *
 * <h2>为什么需要这个判断</h2>
 *
 * <p>原来的规则是「{@code TYPE_CHANGED} 一律算不兼容」。于是这两条进了同一档:
 * <table border="1">
 *   <caption>同样是「类型变更」,后果差了十万八千里</caption>
 *   <tr><th>变更</th><th>JSON 上的实际后果</th></tr>
 *   <tr><td>{@code tooltips JSONArray → JSONObject}</td>
 *       <td>{@code [...]} 变成 {@code {...}},<b>客户端解析必崩</b></td></tr>
 *   <tr><td>{@code kills int → Integer}</td>
 *       <td>{@code "kills": 5} 还是 {@code "kills": 5},<b>一模一样</b></td></tr>
 * </table>
 *
 * <p>一个 DTO 整体从基本类型换成包装类型,会一次产出二三十条「可能不兼容」。
 * 实测一次提测报出 79 条,里面真正要去问开发的只有一条。
 * <b>而噪音的代价不是多看几行,是整份报告失去可信度</b>——
 * 这正是 {@code ApiRiskScanner} 注释里那句话:"人核过两三条假的就不看了,
 * 真的那条也一起废掉"。
 *
 * <h2>判不出来的一律按会出问题处理</h2>
 *
 * <p>这个判断的<b>错误代价是不对称的</b>:
 * <ul>
 *   <li>误报(把兼容的说成不兼容)→ 多看几行,就是现在这个问题</li>
 *   <li><b>漏报</b>(把真的破坏性变更降级)→ 它从报告里消失,没人去问开发,
 *       上线后客户端解析失败</li>
 *   </ul>
 * 这次改动的目的是降噪,所以它<b>最危险的失败方式就是降过头</b>。
 * 因此 {@link #UNKNOWN} 和 {@link #SHAPE} 同等对待:
 * {@code Object}、{@code JsonNode}、日期类型互换这些看不透的,一律留在不兼容档。
 * 宁可留几条噪音,不能让一条真的消失。
 */
public enum FieldTypeChange {

    /** JSON 形状变了(数组↔对象、数字↔字符串…),客户端解析一定出问题 */
    SHAPE,

    /**
     * 只是可空性变了({@code int → Integer})。
     * JSON 数值一模一样,但<b>这个字段现在可能是 {@code null}</b>。
     */
    NULLABILITY,

    /** 数值范围/精度变大({@code int → long}),多数兼容 */
    WIDENING,

    /** JSON 上看不出区别({@code List → ArrayList}) */
    SAME,

    /** 判不出来。<b>按会出问题处理。</b> */
    UNKNOWN;

    /** 要不要留在「可能不兼容」里 */
    public boolean breaking() {
        return this == SHAPE || this == UNKNOWN;
    }

    public String label() {
        return switch (this) {
            case SHAPE -> "结构变了";
            case NULLABILITY -> "可能为 null";
            case WIDENING -> "范围变大";
            case SAME -> "无影响";
            case UNKNOWN -> "判不出";
        };
    }

    /** 给人看的一句话,直接进报告 */
    public String describe() {
        return switch (this) {
            case SHAPE -> "JSON 结构变了,客户端按老结构解析会失败";
            case NULLABILITY -> "JSON 值不变,但这个字段现在可能返回 null——"
                    + "确认调用方对 null 的处理";
            case WIDENING -> "数值范围变大,一般兼容;只在调用方用更窄的类型接时才有问题";
            case SAME -> "序列化出来一样,无影响";
            case UNKNOWN -> "判不出序列化后会不会变——按会出问题处理,先去确认";
        };
    }

    // ---------- 类型归类 ----------

    private static final Set<String> PRIMITIVE_NUM =
            Set.of("byte", "short", "int", "long", "float", "double");
    private static final Set<String> WRAPPER_NUM =
            Set.of("Byte", "Short", "Integer", "Long", "Float", "Double",
                    "BigDecimal", "BigInteger", "Number");
    private static final Set<String> ARRAY_LIKE =
            Set.of("List", "ArrayList", "LinkedList", "Set", "HashSet", "TreeSet",
                    "LinkedHashSet", "Collection", "Iterable", "JSONArray", "ArrayNode");
    private static final Set<String> OBJECT_LIKE =
            Set.of("Map", "HashMap", "LinkedHashMap", "TreeMap", "JSONObject", "ObjectNode");
    private static final Set<String> STRING_LIKE =
            Set.of("String", "CharSequence", "StringBuilder", "char", "Character");
    private static final Set<String> BOOL_LIKE = Set.of("boolean", "Boolean");
    /**
     * 看不透的:实际序列化成什么取决于运行时的值或 Jackson 配置。
     * 日期类型也在这里——{@code Date → LocalDateTime} 到底变成时间戳还是 ISO 字符串,
     * 取决于全局配置,静态判不了。
     */
    private static final Set<String> OPAQUE =
            Set.of("Object", "JsonNode", "Date", "LocalDate", "LocalDateTime",
                    "LocalTime", "Instant", "Timestamp", "ZonedDateTime", "OffsetDateTime",
                    "Calendar", "Serializable");

    /**
     * 判定。
     *
     * @param oldType 变更前的 Java 类型,原样(可带包名、泛型、数组)
     * @param newType 变更后的类型
     * @return 判定结果;任何一边为空都返回 {@link #UNKNOWN}
     */
    public static FieldTypeChange classify(String oldType, String newType) {
        String a = normalize(oldType);
        String b = normalize(newType);
        if (a == null || b == null) {
            return UNKNOWN;
        }
        if (a.equals(b)) {
            return SAME;
        }

        String ra = raw(a);
        String rb = raw(b);
        String ga = generic(a);
        String gb = generic(b);

        // 两边都是集合:外层一样就看里面。List<Integer> → List<Long> 是范围变大，
        // List<Integer> → List<String> 是结构变了
        boolean aArr = isArray(a);
        boolean bArr = isArray(b);
        if (aArr && bArr) {
            if (ga == null || gb == null) {
                return erasureSame(ra, rb) ? SAME : UNKNOWN;
            }
            return classify(ga, gb);
        }
        // 一边是数组一边不是：[...] 变成 {...} 或者标量，必崩
        if (aArr != bArr) {
            return SHAPE;
        }

        boolean aObj = OBJECT_LIKE.contains(ra);
        boolean bObj = OBJECT_LIKE.contains(rb);
        if (aObj != bObj) {
            return SHAPE;
        }
        if (aObj) {
            // 都是 Map/JSONObject：键值类型变化这里不展开，交给 UNKNOWN 保守处理
            return erasureSame(ra, rb) ? SAME : UNKNOWN;
        }

        // 看不透的一律 UNKNOWN，绝不降级
        if (OPAQUE.contains(ra) || OPAQUE.contains(rb)) {
            return UNKNOWN;
        }

        boolean aNum = isNumeric(ra);
        boolean bNum = isNumeric(rb);
        boolean aStr = STRING_LIKE.contains(ra);
        boolean bStr = STRING_LIKE.contains(rb);
        boolean aBool = BOOL_LIKE.contains(ra);
        boolean bBool = BOOL_LIKE.contains(rb);

        // 数字 ↔ 字符串：JSON 里 5 和 "5" 不是一回事。
        // long → String 是修 JS 精度丢失的常见做法，而它一定是破坏性的
        if (aNum && bStr || aStr && bNum) {
            return SHAPE;
        }
        if (aBool != bBool) {
            return SHAPE;
        }

        if (aNum && bNum) {
            int wa = width(ra);
            int wb = width(rb);
            if (wa != wb) {
                return wb > wa ? WIDENING : SHAPE;   // 变窄会溢出，当成结构问题处理
            }
            // 宽度一样，差别只在装箱与否
            return boxed(ra) != boxed(rb) ? nullabilityOf(ra, rb) : SAME;
        }
        if (aBool && bBool) {
            return boxed(ra) != boxed(rb) ? nullabilityOf(ra, rb) : SAME;
        }
        if (aStr && bStr) {
            return SAME;
        }
        if (erasureSame(ra, rb)) {
            return SAME;
        }
        // 两个不认识的自定义类型互换：结构大概率变了，但判不准 → UNKNOWN（＝仍算不兼容）
        return UNKNOWN;
    }

    /**
     * 装箱方向。基本类型 → 包装类型才是「可能为 null」;
     * 反过来(包装 → 基本)是<b>收紧</b>,老的 null 现在会变成 0 或者直接抛,
     * 那是真的会出问题,不能算成无害。
     */
    private static FieldTypeChange nullabilityOf(String from, String to) {
        boolean wasBoxed = boxed(from);
        boolean nowBoxed = boxed(to);
        if (!wasBoxed && nowBoxed) {
            return NULLABILITY;
        }
        // 包装 → 基本：原来可能是 null，现在不可能了。
        // 对调用方来说字段值的集合变了（null 不见了），当成结构问题去确认
        return SHAPE;
    }

    private static boolean boxed(String t) {
        return !PRIMITIVE_NUM.contains(t) && !"boolean".equals(t) && !"char".equals(t);
    }

    private static boolean isNumeric(String t) {
        return PRIMITIVE_NUM.contains(t) || WRAPPER_NUM.contains(t);
    }

    /** 数值宽度，用来分辨变宽还是变窄；BigDecimal/BigInteger 当成最宽 */
    private static int width(String t) {
        return switch (t) {
            case "byte", "Byte" -> 1;
            case "short", "Short" -> 2;
            case "int", "Integer" -> 4;
            case "long", "Long" -> 8;
            case "float", "Float" -> 10;
            case "double", "Double" -> 11;
            default -> 99;   // BigDecimal / BigInteger / Number
        };
    }

    private static boolean isArray(String t) {
        return t.endsWith("[]") || ARRAY_LIKE.contains(raw(t));
    }

    private static boolean erasureSame(String a, String b) {
        return a.equals(b);
    }

    /** 去掉包名和泛型，留下最外层的简单名 */
    static String raw(String t) {
        if (t == null) {
            return null;
        }
        String s = t;
        int lt = s.indexOf('<');
        if (lt >= 0) {
            s = s.substring(0, lt);
        }
        s = s.replace("[]", "");
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    /** 取第一个泛型参数，没有返回 null */
    static String generic(String t) {
        if (t == null) {
            return null;
        }
        int lt = t.indexOf('<');
        int gt = t.lastIndexOf('>');
        if (lt < 0 || gt <= lt) {
            // 数组写法：Foo[] 的元素类型是 Foo
            return t.endsWith("[]") ? t.substring(0, t.length() - 2).trim() : null;
        }
        String inner = t.substring(lt + 1, gt).trim();
        // Map<String,Foo> 这种取不出单一元素类型，交给上层按 OBJECT_LIKE 处理
        int depth = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                return null;
            }
        }
        return inner.isEmpty() ? null : inner;
    }

    private static String normalize(String t) {
        if (t == null || t.isBlank()) {
            return null;
        }
        return t.trim().replaceAll("\\s+", "");
    }
}
