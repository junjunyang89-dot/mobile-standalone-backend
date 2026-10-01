package com.mobile.standalone.controller;

import com.mobile.standalone.service.record.FlowRecorder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 流程录制：人在截图上点，平台记一步、再驱动设备点一下。
 *
 * <p>录出来的元素一律是 {@code source=RECORDED} 的待审候选，页面归属留空——
 * 归属只能人来定，机器猜出来的会毁掉这个字段的价值。
 */
@RestController
@RequestMapping("/ui-recordings")
@RequiredArgsConstructor
public class UiRecordingController {

    private final FlowRecorder recorder;

    @PostMapping
    public ResponseEntity<?> start(@RequestBody StartRequest body) {
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "请求体不能为空"));
        }
        try {
            FlowRecorder.Session s = recorder.start(
                    body.getDeviceId(), body.getAppId(), body.getPlatform(), body.getName(),
                    body.getLaunchApp() == null || body.getLaunchApp());
            return ResponseEntity.ok(FlowRecorder.toMap(s));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "开始录制失败"));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        try {
            return ResponseEntity.ok(FlowRecorder.toMap(recorder.get(id)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 在截图上点一下。xRatio / yRatio 是相对截图的 0~1，跟真机点选采集用的是同一套坐标，
     * 前端那边的换算可以直接照搬。
     */
    @PostMapping("/{id}/tap")
    public ResponseEntity<?> tap(@PathVariable String id, @RequestBody TapRequest body) {
        if (body == null || body.getXRatio() == null || body.getYRatio() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "xRatio / yRatio 必填"));
        }
        try {
            FlowRecorder.Step step = recorder.tap(id, body.getXRatio(), body.getYRatio());
            FlowRecorder.Session s = recorder.get(id);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("step", FlowRecorder.toMap(step, s.steps.size()));
            out.put("screenshotUrl", s.screenshotUrl);
            out.put("totalSteps", s.steps.size());
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "记录这一步失败"));
        }
    }

    @PostMapping("/{id}/swipe")
    public ResponseEntity<?> swipe(@PathVariable String id, @RequestBody SwipeRequest body) {
        if (body == null || body.getX1Ratio() == null || body.getY1Ratio() == null
                || body.getX2Ratio() == null || body.getY2Ratio() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "x1Ratio/y1Ratio/x2Ratio/y2Ratio 必填"));
        }
        try {
            FlowRecorder.Step step = recorder.swipe(id, body.getX1Ratio(), body.getY1Ratio(),
                    body.getX2Ratio(), body.getY2Ratio(), body.getDurationMs());
            FlowRecorder.Session s = recorder.get(id);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("step", FlowRecorder.toMap(step, s.steps.size()));
            out.put("screenshotUrl", s.screenshotUrl);
            out.put("totalSteps", s.steps.size());
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "这一下滑没记上"));
        }
    }

    /** 手动补一步：等待、返回、断言这些不是「点一下」的操作 */
    @PostMapping("/{id}/step")
    public ResponseEntity<?> addStep(@PathVariable String id, @RequestBody StepRequest body) {
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "请求体不能为空"));
        }
        try {
            FlowRecorder.Step step = recorder.addStep(
                    id, body.getKeywordCode(), body.getElementKey(), body.getParams());
            FlowRecorder.Session s = recorder.get(id);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("step", FlowRecorder.toMap(step, s.steps.size()));
            out.put("totalSteps", s.steps.size());
            out.put("screenshotUrl", s.screenshotUrl);
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 撤销最后一步。
     * <p>
     * 只撤记录，<b>设备上已经点过的操作撤不回来</b>——界面还停在点完之后那一屏。
     * 前端要把这句话说清楚，否则人会以为界面也退回去了，接着录出一堆错位的步骤。
     */
    @PostMapping("/{id}/undo")
    public ResponseEntity<?> undo(@PathVariable String id) {
        try {
            recorder.undo(id);
            FlowRecorder.Session s = recorder.get(id);
            return ResponseEntity.ok(Map.of(
                    "totalSteps", s.steps.size(),
                    "message", "已撤销记录。设备上那一步的实际操作不会回退，界面还停在原处，"
                            + "必要时先在手机上退回去再接着录"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** 人在设备上手动操作过之后，用它把截图对上 */
    @PostMapping("/{id}/refresh")
    public ResponseEntity<?> refresh(@PathVariable String id) {
        try {
            return ResponseEntity.ok(FlowRecorder.toMap(recorder.refresh(id)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/{id}/finish")
    public ResponseEntity<?> finish(@PathVariable String id, @RequestBody(required = false) FinishRequest body) {
        try {
            FlowRecorder.FinishResult r = recorder.finish(id, body == null ? null : body.getFlowName());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("flowId", r.flowId());
            out.put("flowName", r.flowName());
            out.put("steps", r.steps());
            out.put("elementsCreated", r.elementsCreated());
            out.put("elementsReused", r.elementsReused());
            out.put("warnings", r.warnings());
            out.put("message", "已生成流程草稿。新建的 " + r.elementsCreated()
                    + " 个元素是待审候选（来源＝录制），有空在元素管理里补上页面归属再提升为 ACTIVE");
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() != null ? e.getMessage() : "保存失败"));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> cancel(@PathVariable String id) {
        recorder.cancel(id);
        return ResponseEntity.ok(Map.of("ok", true, "message", "已放弃这次录制，设备已释放"));
    }

    @Data
    public static class StartRequest {
        private String deviceId;
        /** 必填：录出来的元素要落到具体应用下面 */
        private String appId;
        private String platform;
        private String name;
        /**
         * 开录前先拉起应用并记一步 LAUNCH_APP。不传＝开。
         * <p>只有「就是要从当前这一屏接着录」时才传 false——
         * 从桌面开始录出来的「上滑、找图标、点图标」，回放时几乎必错。
         */
        private Boolean launchApp;
    }

    @Data
    public static class TapRequest {
        @com.fasterxml.jackson.annotation.JsonProperty("xRatio")
        private Double xRatio;
        @com.fasterxml.jackson.annotation.JsonProperty("yRatio")
        private Double yRatio;
    }

    @Data
    public static class SwipeRequest {
        @com.fasterxml.jackson.annotation.JsonProperty("x1Ratio")
        private Double x1Ratio;
        @com.fasterxml.jackson.annotation.JsonProperty("y1Ratio")
        private Double y1Ratio;
        @com.fasterxml.jackson.annotation.JsonProperty("x2Ratio")
        private Double x2Ratio;
        @com.fasterxml.jackson.annotation.JsonProperty("y2Ratio")
        private Double y2Ratio;
        private Integer durationMs;
    }

    @Data
    public static class StepRequest {
        private String keywordCode;
        private String elementKey;
        private Map<String, Object> params;
    }

    @Data
    public static class FinishRequest {
        private String flowName;
    }
}
