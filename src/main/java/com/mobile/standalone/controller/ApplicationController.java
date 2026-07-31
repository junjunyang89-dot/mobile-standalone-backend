package com.mobile.standalone.controller;

import com.mobile.standalone.entity.Application;
import com.mobile.standalone.service.ApplicationService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/applications")
@RequiredArgsConstructor
@Slf4j
public class ApplicationController {
    
    private final ApplicationService applicationService;
    private final com.mobile.standalone.service.DeviceManager deviceManager;
    private final com.mobile.standalone.mapper.DeviceMapper deviceMapper;
    
    @Value("${app.upload-dir:uploads/applications}")
    private String uploadDir;
    
    @GetMapping
    public ResponseEntity<List<Application>> getAllApplications() {
        return ResponseEntity.ok(applicationService.getAllApplications());
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<Application> getApplication(@PathVariable String id) {
        Application application = applicationService.getApplicationById(id);
        if (application != null) {
            return ResponseEntity.ok(application);
        }
        return ResponseEntity.notFound().build();
    }
    
    @GetMapping("/platform/{platform}")
    public ResponseEntity<List<Application>> getApplicationsByPlatform(@PathVariable String platform) {
        try {
            Application.Platform platformEnum = Application.Platform.valueOf(platform.toUpperCase());
            return ResponseEntity.ok(applicationService.getApplicationsByPlatform(platformEnum));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }
    
    /**
     * 获取设备已安装的应用列表
     * 通过设备ID获取该设备上已安装的所有应用。
     * 默认返回数据库缓存；传 refresh=true 时强制从设备重新扫描（可拿到新安装的应用，如 com.arca.egames.intl）。
     */
    @GetMapping("/devices/{deviceId}/applications")
    public ResponseEntity<?> getApplicationsByDeviceId(
            @PathVariable String deviceId,
            @RequestParam(required = false, defaultValue = "false") Boolean refresh) {
        log.info("获取设备 {} 的应用列表, refresh={}", deviceId, refresh);
        try {
            List<Application> applications = applicationService.getApplicationsByDeviceId(deviceId, Boolean.TRUE.equals(refresh));
            log.info("设备 {} 共有 {} 个应用", deviceId, applications.size());
            return ResponseEntity.ok(applications);
        } catch (RuntimeException e) {
            log.error("获取设备应用列表失败: {}", e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("获取设备应用列表失败: " + e.getMessage()));
        } catch (Exception e) {
            log.error("获取设备应用列表失败", e);
            return ResponseEntity.internalServerError()
                    .body(new ErrorResponse("服务器内部错误: " + e.getMessage()));
        }
    }
    
    @PostMapping
    public ResponseEntity<Application> createApplication(@RequestBody Application application) {
        Application created = applicationService.createApplication(application);
        return ResponseEntity.ok(created);
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<Application> updateApplication(@PathVariable String id, @RequestBody Application application) {
        Application updated = applicationService.updateApplication(id, application);
        if (updated != null) {
            return ResponseEntity.ok(updated);
        }
        return ResponseEntity.notFound().build();
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteApplication(@PathVariable String id) {
        boolean success = applicationService.deleteApplication(id);
        if (success) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }
    
    /**
     * 上传并安装应用
     */
    @PostMapping("/upload")
    public ResponseEntity<ApplicationUploadResponse> uploadApplication(
            @RequestParam("file") MultipartFile file,
            @RequestParam("platform") String platform,
            @RequestParam(value = "deviceId", required = false) String deviceId) {
        
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        
        try {
            // 验证平台类型
            Application.Platform platformEnum;
            try {
                platformEnum = Application.Platform.valueOf(platform.toUpperCase());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
            
            // 验证文件类型
            String originalFilename = file.getOriginalFilename();
            String fileExtension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                fileExtension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            
            // 根据平台验证文件类型
            boolean isValidFile = false;
            switch (platformEnum) {
                case ANDROID:
                case POG_ANDROID:
                    isValidFile = ".apk".equalsIgnoreCase(fileExtension);
                    break;
                case IOS:
                case POG_IOS:
                    isValidFile = ".ipa".equalsIgnoreCase(fileExtension) || ".app".equalsIgnoreCase(fileExtension);
                    break;
                case HARMONYOS:
                    isValidFile = ".hap".equalsIgnoreCase(fileExtension) || ".app".equalsIgnoreCase(fileExtension);
                    break;
            }
            
            if (!isValidFile) {
                return ResponseEntity.badRequest().build();
            }
            
            // 创建上传目录
            Path uploadPath = Paths.get(uploadDir);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }
            
            // 保存文件
            String fileName = UUID.randomUUID().toString() + fileExtension;
            Path filePath = uploadPath.resolve(fileName);
            Files.copy(file.getInputStream(), filePath);
            
            // 创建应用记录
            Application application = new Application();
            application.setId(UUID.randomUUID().toString());
            application.setName(originalFilename != null ? originalFilename.replace(fileExtension, "") : "Unknown");
            application.setPackageName(extractPackageName(originalFilename));
            application.setVersion("1.0.0"); // 可以从文件中提取
            application.setIconPath("");
            application.setPlatform(platformEnum);
            application.setInstallPath(filePath.toString());
            application.setInstalledAt(LocalDateTime.now());
            
            Application created = applicationService.createApplication(application);
            
            ApplicationUploadResponse response = new ApplicationUploadResponse();
            response.setApplicationId(created.getId());
            response.setApplicationName(created.getName());
            response.setPlatform(created.getPlatform().name());
            response.setInstallPath(created.getInstallPath());
            if (deviceId != null && !deviceId.isEmpty()) {
                boolean installed = installApplicationToDevice(created, deviceId);
                response.setMessage(installed ? "应用上传并安装成功" : "应用已上传，但安装到设备失败（请检查平台/签名/连接）");
            } else {
                response.setMessage("应用上传成功（未指定 deviceId，仅保存文件）");
            }
            
            return ResponseEntity.ok(response);
            
        } catch (IOException e) {
            log.error("上传应用文件失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * 安装应用到设备
     */
    @PostMapping("/{id}/install")
    public ResponseEntity<InstallResponse> installApplication(
            @PathVariable String id,
            @RequestParam("deviceId") String deviceId) {
        
        Application application = applicationService.getApplicationById(id);
        if (application == null) {
            return ResponseEntity.notFound().build();
        }
        
        try {
            boolean success = installApplicationToDevice(application, deviceId);
            
            InstallResponse response = new InstallResponse();
            response.setSuccess(success);
            response.setMessage(success ? "应用安装成功" : "应用安装失败");
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("安装应用失败", e);
            InstallResponse response = new InstallResponse();
            response.setSuccess(false);
            response.setMessage("安装失败: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }
    
    /**
     * 安装应用到设备的实际逻辑（本地 APK/IPA sideload）
     */
    private boolean installApplicationToDevice(Application application, String deviceId) {
        log.info("安装应用 {} 到设备 {}", application.getName(), deviceId);
        var device = deviceMapper.findById(deviceId);
        if (device == null) {
            log.warn("设备不存在: {}", deviceId);
            return false;
        }
        String path = application.getInstallPath();
        if (path == null || path.isBlank()) {
            return false;
        }
        if (com.mobile.standalone.entity.Device.isAndroidOrPogAndroid(device.getPlatform())
                || device.getPlatform() == com.mobile.standalone.entity.Device.Platform.HARMONYOS) {
            return deviceManager.installAndroidApp(deviceId, path);
        }
        if (com.mobile.standalone.entity.Device.usesIosAutomation(device.getPlatform())) {
            return deviceManager.installIOSApp(deviceId, path);
        }
        log.warn("不支持的安装平台: {}", device.getPlatform());
        return false;
    }
    
    /**
     * 从文件名提取包名
     */
    private String extractPackageName(String filename) {
        if (filename == null) {
            return "unknown";
        }
        // 简单实现，实际应该从应用文件中解析
        return filename.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();
    }
    
    @Data
    public static class ApplicationUploadResponse {
        private String applicationId;
        private String applicationName;
        private String platform;
        private String installPath;
        private String message;
    }
    
    @Data
    public static class InstallResponse {
        private boolean success;
        private String message;
    }
    
    @Data
    public static class ErrorResponse {
        private String error;
        
        public ErrorResponse(String error) {
            this.error = error;
        }
    }
}
