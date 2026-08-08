package com.mobile.standalone.service;

import com.mobile.standalone.dto.FailureDiagnosisDtos.Artifact;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Category;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Context;
import com.mobile.standalone.dto.FailureDiagnosisDtos.ElementRef;
import com.mobile.standalone.dto.FailureDiagnosisDtos.LogLine;
import com.mobile.standalone.dto.FailureDiagnosisDtos.PageSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归因提示词渲染。
 * <p>
 * 这份文本手工阶段贴进 Cursor，将来直连 API 时原样发出去，所以措辞本身就是要守住的东西——
 * 尤其是「缺证据时答 UNKNOWN」和「六类不合适可以说不合适」这两条，掉了就等于默认它必须给个结论。
 */
class FailureDiagnosisPromptRendererTest {

    private Context fullContext() {
        Context ctx = new Context();
        ctx.setTaskId("task-1");
        ctx.setTaskName("登录流程");
        ctx.setFlowId("flow-login");
        ctx.setFlowName("登录");
        ctx.setPlatform("ANDROID");
        ctx.setDeviceId("emulator-5554");
        ctx.setTaskStatus("FAILED");
        ctx.setErrorMessage("点击登录按钮失败");

        LogLine failed = new LogLine();
        failed.setStep("3 · 点击登录");
        failed.setStatus("fail");
        failed.setMessage("未找到元素，试过 2 个定位");
        failed.setSelector("id=login_btn");
        failed.setElementKey("login_submit_btn");
        ctx.setFailedStep(failed);

        LogLine ok = new LogLine();
        ok.setStep("2 · 输入手机号");
        ok.setStatus("success");
        ok.setMessage("已输入 138****8000");
        LogLine neutral = new LogLine();
        neutral.setStep("3 · 准备重试");
        neutral.setStatus("neutral");
        neutral.setMessage("第 1/2 次失败");
        ctx.setLogWindow(List.of(ok, neutral, failed));

        ElementRef el = new ElementRef();
        el.setNameEn("login_submit_btn");
        el.setNameZh("登录按钮");
        el.setVisualHint("底部蓝色通栏按钮");
        el.setLocatorType("id");
        el.setLocatorValue("com.demo.app:id/login_btn");
        el.setLocatorFallbacks(List.of("xpath=//*[@text='登录']"));
        ctx.setElements(List.of(el));

        PageSource ps = new PageSource();
        ps.setOutline("  Button id=\"login_btn_v2\" text=\"登录\" clickable");
        ps.setTotalNodes(120);
        ps.setKeptNodes(18);
        ps.setVisibleTexts(List.of("登录", "获取验证码"));
        ctx.setPageSource(ps);

        Artifact shot = new Artifact();
        shot.setPath("/tmp/screenshots/task-1/step-100.png");
        shot.setStep("3 · 点击登录");
        shot.setSettled(true);
        ctx.setScreenshots(List.of(shot));
        return ctx;
    }

    @Test
    void 六个分类都出现在提示词里() {
        String out = FailureDiagnosisPromptRenderer.render(fullContext());

        for (Category c : Category.values()) {
            assertTrue(out.contains(c.name()), "缺少分类 " + c.name());
        }
    }

    /** 分类是拍脑袋定的，不留这个口子就永远不知道分错了 */
    @Test
    void 允许回答六类都不合适() {
        String out = FailureDiagnosisPromptRenderer.render(fullContext());

        assertTrue(out.contains("都不合适"), out);
    }

    /** 缺证据时硬凑一个结论，比没有归因更麻烦 */
    @Test
    void 始终要求证据不足时答UNKNOWN() {
        String withGaps = FailureDiagnosisPromptRenderer.render(contextWithGaps());
        String withoutGaps = FailureDiagnosisPromptRenderer.render(fullContext());

        assertTrue(withGaps.contains("UNKNOWN"), withGaps);
        assertTrue(withoutGaps.contains("UNKNOWN"), withoutGaps);
    }

    private Context contextWithGaps() {
        Context ctx = fullContext();
        ctx.setScreenshots(List.of());
        ctx.setPageSource(null);
        ctx.getGaps().add("没有找到截图（可能已被保留策略清理）");
        return ctx;
    }

    /**
     * 缺口要排在证据之前。先看到「有图有控件树」再看到「其实图没了」，
     * 对同一份剩余证据的解读完全不同。
     */
    @Test
    void 证据缺口排在失败步骤之前() {
        String out = FailureDiagnosisPromptRenderer.render(contextWithGaps());

        int gapPos = out.indexOf("证据缺口");
        int stepPos = out.indexOf("## 失败步骤");
        assertTrue(gapPos > 0, "应输出证据缺口一节");
        assertTrue(gapPos < stepPos, "缺口应排在证据之前");
        assertTrue(out.contains("不要当成「不存在」"), out);
    }

    @Test
    void 没有缺口时不输出缺口小节() {
        String out = FailureDiagnosisPromptRenderer.render(fullContext());

        assertFalse(out.contains("证据缺口"), out);
    }

    @Test
    void 失败与中立事件在日志里可区分() {
        String out = FailureDiagnosisPromptRenderer.render(fullContext());

        assertTrue(out.contains("x 3 · 点击登录"), out);
        assertTrue(out.contains("! 3 · 准备重试"), out);
        assertTrue(out.contains(". 2 · 输入手机号"), out);
        // 不解释符号含义的话，中立事件会被当成失败读
        assertTrue(out.contains("不算不通过"), out);
    }

    @Test
    void 元素的主定位与备用定位都带上() {
        String out = FailureDiagnosisPromptRenderer.render(fullContext());

        assertTrue(out.contains("com.demo.app:id/login_btn"), out);
        assertTrue(out.contains("xpath=//*[@text='登录']"), out);
        assertTrue(out.contains("底部蓝色通栏按钮"), out);
    }

    /** 元素查不到本身就是判据，不能渲染成一片空白 */
    @Test
    void 元素查不到时说明原因() {
        Context ctx = fullContext();
        ElementRef missing = new ElementRef();
        missing.setNameEn("login_submit_btn");
        missing.setMissingReason("元素表中无 ACTIVE 记录");
        ctx.setElements(List.of(missing));

        String out = FailureDiagnosisPromptRenderer.render(ctx);

        assertTrue(out.contains("元素表里查不到"), out);
        assertTrue(out.contains("被停用或改名"), out);
    }

    @Test
    void 控件树带上节点保留比例与标记说明() {
        String out = FailureDiagnosisPromptRenderer.render(fullContext());

        assertTrue(out.contains("18/120"), out);
        assertTrue(out.contains("not-displayed"), out);
        assertTrue(out.contains("被遮挡"), out);
    }

    /** 截断了不说，读的人会把「元素在更下面」当成「页面没这个元素」 */
    @Test
    void 控件树截断时明确提示() {
        Context ctx = fullContext();
        ctx.getPageSource().setTruncated(true);

        String out = FailureDiagnosisPromptRenderer.render(ctx);

        assertTrue(out.contains("被截断"), out);
    }

    /**
     * 截图不能标成「失败瞬间」。实测一次点击失败，最后一张截图跟控件树差了 31 秒——
     * 截图拍在动作开始前，控件树存在最终失败之后，中间隔着重试和逐个尝试备用定位。
     * 标错了会让人把「两份证据显示不同页面」当成矛盾去解释。
     */
    @Test
    void 截图给本地路径且不谎称是失败瞬间() {
        String out = FailureDiagnosisPromptRenderer.render(fullContext());

        assertTrue(out.contains("/tmp/screenshots/task-1/step-100.png"), out);
        assertTrue(out.contains("**（最后一张）**"), out);
        assertTrue(out.contains("页面已稳定"), out);
        assertFalse(out.contains("**（失败瞬间）**"), out);
        assertTrue(out.contains("不是同一时刻"), out);
    }

    /** 没有失败步骤的任务也得能渲染出来，不能抛异常 */
    @Test
    void 空上下文不抛异常() {
        Context empty = new Context();
        empty.setTaskId("task-x");

        String out = FailureDiagnosisPromptRenderer.render(empty);

        assertTrue(out.contains("没有失败步骤"), out);
        assertTrue(out.contains("category:"), out);
    }

    /**
     * 不发图却写着「请看截图」，模型会照着措辞脑补一段它没看过的画面描述——
     * 这种幻觉混进归因依据里最难识别，所以必须先讲清楚「你看不到这些图」。
     */
    @Test
    void 不提供截图时明确告知模型看不到() {
        String out = FailureDiagnosisPromptRenderer.render(fullContext(), false);

        assertTrue(out.contains("你看不到这些图"), out);
        assertTrue(out.contains("不要描述或推测截图内容"), out);
        assertFalse(out.contains("请把文件拖进来"), out);
        // 路径仍要保留：人工复核时得能打开
        assertTrue(out.contains("/tmp/screenshots/task-1/step-100.png"), out);
    }

    @Test
    void 给出可直接回填的输出格式() {
        String out = FailureDiagnosisPromptRenderer.render(fullContext());

        assertTrue(out.contains("category:"));
        assertTrue(out.contains("confidence:"));
        assertTrue(out.contains("reason:"));
        assertTrue(out.contains("suggestion:"));
    }
}
