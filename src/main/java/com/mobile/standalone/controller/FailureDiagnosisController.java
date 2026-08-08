package com.mobile.standalone.controller;

import com.mobile.standalone.dto.FailureDiagnosisDtos.Category;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Context;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Verdict;
import com.mobile.standalone.entity.TaskFailureDiagnosis;
import com.mobile.standalone.entity.UiElement;
import com.mobile.standalone.service.FailureDiagnosisPromptRenderer;
import com.mobile.standalone.service.FailureDiagnosisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 失败归因：取现场上下文、写回结论。
 * <p>
 * 上下文接口只负责把证据摆齐，不做判断——归因方是谁（模型、脚本、还是人在页面上点）
 * 由调用方决定，结论统一走 POST 写回。
 */
@RestController
@RequestMapping("/failure-diagnosis")
@RequiredArgsConstructor
@Slf4j
public class FailureDiagnosisController {

    private final FailureDiagnosisService diagnosisService;

    /**
     * 归因上下文：失败步骤 + 前后日志 + 元素定位 + 截图路径 + 控件树摘要。
     * <p>
     * 注意返回体里的 {@code gaps}：它列出这次拿不到哪些证据。缺证据时结论应当是 UNKNOWN，
     * 而不是拿剩下的证据硬凑一个。
     */
    @GetMapping("/{taskId}/context")
    public ResponseEntity<Context> context(@PathVariable String taskId) {
        return ResponseEntity.ok(diagnosisService.buildContext(taskId));
    }

    /**
     * 同一份上下文渲染成可整块粘贴的文本，贴进 Cursor / 对话框里判。
     * <p>
     * 内容与 {@code /context} 完全一致，只是排版给人读。截图不内嵌，末尾给本地文件路径，
     * 自行拖进去——图片转 base64 塞进文本会让这个响应大到没法用。
     */
    @GetMapping(value = "/{taskId}/prompt", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> prompt(@PathVariable String taskId) {
        Context ctx = diagnosisService.buildContext(taskId);
        return ResponseEntity.ok(FailureDiagnosisPromptRenderer.render(ctx));
    }

    /** 写回归因结论。source 缺省为 AI；人工复核请显式传 HUMAN */
    @PostMapping("/{taskId}")
    public ResponseEntity<TaskFailureDiagnosis> save(@PathVariable String taskId,
                                                     @RequestBody Verdict verdict) {
        return ResponseEntity.ok(diagnosisService.save(taskId, verdict));
    }

    /** 当前结论（人工优先），没有则返回 204 */
    @GetMapping("/{taskId}")
    public ResponseEntity<TaskFailureDiagnosis> current(@PathVariable String taskId) {
        TaskFailureDiagnosis current = diagnosisService.current(taskId);
        return current == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(current);
    }

    /** 全部结论，最新在前。AI 与人工的分歧都留在这里 */
    @GetMapping("/{taskId}/history")
    public ResponseEntity<List<TaskFailureDiagnosis>> history(@PathVariable String taskId) {
        return ResponseEntity.ok(diagnosisService.history(taskId));
    }

    /**
     * 采纳 AI 给出的候选定位，写回元素行。
     * <p>
     * 刻意做成人点一下才生效，不跟着归因自动改：定位是执行的根基，AI 判错的话
     * 后面每条引用这个元素的流程都会跟着错。原定位会被降级进备用定位，不会丢。
     */
    @PostMapping("/records/{id}/apply-locator")
    public ResponseEntity<UiElement> applyLocator(@PathVariable String id,
                                                  @RequestParam(required = false) String operator) {
        return ResponseEntity.ok(diagnosisService.applySuggestedLocator(id, operator));
    }

    /**
     * 人工复核：认可这条结论。
     * <p>
     * 没有这个动作，「人工改判率」恒为 0——分不清「AI 判对了」和「根本没人看」。
     * 改判走 POST 写一条 HUMAN 结论；这里是「看过且认可」，同样计入已复核。
     */
    @PostMapping("/records/{id}/review")
    public ResponseEntity<TaskFailureDiagnosis> review(@PathVariable String id,
                                                       @RequestParam(required = false) String operator) {
        return ResponseEntity.ok(diagnosisService.review(id, operator));
    }

    @DeleteMapping("/records/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        diagnosisService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** 分类字典：值 + 中文名 + 对应处理动作，供前端下拉直接用 */
    @GetMapping("/categories")
    public ResponseEntity<List<Map<String, String>>> categories() {
        List<Map<String, String>> out = java.util.Arrays.stream(Category.values())
                .map(c -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("value", c.name());
                    m.put("label", c.label());
                    m.put("action", c.action());
                    return m;
                })
                .toList();
        return ResponseEntity.ok(out);
    }
}
