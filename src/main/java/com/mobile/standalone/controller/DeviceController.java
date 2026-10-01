package com.mobile.standalone.controller;

import com.mobile.standalone.entity.Device;
import com.mobile.standalone.service.DeviceService;
import com.mobile.standalone.util.TestEnvironment;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;
    private final com.mobile.standalone.service.IosDeviceReadiness iosDeviceReadiness;
    private final com.mobile.standalone.service.DeviceLockService deviceLockService;
    private final com.mobile.standalone.service.IosWdaToolkitService iosWdaToolkitService;
    private final com.mobile.standalone.service.pool.EmulatorLauncher emulatorLauncher;
    private final com.mobile.standalone.service.pool.DevicePoolPageService devicePoolPageService;

    /**
     * 设备池页那一张表：已入库设备 + 还没开机的模拟器。
     * <p>
     * 不要拿这个去选设备或排班。没开机的 AVD 不是一台能派活的设备，
     * 混进 {@code GET /devices} 会让调度器以为池子里多了几台空闲机。
     */
    @org.springframework.web.bind.annotation.GetMapping("/pool")
    public ResponseEntity<?> pool() {
        return ResponseEntity.ok(devicePoolPageService.snapshot());
    }

    /**
     * 主控机上有哪些模拟器（AVD），各自起没起。
     * <p>
     * 设备列表回答不了这个问题：它只认已经启动、并且被 adb 扫到的东西，
     * 没启动的 AVD 在 adb 眼里根本不存在。而「有哪些模拟器可以用」问的是
     * 主控机上装了什么，是另一个问题，所以单独一个接口。
     */
    @org.springframework.web.bind.annotation.GetMapping("/emulators")
    public ResponseEntity<?> emulators() {
        return ResponseEntity.ok(overviewMap());
    }

    /**
     * 启动一台模拟器。
     * <p>
     * <b>立刻返回 202，不等开机</b>：冷启动 30~60 秒，同步等的话这个请求必然超时，
     * 而超时之后模拟器其实还在起——人会以为失败了再点一次，然后撞上「AVD 已在运行」。
     * 前端拿到 202 之后轮询 {@code GET /devices/emulators} 看状态就行。
     */
    @org.springframework.web.bind.annotation.PostMapping("/emulators/{avd}/start")
    public ResponseEntity<?> startEmulator(@org.springframework.web.bind.annotation.PathVariable String avd,
                                           @org.springframework.web.bind.annotation.RequestBody(required = false)
                                           java.util.Map<String, Object> body) {
        boolean wipeData = body != null && Boolean.parseBoolean(String.valueOf(body.get("wipeData")));
        com.mobile.standalone.service.pool.EmulatorLauncher.ActionResult r =
                emulatorLauncher.start(avd, wipeData);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", r.ok());
        out.put("state", r.state().name());
        out.put("message", r.message());
        return r.ok() ? ResponseEntity.accepted().body(out) : ResponseEntity.badRequest().body(out);
    }

    /**
     * 关掉一台模拟器。正被占用的一律不给关——
     * 跑着的用例会在中途失败，而失败信息只会说「设备连接断开」，
     * 查的人根本想不到是有人在另一个页面按了关闭。
     */
    @org.springframework.web.bind.annotation.PostMapping("/emulators/{avd}/stop")
    public ResponseEntity<?> stopEmulator(@org.springframework.web.bind.annotation.PathVariable String avd) {
        com.mobile.standalone.service.pool.EmulatorLauncher.ActionResult r = emulatorLauncher.stop(avd);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", r.ok());
        out.put("state", r.state().name());
        out.put("message", r.message());
        return r.ok() ? ResponseEntity.ok(out) : ResponseEntity.status(409).body(out);
    }

    private java.util.Map<String, Object> overviewMap() {
        com.mobile.standalone.service.pool.EmulatorLauncher.Overview o = emulatorLauncher.overview();
        java.util.List<java.util.Map<String, Object>> avds = new java.util.ArrayList<>();
        for (com.mobile.standalone.service.pool.EmulatorLauncher.AvdView v : o.avds()) {
            avds.add(com.mobile.standalone.service.pool.EmulatorLauncher.toMap(v));
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("emulatorCliAvailable", o.emulatorCliAvailable());
        out.put("avds", avds);
        out.put("runningUnmatched", o.runningUnmatched());
        out.put("runningCount", o.runningCount());
        out.put("maxRunning", o.maxRunning());
        out.put("hint", o.hint());
        return out;
    }

    /**
     * 全部设备的占用情况，一次拿完。
     * <p>
     * 不让前端逐台去调 {@code /{id}/lock}：六台就是六个请求，而设备列表每次刷新都要问一遍。
     */
    @org.springframework.web.bind.annotation.GetMapping("/occupancy")
    public ResponseEntity<?> occupancy(jakarta.servlet.http.HttpServletRequest request) {
        // 「这台是不是我借的」由后端算，不让前端拿 refId 自己比。
        // 前端要自己比的话，得把借用时拿到的 refId 存住——刷新一下页面就丢了，
        // 于是自己借的设备自己还不了，只能去找管理员强制解锁
        String me = manualRefId(request);
        java.util.Map<String, java.util.Map<String, Object>> out = new java.util.LinkedHashMap<>();
        deviceLockService.snapshot().forEach((deviceId, h) -> {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("kind", h.kind().name());
            m.put("kindLabel", h.kind().getDisplayName());
            m.put("label", h.label());
            m.put("refId", h.refId());
            m.put("since", String.valueOf(h.since()));
            m.put("describe", h.describe());
            m.put("mine", h.kind() == com.mobile.standalone.service.DeviceLockService.Kind.MANUAL
                    && me.equals(h.refId()));
            out.put(deviceId, m);
        });
        return ResponseEntity.ok(java.util.Map.of(
                "occupancy", out,
                "maxHoldMinutes", com.mobile.standalone.service.DeviceLockService.maxHold().toMinutes()));
    }

    /**
     * 借走一台设备手动用。
     * <p>
     * 走的是跑任务那把锁，所以借出去之后调度器就不会再往这台派活了——
     * 「借用」和「在跑任务」本来就是同一件事：这台现在归某个人/某件事，别人别碰。
     */
    @org.springframework.web.bind.annotation.PostMapping("/{deviceId}/reserve")
    public ResponseEntity<?> reserve(@org.springframework.web.bind.annotation.PathVariable String deviceId,
                                     @org.springframework.web.bind.annotation.RequestBody(required = false)
                                     java.util.Map<String, Object> body,
                                     jakarta.servlet.http.HttpServletRequest request) {
        Device device = deviceService.getDeviceById(deviceId);
        if (device == null) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", "设备不存在: " + deviceId));
        }
        String refId = manualRefId(request);
        String note = body == null ? null : String.valueOf(body.getOrDefault("note", "")).trim();
        String label = manualLabel(request) + (note == null || note.isEmpty() || "null".equals(note) ? "" : "·" + note);

        com.mobile.standalone.service.DeviceLockService.Holder blocker = deviceLockService.tryAcquire(
                deviceId, com.mobile.standalone.service.DeviceLockService.Kind.MANUAL, refId, label);
        if (blocker != null) {
            return ResponseEntity.status(409).body(java.util.Map.of(
                    "message", "这台正被占用：" + blocker.describe() + "。等它结束，或换一台"));
        }
        long minutes = com.mobile.standalone.service.DeviceLockService.maxHold().toMinutes();
        return ResponseEntity.ok(java.util.Map.of(
                "ok", true,
                "refId", refId,
                "message", "已借到「" + (device.getName() != null ? device.getName() : deviceId)
                        + "」。期间不会有自动化任务派进来。最长 " + minutes
                        + " 分钟后自动收回，还在用就点一下续借"));
    }

    /** 归还：只有借的人自己能还；别人占着的用 DELETE /lock 强制释放 */
    @org.springframework.web.bind.annotation.PostMapping("/{deviceId}/release")
    public ResponseEntity<?> releaseMine(@org.springframework.web.bind.annotation.PathVariable String deviceId,
                                         jakarta.servlet.http.HttpServletRequest request) {
        String refId = manualRefId(request);
        boolean ok = deviceLockService.release(deviceId, refId);
        return ResponseEntity.ok(java.util.Map.of(
                "released", ok,
                "message", ok ? "已归还，这台重新进入排班"
                        : "这台不是你借的（或已经还过了）。要强制释放请用设备详情里的解锁"));
    }

    /** 续借：还在用就把时间往后推，避免被当成忘记归还收走 */
    @org.springframework.web.bind.annotation.PostMapping("/{deviceId}/renew")
    public ResponseEntity<?> renew(@org.springframework.web.bind.annotation.PathVariable String deviceId,
                                   jakarta.servlet.http.HttpServletRequest request) {
        boolean ok = deviceLockService.renew(deviceId, manualRefId(request));
        return ResponseEntity.ok(java.util.Map.of(
                "renewed", ok,
                "message", ok ? "已续借" : "这台不是你借的（或已经还过了）"));
    }

    /** 人工停用 / 恢复：坏了、在维护的设备不该继续被派活，但也不该拔掉让人以为丢了 */
    @org.springframework.web.bind.annotation.PostMapping("/{deviceId}/disabled")
    public ResponseEntity<?> setDisabled(@org.springframework.web.bind.annotation.PathVariable String deviceId,
                                         @org.springframework.web.bind.annotation.RequestBody
                                         java.util.Map<String, Object> body) {
        boolean disabled = body != null && Boolean.parseBoolean(String.valueOf(body.get("disabled")));
        boolean ok = deviceService.setDisabled(deviceId, disabled);
        return ResponseEntity.ok(java.util.Map.of(
                "ok", ok,
                "disabled", disabled,
                "message", disabled ? "已停用，调度器不会再往这台派任务" : "已恢复，重新进入排班"));
    }

    /**
     * 借用的持有者标识：一个人一个。
     * <p>
     * 用用户名而不是每次生成一个随机 id，是为了让「归还」在换了浏览器、刷新过页面之后照样能用——
     * 借的是人，不是某个标签页。
     */
    private static String manualRefId(jakarta.servlet.http.HttpServletRequest request) {
        Object u = request.getAttribute(com.mobile.standalone.auth.AuthFilter.ATTR_USER);
        if (u instanceof com.mobile.standalone.auth.PlatformUser pu && pu.getUsername() != null) {
            return "manual:" + pu.getUsername();
        }
        return "manual:anonymous";
    }

    private static String manualLabel(jakarta.servlet.http.HttpServletRequest request) {
        Object u = request.getAttribute(com.mobile.standalone.auth.AuthFilter.ATTR_USER);
        if (u instanceof com.mobile.standalone.auth.PlatformUser pu) {
            String name = pu.getDisplayName() != null && !pu.getDisplayName().isBlank()
                    ? pu.getDisplayName() : pu.getUsername();
            return name != null ? name : "未登录用户";
        }
        return "未登录用户";
    }

    /** 这台设备现在被谁占着；没人占返回 null */
    @org.springframework.web.bind.annotation.GetMapping("/{deviceId}/lock")
    public ResponseEntity<?> lockOf(@org.springframework.web.bind.annotation.PathVariable String deviceId) {
        com.mobile.standalone.service.DeviceLockService.Holder h = deviceLockService.current(deviceId);
        return ResponseEntity.ok(h == null
                ? java.util.Map.of("locked", false)
                : java.util.Map.of("locked", true, "describe", h.describe(),
                        "refId", h.refId(), "since", String.valueOf(h.since())));
    }

    /**
     * 强制释放设备占用。
     * <p>
     * 锁是内存里的，只在执行线程正常收尾时释放。线程卡死或异常退出就会一直占着，
     * 而人看到的是「任务明明停了还说被占用」——在这之前唯一的办法是重启后端。
     * 自动兜底有宽限期（停止后 2 分钟），这个是给不想等的时候用的。
     */
    @org.springframework.web.bind.annotation.DeleteMapping("/{deviceId}/lock")
    public ResponseEntity<?> forceRelease(@org.springframework.web.bind.annotation.PathVariable String deviceId) {
        com.mobile.standalone.service.DeviceLockService.Holder removed = deviceLockService.forceRelease(deviceId);
        return ResponseEntity.ok(java.util.Map.of(
                "released", removed != null,
                "message", removed == null ? "该设备本来就没有被占用"
                        : "已强制释放：" + removed.describe() + "。若那个任务其实还在跑，两边会抢同一台机"));
    }

    /**
     * iOS 接新机体检：四个前提逐条查，直接说缺哪个、去哪儿点。
     * <p>
     * 人工步骤消不掉（Apple 有意为之），但「不知道卡在哪一步」可以消掉——
     * 「WDA 装完就没了」这个现象离真正的原因（签名 team 里没这台设备）隔得太远。
     */
    @org.springframework.web.bind.annotation.GetMapping("/{udid}/ios-readiness")
    public ResponseEntity<?> iosReadiness(@org.springframework.web.bind.annotation.PathVariable String udid) {
        return ResponseEntity.ok(iosDeviceReadiness.check(udid));
    }

    /**
     * iOS WDA / 签名修复工具箱：分步体检 + 可执行动作提示。
     * <p>
     * 证书吊销、同名歧义、冷却、未信任开发者——按步骤给出 tip；能自动修的带 actionId。
     */
    @GetMapping("/{udid}/ios-wda-toolkit")
    public ResponseEntity<?> iosWdaToolkit(@PathVariable String udid) {
        return ResponseEntity.ok(iosWdaToolkitService.diagnose(udid));
    }

    /**
     * 执行工具箱动作。危险操作（清理吊销证书）需 {@code confirm=true}。
     * <p>
     * action：clear_cooldown / ensure_running / rebuild_install / remove_revoked_certs
     */
    @PostMapping("/{udid}/ios-wda-toolkit/actions")
    public ResponseEntity<?> iosWdaToolkitAction(@PathVariable String udid,
                                                 @RequestBody Map<String, Object> body) {
        String action = body == null ? null : String.valueOf(body.getOrDefault("action", ""));
        boolean confirm = body != null && Boolean.TRUE.equals(body.get("confirm"));
        return ResponseEntity.ok(iosWdaToolkitService.runAction(udid, action, confirm));
    }

    /**
     * 首页用：已连接设备统计及列表。
     * 返回总数、Android/iOS/HarmonyOS 数量、以及已连接设备列表（设备名称、平台、状态）。
     */
    @GetMapping("/connected-summary")
    public ResponseEntity<DeviceService.ConnectedDeviceSummary> getConnectedDeviceSummary() {
        return ResponseEntity.ok(deviceService.getConnectedDeviceSummary());
    }

    /**
     * 设备详情：设备名称、电量、系统信息；已安装应用默认不含（很慢），见 {@code /devices/{id}/installed-apps}。
     * <p>
     * {@code includeApps=true} 兼容旧调用；{@code includeRaw=true} 才拉 getprop/ideviceinfo 全文（较慢）。
     * 结果短时缓存约 60s，重复点开会快很多。
     */
    @GetMapping("/{id}/detail")
    public ResponseEntity<DeviceService.DeviceDetail> getDeviceDetail(
            @PathVariable String id,
            @RequestParam(required = false, defaultValue = "false") boolean includeApps,
            @RequestParam(required = false, defaultValue = "false") boolean includeRaw
    ) {
        DeviceService.DeviceDetail detail = deviceService.getDeviceDetail(id, includeApps, includeRaw);
        if (detail == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(detail);
    }

    /** 已安装应用（单独接口，避免拖慢详情首屏） */
    @GetMapping("/{id}/installed-apps")
    public ResponseEntity<?> getInstalledApps(@PathVariable String id) {
        List<DeviceService.InstalledAppItem> apps = deviceService.getInstalledApps(id);
        if (apps == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("installedApplications", apps));
    }

    @GetMapping
    public ResponseEntity<List<Device>> getAllDevices() {
        return ResponseEntity.ok(deviceService.getAllDevices());
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<Device> getDevice(@PathVariable String id) {
        Device device = deviceService.getDeviceById(id);
        if (device != null) {
            return ResponseEntity.ok(device);
        }
        return ResponseEntity.notFound().build();
    }
    
    @PostMapping("/{id}/connect")
    public ResponseEntity<Device> connectDevice(@PathVariable String id) {
        Device device = deviceService.connectDevice(id);
        if (device != null) {
            return ResponseEntity.ok(device);
        }
        return ResponseEntity.notFound().build();
    }
    
    @PostMapping("/{id}/disconnect")
    public ResponseEntity<Void> disconnectDevice(@PathVariable String id) {
        boolean success = deviceService.disconnectDevice(id);
        if (success) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }
    
    @GetMapping("/platform/{platform}")
    public ResponseEntity<List<Device>> getDevicesByPlatform(@PathVariable String platform) {
        try {
            Device.Platform platformEnum = Device.Platform.valueOf(platform.toUpperCase());
            List<Device> devices = deviceService.getAllDevices();
            // 过滤出匹配平台的设备
            List<Device> filteredDevices = devices.stream()
                .filter(device -> Device.matchesAutomationDeviceListFilter(device.getPlatform(), platformEnum))
                .toList();
            return ResponseEntity.ok(filteredDevices);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }
    
    /**
     * 手动触发设备扫描（用于测试）
     */
    /**
     * 设置这台设备跑哪个环境。
     * <p>
     * 绑在设备上而不是每次执行时选：一台手机上装的就是某个环境的包。
     * 四台机器同时跑（两台线上两台测试）时，不用每次去勾一遍。
     */
    @PutMapping("/{id}/environment")
    public ResponseEntity<?> setEnvironment(@PathVariable String id,
                                            @RequestBody Map<String, String> body) {
        Device device = deviceService.getDeviceById(id);
        if (device == null) {
            return ResponseEntity.notFound().build();
        }
        String env = body.get("environment");
        // 空串＝取消绑定，回到「执行时再定」；不能跟「这次不改」混为一谈
        device.setEnvironment(env == null || env.isBlank() ? "" : TestEnvironment.normalize(env));
        deviceService.updateDevice(device);
        return ResponseEntity.ok(Map.of("ok", true, "environment", device.getEnvironment()));
    }

    @PostMapping("/scan")
    public ResponseEntity<String> scanDevices() {
        try {
            deviceService.scanDevices();
            return ResponseEntity.ok("设备扫描完成");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("设备扫描失败: " + e.getMessage());
        }
    }
}
