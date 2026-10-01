package com.mobile.standalone.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 失败归因的输入输出结构。
 * <p>
 * 归因要回答的是一个很具体的问题：这次失败该找谁修——是元素定位失效（改元素），
 * 是页面改版流程过期（改流程），是真 bug（提单），还是设备/网络（重跑）。
 * 这几条路的成本差很多，靠人一条条翻日志翻截图判断，量一上来就跟不上了。
 */
public final class FailureDiagnosisDtos {

    private FailureDiagnosisDtos() {
    }

    /**
     * 归因结论分类。每一类对应一个明确的处理动作，不是为了好看而分的——
     * 分类要是不能推出「接下来干什么」，归因就白做了。
     */
    public enum Category {
        /** 元素定位失效：控件还在，定位串对不上了 */
        ELEMENT_BROKEN("元素定位失效", "修元素行"),
        /** 流程过期：页面改版，步骤顺序/前置条件已经不成立 */
        FLOW_OUTDATED("流程过期", "改流程编排"),
        /** 功能缺陷：应用本身的问题 */
        FUNCTION_BROKEN("功能缺陷", "提单"),
        /** 环境问题：设备掉线、网络、Appium/WDA 没起来 */
        ENVIRONMENT("环境问题", "重跑"),
        /** 偶现：同一步重试过或历史上时好时坏 */
        FLAKY("偶现", "先观察"),
        /** 证据不足以下结论。宁可给这个，也不要编一个像模像样的错结论 */
        UNKNOWN("证据不足", "人工排查");

        private final String label;
        private final String action;

        Category(String label, String action) {
            this.label = label;
            this.action = action;
        }

        /** 中文名，前端下拉直接用 */
        public String label() {
            return label;
        }

        /** 对应的处理动作 */
        public String action() {
            return action;
        }
    }

    /** 结论来源 */
    public enum Source {
        AI,
        HUMAN
    }

    /**
     * 归因上下文：一次失败的全部现场证据。
     * <p>
     * 这份结构同时给三种消费方用——模型归因、前端排查面板、人工复核，
     * 所以它只负责「把证据摆齐并说清缺了什么」，不做任何判断。
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Context {
        private String taskId;
        private String taskName;
        private String taskStatus;
        private String taskType;
        private String platform;
        private String deviceId;
        /** 执行环境（测试环境 / 生产环境）：测试服数据与生产不同，判读时是重要背景 */
        private String environment;
        /** 流程绑定的应用字典 ID，元素就是按它 + platform 查的 */
        private String uiAppId;
        private String flowId;
        private String flowName;
        private String executionId;
        private String executionStatus;
        /** 落库的简短失败原因 */
        private String errorMessage;
        /** 运行期间 logcat 里抓到了被测应用的崩溃 */
        private boolean appCrashed;

        /** 失败步骤。为 null 表示这次执行没有失败步骤（可能是停止或性能任务） */
        private LogLine failedStep;
        /** 失败点前后的日志窗口，含首条「开始执行流程」 */
        private List<LogLine> logWindow = new ArrayList<>();
        /** 失败步骤引用的元素行（含备用定位） */
        private List<ElementRef> elements = new ArrayList<>();
        /** 现场截图，最后一张通常就是失败瞬间 */
        private List<Artifact> screenshots = new ArrayList<>();
        /** 失败瞬间的控件树摘要 */
        private PageSource pageSource;

        /**
         * 证据缺口说明，如「截图已被保留策略清理」「该任务执行时还没有控件树留存」。
         * <p>
         * 这一项必须往下传：模型看不到自己没拿到什么，缺证据时它照样会给出一个语气笃定的结论。
         * 把缺口显式写进上下文，才能让它有依据地回答 UNKNOWN。
         */
        private List<String> gaps = new ArrayList<>();
        /** 文本证据的粗略字符数，供调用方判断是否还要再裁剪 */
        private int textChars;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LogLine {
        /** 在完整日志中的下标，便于前端定位跳转 */
        private Integer index;
        private String step;
        private String status;
        private String level;
        private String message;
        private String selector;
        private String elementKey;
        private String timestamp;
        private Boolean screenshotSettled;
    }

    /** 元素行快照：归因结论若是 ELEMENT_BROKEN，要改的就是这一行 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ElementRef {
        private String id;
        private String nameEn;
        private String nameZh;
        /** 视觉说明，如「左上角三横线汉堡」，模型看图时能对上 */
        private String visualHint;
        private String pageId;
        private String platform;
        private String locatorType;
        private String locatorValue;
        private List<String> locatorFallbacks = new ArrayList<>();
        private String status;
        private String source;
        private String lastVerifiedAt;
        /** 元素表里查不到时填写原因，如「无 ACTIVE 元素」 */
        private String missingReason;
    }

    /** 现场文件：只给路径与大小，二进制由调用方按需读取 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Artifact {
        private String path;
        private String fileName;
        private long sizeBytes;
        private String modifiedAt;
        /** 是否为「页面稳定后」拍的最终态；连拍的中间帧做判定容易误判 */
        private Boolean settled;
        /** 关联的日志步骤名 */
        private String step;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PageSource {
        private String path;
        private String capturedAt;
        /** 缩进大纲，已剔除纯布局节点 */
        private String outline;
        private int totalNodes;
        private int keptNodes;
        private boolean truncated;
        private String rootTag;
        /** 页面上的可见文案，用来快速判断停在哪一页 */
        private List<String> visibleTexts = new ArrayList<>();
    }

    /** 写回的归因结论 */
    @Data
    public static class Verdict {
        private Category category;
        /** 0-100，模型或人对结论的把握 */
        private Integer confidence;
        private String reason;
        private String suggestion;
        /** 判成 ELEMENT_BROKEN 时给出的候选定位类型 */
        private String suggestedLocatorType;
        /** 判成 ELEMENT_BROKEN 时给出的候选定位串，供一键采纳 */
        private String suggestedLocatorValue;
        /** 缺省 AI */
        private Source source;
        /** 模型标识，如 claude-opus-5；人工结论留空 */
        private String model;
        private String createdBy;
    }
}
