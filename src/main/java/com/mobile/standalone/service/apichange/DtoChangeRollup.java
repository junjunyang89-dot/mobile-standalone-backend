package com.mobile.standalone.service.apichange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一个返回体的几十条字段差异,收成<b>人要做的那几个决定</b>。
 *
 * <h2>这个类要解决的事</h2>
 *
 * <p>现在的呈现规则是「<b>一条字段差异 = 一行</b>」。于是一个 DTO 整体从基本类型
 * 换成包装类型,报告里就是二三十行长得一模一样的
 * {@code 类型变更 xxx int → Integer}。实测一次提测 79 条,
 * 人要滚很久、读很久,读完得到的信息其实只有两句话。
 *
 * <p>正确的规则是「<b>一个要人做的决定 = 一行</b>」:
 * <pre>
 *   DeadlockMatchPlayerDetailDTO
 *     ① tooltips 从数组变成对象，客户端解析会崩  → 去问开发是不是有意的
 *     ② 23 个字段改成包装类型，现在可能返回 null → 确认调用方对 null 的处理
 * </pre>
 * 23 个字段是<b>一次机械重构</b>,它是一个事实,不是 23 条待确认项。
 *
 * <h2>什么时候才合并</h2>
 *
 * <p>只合并<b>同一种 wireEffect</b> 的。把 SHAPE 和 NULLABILITY 合在一起数,
 * 等于把那条真问题藏进一个数字里——而那条恰恰是唯一要人动手的。
 *
 * <p>SHAPE / UNKNOWN <b>永远逐条列</b>,不管有几条:它们每一条都要人单独去确认,
 * 合并成「3 个字段结构变了」人还得展开看是哪三个,省不了事还多一次点击。
 */
public final class DtoChangeRollup {

    private DtoChangeRollup() {
    }

    /**
     * 一组同类变更的归并结果。
     *
     * @param effect   ADDED / REMOVED / SHAPE / NULLABILITY / WIDENING / SAME / UNKNOWN
     * @param count    这一组有几个字段
     * @param fields   字段名,按出现顺序;用于「展开看全部」
     * @param headline 一句话说清这一组是什么
     * @param action   人该做什么;没有要做的就是空串
     * @param breaking 这一组要不要进「可能不兼容」
     * @param collapse 界面上默认折不折叠
     */
    public record Group(String effect, int count, List<String> fields,
                        String headline, String action, boolean breaking, boolean collapse) {
    }

    /**
     * 归并。
     *
     * @param dtoType 返回体类名,只用于文案
     * @param fields  这个返回体的全部字段差异
     * @return 按「先要人动手的、后知道就好的」排序;空输入返回空列表
     */
    public static List<Group> of(String dtoType, List<ApiChangeAnalyzer.FieldChange> fields) {
        Map<String, List<ApiChangeAnalyzer.FieldChange>> byEffect = new LinkedHashMap<>();
        if (fields != null) {
            for (ApiChangeAnalyzer.FieldChange f : fields) {
                if (f == null) {
                    continue;
                }
                byEffect.computeIfAbsent(effectKey(f), k -> new ArrayList<>()).add(f);
            }
        }
        List<Group> out = new ArrayList<>();
        // 固定顺序：报告两次跑出来顺序不一样的话，人没法 diff
        for (String key : new String[]{"REMOVED", "SHAPE", "UNKNOWN",
                                       "NULLABILITY", "WIDENING", "ADDED", "SAME"}) {
            List<ApiChangeAnalyzer.FieldChange> g = byEffect.get(key);
            if (g == null || g.isEmpty()) {
                continue;
            }
            List<String> names = g.stream().map(ApiChangeAnalyzer.FieldChange::getName).toList();
            out.add(new Group(key, g.size(), names,
                    headline(key, g.size(), g, dtoType), action(key),
                    breaking(key), collapse(key, g.size())));
        }
        return out;
    }

    /** 没有 wireEffect 的（ADDED / REMOVED）用 changeType；类型变更用 wireEffect */
    private static String effectKey(ApiChangeAnalyzer.FieldChange f) {
        if (!"TYPE_CHANGED".equals(f.getChangeType())) {
            return f.getChangeType() == null ? "SAME" : f.getChangeType();
        }
        String w = f.getWireEffect();
        // 空值不能当成「无影响」：那是「没算过」，按判不出处理
        return w == null || w.isBlank() ? "UNKNOWN" : w;
    }

    private static String headline(String key, int n, List<ApiChangeAnalyzer.FieldChange> g,
                                   String dtoType) {
        String one = n == 1 ? g.get(0).getName() : null;
        return switch (key) {
            case "REMOVED" -> one != null
                    ? "删了字段 " + one
                    : "删了 " + n + " 个字段";
            case "SHAPE" -> one != null
                    ? one + " 的结构变了（" + g.get(0).getOldType() + " → " + g.get(0).getNewType() + "）"
                    : n + " 个字段的结构变了";
            case "UNKNOWN" -> one != null
                    ? one + " 的类型变了，判不出序列化后会不会变"
                    : n + " 个字段的类型变了，判不出序列化后会不会变";
            case "NULLABILITY" -> n + " 个字段改成了包装类型，现在可能返回 null"
                    + (n >= 5 ? "（看着像一次整体重构）" : "");
            case "WIDENING" -> n + " 个字段的数值范围变大";
            case "ADDED" -> "新增 " + n + " 个字段";
            default -> n + " 个字段有变化，序列化结果不变";
        };
    }

    private static String action(String key) {
        return switch (key) {
            case "REMOVED" -> "去问开发是不是有意删的——用到它的调用方会拿到 undefined";
            case "SHAPE" -> "去问开发是不是有意的——客户端按老结构解析会失败";
            case "UNKNOWN" -> "去确认序列化出来是什么样";
            case "NULLABILITY" -> "确认调用方对 null 的处理（JSON 值本身没变）";
            case "WIDENING" -> "";
            case "ADDED" -> "";
            default -> "";
        };
    }

    private static boolean breaking(String key) {
        return "REMOVED".equals(key) || "SHAPE".equals(key) || "UNKNOWN".equals(key);
    }

    /**
     * 折不折叠。
     *
     * <p>要人动手的一条都不折;其余默认折起来。
     * 单条的 NULLABILITY 也不折——只有一个字段时,展开和折叠一样长,
     * 折起来反而多一次点击。
     */
    private static boolean collapse(String key, int n) {
        if (breaking(key)) {
            return false;
        }
        return n > 1;
    }
}
