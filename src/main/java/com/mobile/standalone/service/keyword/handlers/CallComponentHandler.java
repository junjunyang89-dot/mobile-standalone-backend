package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.entity.UiFlow;
import com.mobile.standalone.service.UiFlowService;
import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 调用已保存的流程组件（如登录）。参数只有一个流程 ID，走通用参数渲染即可。
 */
@Component
@RequiredArgsConstructor
public class CallComponentHandler implements KeywordHandler {

    /** 组件互相调用的最大嵌套深度，防止环形引用把线程栈跑爆 */
    public static final int MAX_COMPONENT_DEPTH = 5;

    private final UiFlowService uiFlowService;

    @Override
    public String code() {
        return "CALL_COMPONENT";
    }

    @Override
    public String nameZh() {
        return "调用组件";
    }

    @Override
    public String category() {
        return "FLOW";
    }

    @Override
    public String description() {
        return "复用已保存的流程组件（如登录）";
    }

    @Override
    public int sortOrder() {
        return 100;
    }

    @Override
    public String shortLabel() {
        return "组件";
    }

    /** 不重试：组件内步骤已经执行过，重试会重复执行整个组件 */
    @Override
    public boolean retryable() {
        return false;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(ParamDef.requiredString("flowId", "组件流程ID"));
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        if (ctx.getDepth() >= MAX_COMPONENT_DEPTH) {
            throw ctx.fail("调用组件", "组件调用嵌套过深（>" + MAX_COMPONENT_DEPTH + "）", null);
        }
        String childId = ctx.str("flowId");
        if (childId == null || childId.isBlank()) {
            throw ctx.fail("调用组件", "CALL_COMPONENT 缺少 flowId", null);
        }
        UiFlow child = uiFlowService.get(childId);
        if (child == null) {
            throw ctx.fail("调用组件", "组件流程不存在: " + childId, null);
        }
        ctx.ok("调用组件", child.getName() + " (" + childId + ")");

        // 子流程可能绑定了别的应用字典，元素要按子流程的应用解析
        String childApp = child.getAppId() != null && !child.getAppId().isBlank()
                ? child.getAppId() : ctx.getUiAppId();
        ctx.withUiAppId(childApp)
                .runNested(ctx.getRuntime().parseSteps(child.getSteps()), ctx.getDepth() + 1);
    }
}
