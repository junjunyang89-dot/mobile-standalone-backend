package com.mobile.standalone.service.apitest;

import java.util.ArrayList;
import java.util.List;

/**
 * 把「接口对比分析」那一堆字段差异，压成清单上的一个标记 + 一句人话。
 * <p>
 * 压缩是有损的，但清单要的就是这个：一屏两百多行，每行只能给一个词的空间。
 * 详细差异在对比分析那边看，清单只回答「这条要不要动」。
 * <p>
 * 优先级 <b>入参 &gt; 返回字段 &gt; 实现</b>，因为影响面就是这个顺序：
 * 新增必填入参会让老客户端直接 400；返回体加字段多数是兼容的；
 * 实现改了签名没变，属于「跑一遍看看」。
 */
public final class EndpointChangeFlag {

    private EndpointChangeFlag() {
    }

    public static final String NEW = "NEW";
    public static final String PARAM_CHANGED = "PARAM_CHANGED";
    public static final String FIELD_CHANGED = "FIELD_CHANGED";
    public static final String IMPL_CHANGED = "IMPL_CHANGED";
    /** 分支上删掉了；上线前全量里那条照旧，上线时才标废弃 */
    public static final String REMOVED = "REMOVED";

    /** 对比分析给出的一条接口变更，只取清单用得上的部分 */
    public record Input(String changeType, String risk, int paramChanges,
                        int responseFieldChanges, int bodyFieldChanges, int changedImpls) {
    }

    /**
     * @return 标记；{@code null} 表示这条没什么可标的（既没改也不是新增）
     */
    public static String of(Input in) {
        if (in == null) {
            return null;
        }
        if ("ADDED".equalsIgnoreCase(in.changeType())) {
            return NEW;
        }
        if (in.paramChanges() > 0) {
            return PARAM_CHANGED;
        }
        if (in.responseFieldChanges() > 0 || in.bodyFieldChanges() > 0) {
            return FIELD_CHANGED;
        }
        if (in.changedImpls() > 0) {
            return IMPL_CHANGED;
        }
        // MODIFIED 但说不出改了什么：仍然标一下，别让它混进「本次没变」里，
        // 那会让人以为不用看。分析器判它变了就是变了，只是原因没归到类
        return "MODIFIED".equalsIgnoreCase(in.changeType()) ? IMPL_CHANGED : null;
    }

    /** 一句话说清改了什么，直接显示在清单那一行下面 */
    public static String note(Input in, String risk) {
        if (in == null) {
            return null;
        }
        if ("ADDED".equalsIgnoreCase(in.changeType())) {
            return "本次新增的接口";
        }
        List<String> parts = new ArrayList<>();
        if (in.paramChanges() > 0) {
            parts.add("入参改了 " + in.paramChanges() + " 处");
        }
        if (in.responseFieldChanges() > 0) {
            parts.add("返回字段改了 " + in.responseFieldChanges() + " 处");
        }
        if (in.bodyFieldChanges() > 0) {
            parts.add("请求体字段改了 " + in.bodyFieldChanges() + " 处");
        }
        if (in.changedImpls() > 0) {
            parts.add("实现类改了 " + in.changedImpls() + " 个");
        }
        if (parts.isEmpty()) {
            parts.add("签名没变，但分析判定有改动");
        }
        String s = String.join("、", parts);
        // BREAKING 单独点出来：这一档是「老客户端会直接坏」，和「多了个字段」不是一回事
        if ("BREAKING".equalsIgnoreCase(risk)) {
            return "破坏性变更 —— " + s + "。老客户端可能直接报错，必须测";
        }
        return s;
    }

    /** 这条变更要不要把接口标成已废弃 */
    public static boolean isRemoval(String changeType) {
        return "REMOVED".equalsIgnoreCase(changeType);
    }
}
