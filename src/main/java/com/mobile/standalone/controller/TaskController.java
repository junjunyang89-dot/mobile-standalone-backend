package com.mobile.standalone.controller;

import com.mobile.standalone.dto.PageResult;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.service.FunctionalTestScenarioService;
import com.mobile.standalone.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final FunctionalTestScenarioService scenarioService;

    /**
     * 获取任务列表。支持分页与筛选。
     * 返回的每条任务均带 scenarioKey、scenarioName（展示名由 scenarioKey+platform 解析），供前端「测试场景」列展示。
     * 传 page、size 时返回分页结果（content、totalElements、totalPages、page、size）；
     * 不传时返回全量列表（兼容旧版）。
     */
    @GetMapping
    public ResponseEntity<?> getAllTasks(
            @RequestParam(required = false) Task.TaskType taskType,
            @RequestParam(required = false) Task.Platform platform,
            @RequestParam(required = false) Task.TaskStatus status,
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        boolean usePagination = page != null && size != null && page >= 1 && size >= 1;
        if (usePagination) {
            int p = page;
            int s = Math.min(100, size);
            PageResult<Task> result = taskService.searchTasksPage(taskType, platform, status, deviceId, appId, p, s);
            fillScenarioName(result.getContent());
            return ResponseEntity.ok(result);
        }
        List<Task> list;
        if (taskType == null && platform == null && status == null
                && (deviceId == null || deviceId.isBlank())
                && (appId == null || appId.isBlank())) {
            list = taskService.getAllTasks();
        } else {
            list = taskService.searchTasks(taskType, platform, status, deviceId, appId);
        }
        fillScenarioName(list);
        return ResponseEntity.ok(list);
    }

    /** 为任务列表填充 scenarioName，供前端「测试场景」列展示（与 functional-test 列表逻辑统一，含老数据回退） */
    private void fillScenarioName(List<Task> tasks) {
        if (tasks == null || scenarioService == null) return;
        for (Task task : tasks) {
            task.setScenarioName(scenarioService.getScenarioDisplayNameOrFallback(task));
        }
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<Task> getTask(@PathVariable String id) {
        Task task = taskService.getTaskById(id);
        if (task != null) {
            fillScenarioName(Collections.singletonList(task));
            return ResponseEntity.ok(task);
        }
        return ResponseEntity.notFound().build();
    }
    
    @PostMapping
    public ResponseEntity<Task> createTask(@RequestBody Task task) {
        Task created = taskService.createTask(task);
        return ResponseEntity.ok(created);
    }

    /**
     * 统一创建“测试任务”（按类型/平台创建），方便前端按分类展示。
     *
     * 前端推荐调用：POST /api/tasks/test
     */
    @PostMapping("/test")
    public ResponseEntity<Task> createTestTask(@RequestBody CreateTestTaskRequest request) {
        if (request.getTaskType() == null || request.getPlatform() == null) {
            return ResponseEntity.badRequest().build();
        }
        if (request.getDeviceId() == null || request.getDeviceId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (request.getAppId() == null || request.getAppId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        Task task = new Task();
        task.setName(request.getTaskName());
        task.setDescription(request.getRemarks());
        task.setTaskType(request.getTaskType());
        task.setPlatform(request.getPlatform());
        task.setDeviceId(request.getDeviceId());
        task.setAppId(request.getAppId());
        task.setConfig(request.getConfig());
        task.setStatus(Task.TaskStatus.PENDING);
        // 测试场景键：选「设置测试」传 settings，「设置-我的账号」传 settings_account，与 functional-test 创建一致
        String sk = request.getScenarioKey();
        if (sk != null && !sk.isBlank()) {
            task.setScenarioKey(sk.trim().toLowerCase());
        } else {
            task.setScenarioKey(null);
        }

        Task created = taskService.createTask(task);
        fillScenarioName(Collections.singletonList(created));
        return ResponseEntity.ok(created);
    }

    @lombok.Data
    public static class CreateTestTaskRequest {
        private String taskName; // 可选，不传则按 taskType 自动生成
        private String remarks;  // 可选
        private Task.TaskType taskType; // 必填
        private Task.Platform platform; // 必填：ANDROID/IOS/HARMONYOS/POG_ANDROID/POG_IOS
        private String deviceId; // 必填
        private String appId;    // 必填
        private String config;   // 可选：JSON字符串
        /** 测试场景键：settings=设置测试，settings_account=设置-我的账号，launch_app/login/search 等；不传则列表显示「未选择场景」 */
        private String scenarioKey;
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<Task> updateTask(@PathVariable String id, @RequestBody Task task) {
        Task updated = taskService.updateTask(id, task);
        if (updated != null) {
            return ResponseEntity.ok(updated);
        }
        return ResponseEntity.notFound().build();
    }
    
    /**
     * 删除任务（先停止再删除）。返回 JSON 体，避免前端 response.json() 解析空响应时报错。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteTask(@PathVariable String id) {
        boolean success = taskService.deleteTask(id);
        if (success) {
            return ResponseEntity.ok(Map.of("success", true, "message", "任务已删除"));
        }
        return ResponseEntity.notFound().build();
    }
    
    @PostMapping("/{id}/run")
    public ResponseEntity<Map<String, Object>> runTask(@PathVariable String id) {
        boolean success = taskService.runTask(id);
        if (success) {
            return ResponseEntity.ok(Map.of("success", true, "message", "任务已提交执行"));
        }
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", "任务不存在或已在运行"));
    }
    
    /**
     * 停止任务。返回 JSON 体，避免前端 response.json() 解析空响应时报「Unexpected end of JSON input」。
     */
    @PostMapping("/{id}/stop")
    public ResponseEntity<Map<String, Object>> stopTask(@PathVariable String id) {
        boolean success = taskService.stopTask(id);
        if (success) {
            return ResponseEntity.ok(Map.of("success", true, "message", "任务已停止"));
        }
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", "任务不存在"));
    }
}
