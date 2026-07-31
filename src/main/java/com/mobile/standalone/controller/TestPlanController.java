package com.mobile.standalone.controller;

import com.mobile.standalone.entity.TestPlan;
import com.mobile.standalone.service.TestPlanService;
import com.mobile.standalone.service.scheduler.QuartzSchedulerService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 测试计划管理控制器
 */
@RestController
@RequestMapping("/test-plans")
@RequiredArgsConstructor
@Slf4j
public class TestPlanController {
    
    private final TestPlanService testPlanService;
    private final QuartzSchedulerService quartzSchedulerService;
    
    @GetMapping
    public ResponseEntity<List<TestPlan>> getAllPlans() {
        return ResponseEntity.ok(testPlanService.getAllPlans());
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<TestPlan> getPlan(@PathVariable String id) {
        TestPlan plan = testPlanService.getPlanById(id);
        if (plan != null) {
            return ResponseEntity.ok(plan);
        }
        return ResponseEntity.notFound().build();
    }
    
    @GetMapping("/device/{deviceId}")
    public ResponseEntity<List<TestPlan>> getPlansByDeviceId(@PathVariable String deviceId) {
        return ResponseEntity.ok(testPlanService.getPlansByDeviceId(deviceId));
    }
    
    @GetMapping("/app/{appId}")
    public ResponseEntity<List<TestPlan>> getPlansByAppId(@PathVariable String appId) {
        return ResponseEntity.ok(testPlanService.getPlansByAppId(appId));
    }

    /**
     * 按计划类型获取定时计划：iOS、Android、鸿蒙、pogpog
     */
    @GetMapping("/plan-type/{planType}")
    public ResponseEntity<List<TestPlan>> getPlansByPlanType(@PathVariable String planType) {
        try {
            TestPlan.PlanType type = TestPlan.PlanType.valueOf(planType.toUpperCase());
            return ResponseEntity.ok(testPlanService.getPlansByPlanType(type));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }
    
    @PostMapping
    public ResponseEntity<TestPlan> createPlan(@RequestBody TestPlan plan) {
        try {
            TestPlan created = testPlanService.createPlan(plan);
            return ResponseEntity.ok(created);
        } catch (Exception e) {
            log.error("创建测试计划失败", e);
            return ResponseEntity.badRequest().build();
        }
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<TestPlan> updatePlan(@PathVariable String id, @RequestBody TestPlan plan) {
        try {
            TestPlan updated = testPlanService.updatePlan(id, plan);
            if (updated != null) {
                return ResponseEntity.ok(updated);
            }
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("更新测试计划失败", e);
            return ResponseEntity.badRequest().build();
        }
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePlan(@PathVariable String id) {
        boolean success = testPlanService.deletePlan(id);
        if (success) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }
    
    /**
     * 启用/禁用测试计划
     */
    @PostMapping("/{id}/enable")
    public ResponseEntity<Void> enablePlan(@PathVariable String id, @RequestBody EnableRequest request) {
        try {
            TestPlan plan = testPlanService.getPlanById(id);
            if (plan == null) {
                return ResponseEntity.notFound().build();
            }
            
            plan.setEnabled(request.getEnabled());
            testPlanService.updatePlan(id, plan);
            
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("启用/禁用测试计划失败", e);
            return ResponseEntity.badRequest().build();
        }
    }
    
    /**
     * 立即执行测试计划
     */
    @PostMapping("/{id}/trigger")
    public ResponseEntity<String> triggerPlan(@PathVariable String id) {
        try {
            quartzSchedulerService.triggerPlan(id);
            return ResponseEntity.ok("测试计划已触发执行");
        } catch (Exception e) {
            log.error("触发测试计划失败", e);
            return ResponseEntity.badRequest().body("触发失败: " + e.getMessage());
        }
    }
    
    /**
     * 暂停测试计划
     */
    @PostMapping("/{id}/pause")
    public ResponseEntity<Void> pausePlan(@PathVariable String id) {
        try {
            quartzSchedulerService.pausePlan(id);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("暂停测试计划失败", e);
            return ResponseEntity.badRequest().build();
        }
    }
    
    /**
     * 恢复测试计划
     */
    @PostMapping("/{id}/resume")
    public ResponseEntity<Void> resumePlan(@PathVariable String id) {
        try {
            quartzSchedulerService.resumePlan(id);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("恢复测试计划失败", e);
            return ResponseEntity.badRequest().build();
        }
    }
    
    @Data
    public static class EnableRequest {
        private Boolean enabled;
    }
}
