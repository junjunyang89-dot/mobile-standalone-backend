package com.mobile.standalone.controller;

import com.mobile.standalone.service.perf.MetricBaseline;
import com.mobile.standalone.service.perf.MetricBaselineService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 版本质量卡：这个版本比上个版本，哪些指标退步了。
 * <p>
 * 两周一个大版本，发版评审上要拿出来的就是这个。
 */
@RestController
@RequestMapping("/perf-baseline")
@RequiredArgsConstructor
public class MetricBaselineController {

    private final MetricBaselineService service;

    /** 这个包测过哪些版本，新的在前 */
    @GetMapping("/versions")
    public ResponseEntity<List<String>> versions(@RequestParam String packageName,
                                                 @RequestParam(defaultValue = "ANDROID") String platform) {
        return ResponseEntity.ok(service.versionsOf(packageName, platform));
    }

    /** 变差的排最前——打开报告，人要看的永远是「哪儿退步了」 */
    @GetMapping("/compare")
    public ResponseEntity<List<MetricBaseline>> compare(
            @RequestParam String packageName,
            @RequestParam(defaultValue = "ANDROID") String platform,
            @RequestParam String versionName,
            @RequestParam(required = false) String deviceModel) {
        return ResponseEntity.ok(
                service.compareWithPrevious(packageName, platform, versionName, deviceModel));
    }
}
