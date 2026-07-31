package com.mobile.standalone.controller;

import com.mobile.standalone.entity.App;
import com.mobile.standalone.service.AppService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/apps")
@RequiredArgsConstructor
public class AppController {
    
    private final AppService appService;
    
    /**
     * 获取设备已安装的应用列表
     */
    @GetMapping("/devices/{deviceId}/apps")
    public ResponseEntity<List<App>> getInstalledApps(@PathVariable String deviceId) {
        List<App> apps = appService.getInstalledApps(deviceId);
        return ResponseEntity.ok(apps);
    }
    
    /**
     * 上传并安装应用
     */
    @PostMapping("/upload")
    public ResponseEntity<App> uploadAndInstallApp(
            @RequestParam("file") MultipartFile file,
            @RequestParam("deviceId") String deviceId
    ) {
        App app = appService.uploadAndInstallApp(file, deviceId);
        return ResponseEntity.ok(app);
    }
}
