package com.mobile.standalone.service.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.entity.UiElement;
import com.mobile.standalone.entity.UiFlow;
import com.mobile.standalone.service.DeviceInspectService;
import com.mobile.standalone.service.DeviceLockService;
import com.mobile.standalone.service.UiElementService;
import com.mobile.standalone.service.UiAppService;
import com.mobile.standalone.service.UiFlowService;
import com.mobile.standalone.service.driver.AndroidDriver;
import com.mobile.standalone.service.driver.PlatformDriver;
import com.mobile.standalone.service.driver.PlatformRouter;
import com.mobile.standalone.entity.Device;
import com.mobile.standalone.mapper.DeviceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 引导式录制：人在平台的截图上点，平台记一步、再驱动设备真的点一下。
 *
 * <p><b>为什么是「在截图上点」而不是「监听人在设备上的操作」</b>：
 * 要把一次点击翻译成「点了哪个元素」，必须拿到<b>点击那一刻</b>的控件树。
 * 而 {@code uiautomator dump} 要 300~800 毫秒，人点得比这快得多——
 * 等 dump 出来页面已经翻篇了，映射到的是下一屏的控件。
 * 反过来让人在截图上点就没有这个问题：截图和控件树是同一时刻冻结的，
 * 点中的一定是当时那个控件。代价是慢一点，换来的是每一步都对、而且每一步都被人确认过。
 *
 * <p><b>录制期间设备是被占住的</b>（走的还是那把设备锁）。不占的话调度器会往这台派任务，
 * 两边同时操作同一台手机——录出来的步骤会莫名其妙多出别人点的东西。
 *
 * <p>会话放内存：录制是连续几分钟的事，为它建表不值得。代价是后端重启会丢，
 * 所以 {@code finish} 做成随时可调用的——录一段就能先存成流程。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FlowRecorder {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 多久没动就回收，免得有人开着录制去开会，把设备占一下午 */
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(60);
    private static final int MAX_STEPS = 300;

    private final DeviceInspectService deviceInspectService;
    private final DeviceLockService deviceLockService;
    private final UiElementService uiElementService;
    private final UiFlowService uiFlowService;
    private final PlatformRouter platformRouter;
    private final AndroidDriver androidDriver;
    private final DeviceMapper deviceMapper;
    private final UiAppService uiAppService;

    @Value("${app.ui-element-preview-dir:${user.home}/.mobile-standalone/uploads/ui-elements}")
    private String previewDir;

    /** 录下来的一步 */
    public static class Step {
        public String keywordCode = "CLICK";
        public String elementKey;
        public String elementNameZh;
        public String locatorType;
        public String locatorValue;
        /** 这个定位在按下那一刻命中几个节点；> 1 的要提醒人 */
        public Integer matchCount;
        /** 操作前那一刻的截图，相对路径，直接能给 img 用 */
        public String previewImage;
        public Map<String, Object> params = new LinkedHashMap<>();
        public LocalDateTime at = LocalDateTime.now();
        /** 已经在元素库里存在的话，记下是哪条——审核时不用再判断一次 */
        public String existingElementId;
        public String existingElementStatus;
        /**
         * 这一步的定位靠不住，回放时大概率点空。
         * <p>在<b>录制当场</b>就标出来，人可以立刻退回去换个带文字的控件重点一次；
         * 等到收工、甚至等到回放失败才说，那一屏早翻过去了，只能整条重录。
         */
        public boolean fragile;
        /** 为什么靠不住，给人看的一句话 */
        public String fragileReason;
    }

    public static class Session {
        public String id;
        public String deviceId;
        public String appId;
        public String platform;
        public String name;
        public LocalDateTime startedAt = LocalDateTime.now();
        public LocalDateTime lastActiveAt = LocalDateTime.now();
        public final List<Step> steps = new ArrayList<>();
        /** 本次录制里已经给过 key 的定位，保证同一个控件在一条流程里只有一个 key */
        public final Map<String, String> keyByLocator = new LinkedHashMap<>();
        public String screenshotUrl;
    }

    public record FinishResult(String flowId, String flowName, int steps,
                               int elementsCreated, int elementsReused, List<String> warnings) {
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    // ==================== 会话 ====================

    public Session start(String deviceId, String appId, String platform, String name) {
        return start(deviceId, appId, platform, name, true);
    }

    /**
     * @param launchApp 开录之前先把应用拉起来，并把这一步记进流程（默认开）。
     *
     * <p><b>为什么默认开</b>：不开的话，人是从桌面开始录的——上滑打开抽屉、找图标、点图标。
     * 这三步会原样进流程，而它们每一步都是错的：
     * <ul>
     *   <li>回放时桌面未必停在第一屏，抽屉可能本来就开着</li>
     *   <li>图标位置随装了什么应用而变，只能靠坐标点</li>
     *   <li>换个 launcher（真机上几乎必然）整段作废</li>
     * </ul>
     * 而这三步想表达的意思其实只有一句「打开这个应用」——
     * {@code LAUNCH_APP} 一步就说完了，还是走包名，跟桌面长什么样无关。
     */
    public Session start(String deviceId, String appId, String platform, String name, boolean launchApp) {
        String id = deviceId == null ? "" : deviceId.trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("deviceId 必填");
        }
        if (appId == null || appId.isBlank()) {
            throw new IllegalArgumentException("appId 必填：录出来的元素要落到具体应用下面");
        }
        Device device = deviceMapper.findById(id);
        if (device == null) {
            throw new IllegalArgumentException("设备不存在: " + id);
        }
        if (!Device.isEmulator(device)) {
            throw new IllegalArgumentException("录制只用模拟器。真机留给生产和回归，别占");
        }
        sweepExpired();

        Session existing = sessionOfDevice(id);
        if (existing != null) {
            throw new IllegalStateException("这台正在录制中（" + existing.id + "），先结束或放弃那一次");
        }
        String recId = "rec-" + UUID.randomUUID();
        // 录制期间必须占住设备：不占的话调度器会往这台派任务，
        // 两边同时操作同一台手机，录出来的步骤里会混进别人点的东西
        DeviceLockService.Holder blocker = deviceLockService.tryAcquire(
                id, DeviceLockService.Kind.RECORD, recId,
                name != null && !name.isBlank() ? name : "流程录制");
        if (blocker != null) {
            throw new IllegalStateException("设备正被占用：" + blocker.describe());
        }

        Session s = new Session();
        s.id = recId;
        s.deviceId = id;
        s.appId = appId.trim();
        s.platform = platform == null || platform.isBlank() ? resolvePlatform(id) : platform.trim();
        s.name = name;
        try {
            s.screenshotUrl = refreshShot(id);
        } catch (RuntimeException e) {
            deviceLockService.release(id, recId);
            throw e;
        }
        if (launchApp) {
            prependLaunchStep(s);
        }
        sessions.put(recId, s);
        log.info("[record] 开始录制 {} device={} app={} 先启动应用={}", recId, id, s.appId, launchApp);
        return s;
    }

    /**
     * 记一步「启动应用」，并真的在设备上打开它。
     *
     * <p>参数刻意留空：回放时 {@code LAUNCH_APP} 会自己去应用字典按平台取包名，
     * 把包名写死在步骤里，换个环境的包（debug / release）就得改流程。
     *
     * <p>拉不起来不算失败——人可能就是想从当前这一屏接着录。只记一条提示。
     */
    private void prependLaunchStep(Session s) {
        Step step = new Step();
        step.keywordCode = "LAUNCH_APP";
        step.elementNameZh = "启动应用";
        step.previewImage = savePreview(s.deviceId);
        s.steps.add(step);

        String pkg = androidPackageOf(s.appId);
        boolean ok = false;
        if (pkg != null && !pkg.isBlank()) {
            try {
                ok = driverOf(s.deviceId).launchApp(s.deviceId, pkg);
            } catch (Exception e) {
                log.debug("[record] 启动应用失败 {}: {}", pkg, e.getMessage());
            }
        }
        if (!ok) {
            markFragile(step, "开录时没能自动拉起应用"
                    + (pkg == null || pkg.isBlank() ? "（应用字典里没配这个平台的包名）" : "（包名 " + pkg + "）")
                    + "。请手动切到应用首屏再接着录；这一步回放时仍会尝试启动");
        } else {
            waitForForeground(s.deviceId, pkg, Duration.ofSeconds(20));
        }
        s.screenshotUrl = refreshShotAfterAction(s.deviceId);
    }

    /**
     * 等应用真的到前台，再去拍第一张截图。
     *
     * <p>{@code launchApp} 一返回就拍的话，拍到的多半还是桌面或闪屏——
     * 而录制页把这张图当成「可以点了」的画面摆在人面前。
     * 人对着闪屏点下去，落到的是闪屏（甚至桌面）上的控件，
     * 录出来的第一步就是废的，而且看不出来。
     *
     * <p>等不到也不报错：接着往下走，人自己点「重新截图」就行。
     * 这里的目的是把「大概率对」变成「基本上一定对」，不是加一道闸。
     */
    private void waitForForeground(String deviceId, String pkg, Duration timeout) {
        if (pkg == null || pkg.isBlank()) return;
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            String fg = androidDriver.getForegroundPackage(deviceId);
            if (pkg.equals(fg)) {
                // 到前台只说明 Activity 起来了，首屏内容往往还在加载；
                // 再给一小段渲染时间，免得第一张截图是个空壳
                sleepQuietly(800);
                return;
            }
            sleepQuietly(300);
        }
        log.info("[record] 等应用到前台超时 device={} pkg={}，先按当前画面继续", deviceId, pkg);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String androidPackageOf(String appId) {
        try {
            var app = uiAppService.get(appId);
            return app == null ? null : app.getAndroidPackage();
        } catch (Exception e) {
            log.debug("[record] 取包名失败 appId={}: {}", appId, e.getMessage());
            return null;
        }
    }

    public Session get(String recordingId) {
        sweepExpired();
        Session s = sessions.get(recordingId);
        if (s == null) {
            throw new IllegalArgumentException("录制会话不存在或已过期: " + recordingId);
        }
        return s;
    }

    public void cancel(String recordingId) {
        Session s = sessions.remove(recordingId);
        if (s != null) {
            deviceLockService.release(s.deviceId, s.id);
            log.info("[record] 放弃录制 {}（已录 {} 步）", recordingId, s.steps.size());
        }
    }

    // ==================== 录一步 ====================

    /**
     * 在截图上点一下：先算出点中的是哪个元素、记一步，再驱动设备真的点。
     *
     * <p>顺序是有意的——<b>先记录后执行</b>。反过来的话，点击会让页面立刻变化，
     * 而这一步该记的元素属于变化<b>之前</b>那一屏；一旦执行在前，就只能去猜。
     */
    public Step tap(String recordingId, double xRatio, double yRatio) {
        Session s = get(recordingId);
        if (s.steps.size() >= MAX_STEPS) {
            throw new IllegalStateException("一次录制最多 " + MAX_STEPS + " 步，先结束保存再接着录");
        }
        s.lastActiveAt = LocalDateTime.now();

        DeviceInspectService.HitTestResult hit = deviceInspectService.hitTestForRecording(
                s.deviceId, xRatio, yRatio, null, null, s.appId);
        DeviceInspectService.HitCandidate best = pickCandidate(hit);
        rejectIfForeignApp(s, best, hit);

        Step step = new Step();
        step.keywordCode = "CLICK";
        step.previewImage = savePreview(s.deviceId);

        if (best == null) {
            // 一个像样的定位都没有——比如点在一个既没 id、也没文案、也没描述的容器上。
            //
            // 这种时候老老实实记成坐标点击，不要拿 @bounds 的 xpath 充数。
            // 后者看着是元素定位，锁的却是屏幕上那个像素方框，回放必然点空；
            // 更麻烦的是它会以「元素」的身份混进元素库，让人以为这一步是有定位的。
            // 坐标至少是诚实的：同机同分辨率能跑，换了就废——而这句话现在当场就说出来。
            step.locatorType = "coordinate";
            step.locatorValue = hit.getX() + "," + hit.getY();
            step.elementNameZh = "坐标点击(" + hit.getX() + "," + hit.getY() + ")";
            // 分清两种「只能记坐标」：控件树没抓到，和抓到了但那个位置没有像样的定位。
            // 前者是设备/时机问题，等一下重点一次就好；后者要换个控件点。修法完全不同
            String noTree = firstWarning(hit);
            markFragile(step, noTree != null ? noTree
                    : "点的位置上没有可用的元素定位（没有 id / 文案 / 描述），只能按坐标点。"
                    + "换分辨率或界面改版就会失效——建议改点里面带文字的子控件，重录这一步");
            step.params.put("x", hit.getX());
            step.params.put("y", hit.getY());
            // 坐标步骤同样要有 elementKey：CLICK 是 needsElement 的关键字，
            // 没有 key 的步骤回放时会直接倒在「缺少 elementKey」上，连点都点不出去
            String coordLocKey = "coordinate|" + step.locatorValue;
            step.elementKey = s.keyByLocator.computeIfAbsent(coordLocKey,
                    k -> uniqueKey(s, "tap_" + hit.getX() + "_" + hit.getY()));
            s.steps.add(step);
            clickAt(s, hit.getX(), hit.getY(), null);
            s.screenshotUrl = refreshShotAfterAction(s.deviceId);
            return step;
        }

        step.locatorType = best.getLocatorType();
        step.locatorValue = best.getLocatorValue();
        step.matchCount = best.getMatchCount();
        step.elementNameZh = firstNonBlank(best.getSuggestedNameZh(), best.getMatchedNameZh(),
                best.getText(), best.getContentDesc(), "控件" + (s.steps.size() + 1));
        if (step.matchCount != null && step.matchCount > 1) {
            markFragile(step, "这个定位在按下那一刻命中 " + step.matchCount + " 个节点，"
                    + "回放时只会点第 1 个。如果你点的不是第 1 个，这一步会点错对象");
        } else if (locatorTier(step.locatorType, step.locatorValue) >= TIER_SHAKY) {
            markFragile(step, "只能靠 " + step.locatorType + " 定位（没有 id / 文案 / 描述），界面一改就容易失效");
        }

        // 库里已经有同定位的元素就直接复用它的 key——
        // 这是「录制不该在库里造重复」的第一道，也是最有效的一道
        UiElement exist = uiElementService.findByExactLocator(
                s.appId, s.platform, step.locatorType, step.locatorValue);
        if (exist != null) {
            step.elementKey = exist.getNameEn();
            step.elementNameZh = exist.getNameZh();
            step.existingElementId = exist.getId();
            step.existingElementStatus = exist.getStatus();
        } else {
            String locKey = step.locatorType + "|" + step.locatorValue;
            step.elementKey = s.keyByLocator.computeIfAbsent(locKey,
                    k -> uniqueKey(s, best, step.elementNameZh));
        }

        s.steps.add(step);
        clickAt(s, hit.getX(), hit.getY(), best.getLocatorValue());
        s.screenshotUrl = refreshShotAfterAction(s.deviceId);
        return step;
    }

    /**
     * 在截图上拖一下：记 SWIPE，再按同样的起点终点滑模拟器。
     * 桌面启动器打开应用列表就是这么滑的；当成点击会落到 Play 商店那些图标上。
     */
    public Step swipe(String recordingId, double x1Ratio, double y1Ratio,
                      double x2Ratio, double y2Ratio, Integer durationMs) {
        Session s = get(recordingId);
        if (s.steps.size() >= MAX_STEPS) {
            throw new IllegalStateException("一次录制最多 " + MAX_STEPS + " 步，先结束保存再接着录");
        }
        s.lastActiveAt = LocalDateTime.now();
        int[] a = deviceInspectService.mapRatioToPixels(s.deviceId, x1Ratio, y1Ratio);
        int[] b = deviceInspectService.mapRatioToPixels(s.deviceId, x2Ratio, y2Ratio);
        int dur = durationMs == null ? 400 : (int) Math.max(280, Math.min(900, durationMs));
        String dir = inferSwipeDirection(a[0], a[1], b[0], b[1]);

        Step step = new Step();
        step.keywordCode = "SWIPE";
        step.elementNameZh = swipeLabel(dir);
        step.previewImage = savePreview(s.deviceId);
        step.params.put("direction", dir);
        step.params.put("durationMs", dur);
        step.params.put("times", 1);
        s.steps.add(step);

        PlatformDriver driver = driverOf(s.deviceId);
        if (!driver.swipe(s.deviceId, a[0], a[1], b[0], b[1], dur)) {
            throw new IllegalStateException("滑动没生效，再从截图下半屏往上拖一次");
        }
        s.screenshotUrl = refreshShotAfterAction(s.deviceId);
        return step;
    }

    static String inferSwipeDirection(int x1, int y1, int x2, int y2) {
        int dx = x2 - x1;
        int dy = y2 - y1;
        if (Math.abs(dy) >= Math.abs(dx)) {
            return dy < 0 ? "up" : "down";
        }
        return dx < 0 ? "left" : "right";
    }

    private static String swipeLabel(String dir) {
        return switch (dir) {
            case "up" -> "上滑";
            case "down" -> "下滑";
            case "left" -> "左滑";
            case "right" -> "右滑";
            default -> "滑动";
        };
    }

    /** 手动补一步：等待、返回、输入、断言这些不是「点一下」的操作 */
    public Step addStep(String recordingId, String keywordCode, String elementKey,
                        Map<String, Object> params) {
        Session s = get(recordingId);
        if (keywordCode == null || keywordCode.isBlank()) {
            throw new IllegalArgumentException("keywordCode 必填");
        }
        if (s.steps.size() >= MAX_STEPS) {
            throw new IllegalStateException("一次录制最多 " + MAX_STEPS + " 步，先结束保存再接着录");
        }
        s.lastActiveAt = LocalDateTime.now();
        Step step = new Step();
        step.keywordCode = keywordCode.trim().toUpperCase(Locale.ROOT);
        step.elementKey = elementKey == null || elementKey.isBlank() ? null : elementKey.trim();
        if (params != null) {
            step.params.putAll(params);
        }
        if ("INPUT".equals(step.keywordCode) || "INPUT_EDITTEXT".equals(step.keywordCode)) {
            Step src = lastLocatedStep(s);
            if (src == null || src.locatorValue == null) {
                throw new IllegalArgumentException("先在左边截图上点一下输入框，再补输入");
            }
            if (step.elementKey == null) {
                step.elementKey = src.elementKey;
            }
            step.elementNameZh = src.elementNameZh;
            step.locatorType = src.locatorType;
            step.locatorValue = src.locatorValue;
            Object raw = step.params.get("text");
            String text = raw == null ? "" : String.valueOf(raw);
            step.params.put("text", text);
            PlatformDriver driver = driverOf(s.deviceId);
            if (!driver.inputTextToElement(s.deviceId, src.locatorValue, text)) {
                throw new IllegalStateException("输入没写进去，确认焦点在输入框里再试");
            }
            s.screenshotUrl = refreshShotAfterAction(s.deviceId);
        } else if ("BACK".equals(step.keywordCode)) {
            driverOf(s.deviceId).pressBack(s.deviceId);
            s.screenshotUrl = refreshShotAfterAction(s.deviceId);
        } else if ("WAIT".equals(step.keywordCode)) {
            Object raw = step.params.get("durationMs");
            long ms = 0;
            if (raw instanceof Number) {
                ms = ((Number) raw).longValue();
            } else if (raw != null) {
                try {
                    ms = Long.parseLong(String.valueOf(raw));
                } catch (NumberFormatException ignored) {
                    ms = 0;
                }
            }
            if (ms > 0) {
                try {
                    Thread.sleep(Math.min(ms, 60_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        s.steps.add(step);
        return step;
    }

    private static Step lastLocatedStep(Session s) {
        for (int i = s.steps.size() - 1; i >= 0; i--) {
            Step st = s.steps.get(i);
            if (st.locatorValue != null && !st.locatorValue.isBlank()
                    && !"coordinate".equalsIgnoreCase(String.valueOf(st.locatorType))) {
                return st;
            }
        }
        return null;
    }

    public void undo(String recordingId) {
        Session s = get(recordingId);
        if (s.steps.isEmpty()) {
            throw new IllegalStateException("还没有录到任何一步");
        }
        Step removed = s.steps.remove(s.steps.size() - 1);
        s.lastActiveAt = LocalDateTime.now();
        // 只撤销记录，不会把设备上已经点过的操作撤回来——
        // 这一点必须让人知道，否则他会以为界面也退回去了
        log.info("[record] 撤销一步 {}: {} {}", recordingId, removed.keywordCode, removed.elementKey);
    }

    /** 重新拍一张。只出图，dump 后台走——自动轮询不能每次卡在控件树上。 */
    public Session refresh(String recordingId) {
        Session s = get(recordingId);
        s.lastActiveAt = LocalDateTime.now();
        DeviceInspectService.SnapshotResult r = deviceInspectService.snapshotAfterRecordingAction(s.deviceId);
        s.screenshotUrl = r == null ? null : r.getScreenshotUrl();
        return s;
    }

    // ==================== 收工 ====================

    /**
     * 落库：先把没见过的元素建成待审候选，再生成一条流程草稿。
     *
     * <p>元素一律 {@code status=CANDIDATE}、{@code source=RECORDED}、<b>页面归属留空</b>。
     * 页面归属只能人来定——机器猜出来的会毁掉这个字段的价值，而它是后续维护的唯一抓手。
     */
    public FinishResult finish(String recordingId, String flowName) {
        Session s = get(recordingId);
        if (s.steps.isEmpty()) {
            throw new IllegalStateException("一步都没录，没什么可保存的");
        }
        List<String> warnings = new ArrayList<>();
        int created = 0;
        int reused = 0;
        Map<String, String> keyReady = new LinkedHashMap<>();

        for (Step st : s.steps) {
            // 坐标步骤以前在这里被跳过，于是它没有元素、也就没有 elementKey，
            // 回放时倒在「缺少 elementKey」上——录的时候看着好好的，一跑全是这个错。
            // 坐标本来就是元素库支持的一种 locatorType，照建，审核时看着预览图再决定留不留。
            if (st.elementKey == null || st.locatorValue == null) {
                continue;
            }
            if (keyReady.containsKey(st.elementKey)) {
                continue;
            }
            UiElement exist = uiElementService.findByExactLocator(
                    s.appId, s.platform, st.locatorType, st.locatorValue);
            if (exist != null) {
                keyReady.put(st.elementKey, exist.getId());
                reused++;
                continue;
            }
            try {
                UiElement e = new UiElement();
                e.setAppId(s.appId);
                e.setPlatform(s.platform);
                e.setNameEn(st.elementKey);
                e.setNameZh(st.elementNameZh);
                e.setLocatorType(st.locatorType);
                e.setLocatorValue(st.locatorValue);
                e.setSource("RECORDED");
                e.setStatus("CANDIDATE");
                // 跟流程本身同一个道理：SHARED 是「两条轨道都收」，
                // 一批未审的候选元素不该默认出现在线上回归那一侧的选择列表里
                e.setCatalog("AUTOMATION");
                e.setPreviewImage(st.previewImage);
                e.setVisualHint(st.fragile
                        ? "录制于 " + s.startedAt.toLocalDate() + "，定位不稳待人工重定：" + st.fragileReason
                        : "录制于 " + s.startedAt.toLocalDate() + "，页面归属待人工确认");
                // pageId 刻意不填
                uiElementService.create(e);
                keyReady.put(st.elementKey, e.getId());
                created++;
            } catch (Exception ex) {
                warnings.add("元素「" + st.elementNameZh + "」入库失败：" + ex.getMessage());
            }
        }

        // 靠不住的步骤统一在这里报一次，带上步号——收工页面照着这个列表就能逐条决定重录哪几步。
        // 放在建元素的循环外面：那个循环对重复 key 会 continue，
        // 同一个坏定位被点了三次的话，写在里面只会报第一次。
        for (int i = 0; i < s.steps.size(); i++) {
            Step st = s.steps.get(i);
            if (st.fragile) {
                warnings.add("第 " + (i + 1) + " 步「" + st.elementNameZh + "」：" + st.fragileReason);
            }
        }

        String steps = stepsJson(s);
        UiFlow flow = new UiFlow();
        flow.setName(flowName != null && !flowName.isBlank() ? flowName.trim()
                : (s.name != null && !s.name.isBlank() ? s.name : "录制-" + s.startedAt.toLocalDate()));
        flow.setDescription("由录制生成（" + s.steps.size() + " 步），元素待人工审核");
        flow.setAppId(s.appId);
        flow.setPlatform(s.platform);
        flow.setSteps(steps);
        // 只进自动化轨，不进线上回归。
        //
        // SHARED 是「两条轨道都收」（见 RunTrack.catalogs），而这是一条刚录完的草稿：
        // 元素还是未审的 CANDIDATE、页面归属空着、一句断言都没有。
        // 让它出现在回归勾选列表里，迟早有人顺手勾上跑进生产批次。
        //
        // 生产环境那一侧确实还有 environment=TEST 兜着，但「测试环境的回归」是漏的；
        // 而且 SHARED 本来就是个需要人判断的声明——默认值该取最保守的那个，
        // 让人主动升级，而不是默认最宽松再指望有人回来收窄。
        flow.setCatalog("AUTOMATION");
        // 录制统一按测试环境：录的时候用的是 debug 包，直接标成生产会让它被误当成巡检用例
        flow.setEnvironment("TEST");
        // 本版新录的东西，上个版本的线上包里未必有对应界面——
        // 跑老包的回归批次会据此把它排除掉
        flow.setReleaseStage("TESTING");
        flow.setEnabled(true);
        UiFlow saved = uiFlowService.create(flow);

        sessions.remove(recordingId);
        deviceLockService.release(s.deviceId, s.id);
        log.info("[record] 录制完成 {} → flow={} 步数={} 新建元素={} 复用={}",
                recordingId, saved.getId(), s.steps.size(), created, reused);
        return new FinishResult(saved.getId(), saved.getName(), s.steps.size(), created, reused, warnings);
    }

    // ==================== 内部 ====================

    private String stepsJson(Session s) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Step st : s.steps) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("keywordCode", st.keywordCode);
            if (st.elementKey != null) {
                m.put("elementKey", st.elementKey);
            }
            if (!st.params.isEmpty()) {
                m.put("params", st.params);
            }
            out.add(m);
        }
        try {
            return JSON.writeValueAsString(out);
        } catch (Exception e) {
            throw new IllegalStateException("序列化步骤失败: " + e.getMessage(), e);
        }
    }

    /**
     * 挑一条定位：能跨分辨率复用的优先，其中<b>本屏唯一的再优先</b>，坐标是最后的退路。
     *
     * <p>唯一性这一条不能省。点选面板的第一顺位候选未必唯一——比如桌面上点一个 APP 图标，
     * 排第一的是 {@code nexuslauncher:id/icon}，而那一屏上 21 个图标全叫这个名字。
     * 录进去当时能跑（Appium 取第 1 个，碰巧就是它），换一屏、换个图标位置就点到别的去了，
     * 而且不报错——这正是「元素找得到、点击也成功、界面没反应」的来源。
     *
     * <p>命中数是 hitTest 现成算好的（{@code matchCount}），这里只是别把它丢掉。
     */
    /** 见 {@link com.mobile.standalone.util.LocatorQuality} —— 分档规则只有那一处定义 */
    static final int TIER_POSITIONAL = com.mobile.standalone.util.LocatorQuality.TIER_POSITIONAL;
    static final int TIER_SHAKY = com.mobile.standalone.util.LocatorQuality.TIER_SHAKY;

    /**
     * 给一个定位打质量档，数字越小越稳。
     *
     * <p>最要紧的一条：<b>{@code @bounds} 的 xpath 和坐标是一回事</b>。
     * {@code //android.widget.LinearLayout[@bounds='[0,1386][1080,1941]']} 写起来像元素定位，
     * 锁的却是屏幕上那个像素方框——多一行内容、换台分辨率不同的机器、甚至只是列表滚了一下，
     * 就再也匹配不到任何节点。
     *
     * <p>这里有个很容易反着看的陷阱，上一版就栽在上面：bounds 精确到像素，
     * 所以它<b>必然只命中一个节点</b>。于是「优先选唯一命中的那个」这条听着完全正确的规则，
     * 会稳定地把全场最差的定位挑出来——录的时候还看不出来（录制点击本来就走坐标），
     * 回放时整条流程一步都点不中。
     *
     * <p>结论：<b>唯一性只能在同一档内部比较，不能跨档</b>。一个命中 3 个节点的 id，
     * 也好过一个唯一命中的像素方框。
     */
    static int locatorTier(String type, String value) {
        return com.mobile.standalone.util.LocatorQuality.tier(type, value);
    }

    static int countIndexSegments(String xpath) {
        return com.mobile.standalone.util.LocatorQuality.countIndexSegments(xpath);
    }

    /**
     * 从候选里挑这一步用哪个定位。
     *
     * <p>打分：{@code 档 * 100 + (命中多个 ? 40 : 0) + 推荐序}。
     * 40 比推荐序的取值范围大，所以<b>同一档里唯一命中的一定排在前面</b>；
     * 但再唯一也翻不过一整档——这正是上一版缺的那道闸。
     *
     * <p>坐标档（含 {@code @bounds} 的 xpath、绝对下标路径）直接不参与比较。
     * 挑不到就返回 null，由调用方退回<b>明写的</b>坐标点击：
     * 一步烂定位伪装成元素，比一步诚实的坐标点击难查得多。
     */
    static DeviceInspectService.HitCandidate pickCandidate(DeviceInspectService.HitTestResult hit) {
        if (hit == null || hit.getCandidates() == null) {
            return null;
        }
        DeviceInspectService.HitCandidate best = null;
        int bestScore = Integer.MAX_VALUE;
        int order = 0;
        for (DeviceInspectService.HitCandidate c : hit.getCandidates()) {
            int seq = order++;
            int tier = locatorTier(c.getLocatorType(), c.getLocatorValue());
            if (tier >= TIER_POSITIONAL) continue;
            Integer n = c.getMatchCount();
            boolean multi = n != null && n > 1;
            int score = tier * 100 + (multi ? 40 : 0) + Math.min(seq, 39);
            if (score < bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    /**
     * 挑中的那个控件不属于被测应用，就<b>当场拒绝</b>，不记这一步。
     *
     * <p>桌面、权限弹窗上的控件也有 id、也有文案，录进来看着完全正常——
     * {@code com.google.android.apps.nexuslauncher:id/accessibility_action_view}
     * 会以「home」的名字躺进元素库，一直到回放才发现它压根不属于被测应用。
     * 报错必须发生在<b>人还在录的这一刻</b>：他现在知道自己刚点了哪儿。
     *
     * <p><b>只看挑中的那一个候选，不看整份候选列表。</b>
     * 这一点是踩过坑的：{@code uiautomator dump} 出来的是<b>所有窗口</b>，
     * 应用开着的时候，桌面那一层往往还在树里，而且是个铺满全屏的节点——
     * 于是屏幕上<b>任何一点</b>都同时「命中」桌面。
     * 按整份候选列表投票的话，正常在应用里点一下也会被判成「点到桌面」，
     * 这道本来用来防错的闸会变成没法录制。
     */
    private void rejectIfForeignApp(Session s, DeviceInspectService.HitCandidate best,
                                    DeviceInspectService.HitTestResult hit) {
        String expected = androidPackageOf(s.appId);
        if (expected == null || expected.isBlank()) return;

        String pkg = best != null ? best.getPackageName() : soleForeignPackage(hit, expected);
        if (pkg == null || pkg.isBlank()) return;
        if (pkg.equals(expected)) return;
        // 状态栏、输入法本来就叠在应用上，它们不是「跑错应用」
        if (pkg.startsWith("com.android.systemui") || pkg.contains("inputmethod")) return;

        throw new IllegalStateException("你点到的是 " + pkg + describeForeign(pkg)
                + "，不是被测应用 " + expected + "——这一步没有记下来。"
                + "先把设备切回应用（或点「重新截图」对齐画面）再点。"
                + "别的应用的控件录进流程，回放时一定找不到");
    }

    /**
     * 一个能用的候选都没挑出来时的兜底判断：<b>所有</b>带包名的候选都是外来应用，才算点错了地方。
     * <p>只要还有一个候选属于被测应用，说明人点的位置在应用上，只是那儿没有好定位——
     * 那是坐标兜底该处理的事，不该在这里拦。
     */
    private static String soleForeignPackage(DeviceInspectService.HitTestResult hit, String expected) {
        if (hit == null || hit.getCandidates() == null) return null;
        String foreign = null;
        for (DeviceInspectService.HitCandidate c : hit.getCandidates()) {
            String p = c.getPackageName();
            if (p == null || p.isBlank()) continue;
            if (p.equals(expected)) return null;
            if (foreign == null) foreign = p;
        }
        return foreign;
    }

    private static String describeForeign(String pkg) {
        String p = pkg.toLowerCase(Locale.ROOT);
        if (p.contains("launcher") || p.contains("home")) return "（桌面）";
        if (p.contains("packageinstaller") || p.contains("permission")) return "（权限 / 安装弹窗）";
        if (p.startsWith("com.android.")) return "（系统应用）";
        return "";
    }

    /** hitTest 自己带回来的说明（比如「这一刻没抓到控件树」），有就用它,它比我们猜得准 */
    private static String firstWarning(DeviceInspectService.HitTestResult hit) {
        if (hit == null || hit.getWarnings() == null || hit.getWarnings().isEmpty()) return null;
        String w = hit.getWarnings().get(0);
        return w == null || w.isBlank() ? null : w;
    }

    private static void markFragile(Step step, String reason) {
        step.fragile = true;
        step.fragileReason = reason;
    }

    /**
     * 优先按定位点，点不到再退回坐标。
     * <p>
     * 顺带把定位验了一遍：如果按 locator 点得到，说明这条定位当场就是能用的；
     * 录完才发现定位不可用是最浪费时间的一种返工。
     */
    private void clickAt(Session s, int x, int y, String selector) {
        // 录制点击走 adb 坐标。Appium clickElement 会建会话，和 dump 抢 uiautomator，左边一点就转圈
        if ("HARMONYOS".equalsIgnoreCase(s.platform)) {
            PlatformDriver driver = driverOf(s.deviceId);
            if (selector != null && !selector.isBlank()) {
                try {
                    if (driver.clickElement(s.deviceId, selector)) {
                        return;
                    }
                } catch (Exception e) {
                    log.debug("[record] 按定位点击失败，回退坐标: {}", e.getMessage());
                }
            }
            driver.click(s.deviceId, x, y);
            return;
        }
        androidDriver.click(s.deviceId, x, y);
    }

    private String refreshShot(String deviceId) {
        DeviceInspectService.SnapshotResult r = deviceInspectService.snapshotForRecording(deviceId);
        return r == null ? null : r.getScreenshotUrl();
    }

    /** 点完再拍：等界面翻过去，截图马上回来，dump 不挡下一刀 */
    private String refreshShotAfterAction(String deviceId) {
        try {
            Thread.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        DeviceInspectService.SnapshotResult r = deviceInspectService.snapshotAfterRecordingAction(deviceId);
        return r == null ? null : r.getScreenshotUrl();
    }

    /** 把这一刻的截图存进元素预览目录，审核时能看图 */
    private String savePreview(String deviceId) {
        try {
            byte[] png = deviceInspectService.readLatestScreenshot(deviceId);
            if (png == null || png.length == 0) {
                return null;
            }
            Path dir = Paths.get(previewDir).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            String fileName = "rec-" + UUID.randomUUID() + ".png";
            Files.write(dir.resolve(fileName), png);
            return "ui-element-previews/" + fileName;
        } catch (Exception e) {
            log.debug("[record] 存预览图失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 生成一个在这条流程里唯一的英文 key。
     * <p>
     * 不追求好听——它是给机器引用的，人看的是中文名。但必须<b>稳定且唯一</b>，
     * 重了的话两个不同的控件会共用一个 key，执行时点错对象。
     */
    private String uniqueKey(Session s, DeviceInspectService.HitCandidate c, String zh) {
        return uniqueKey(s, firstNonBlank(c.getSuggestedNameEn(), shortId(c.getResourceId()), "el"));
    }

    private String uniqueKey(Session s, String rawBase) {
        String base = rawBase == null ? "el" : rawBase;
        base = base.replaceAll("[^a-zA-Z0-9_]", "_").replaceAll("_+", "_").toLowerCase(Locale.ROOT);
        if (base.isBlank() || "_".equals(base)) {
            base = "el";
        }
        String key = base;
        int n = 1;
        while (s.keyByLocator.containsValue(key)
                || uiElementService.findByKey(s.appId, s.platform, key) != null) {
            key = base + "_" + (++n);
        }
        return key;
    }

    private static String shortId(String resourceId) {
        if (resourceId == null || resourceId.isBlank()) return null;
        int i = resourceId.lastIndexOf('/');
        return i >= 0 && i < resourceId.length() - 1 ? resourceId.substring(i + 1) : resourceId;
    }

    private Session sessionOfDevice(String deviceId) {
        return sessions.values().stream()
                .filter(s -> s.deviceId.equals(deviceId))
                .findFirst().orElse(null);
    }

    private String resolvePlatform(String deviceId) {
        Device d = deviceMapper.findById(deviceId);
        return d != null && d.getPlatform() != null ? d.getPlatform().name() : "ANDROID";
    }

    private PlatformDriver driverOf(String deviceId) {
        Device d = deviceMapper.findById(deviceId);
        if (d == null) {
            throw new IllegalArgumentException("设备不存在: " + deviceId);
        }
        return platformRouter.getDriver(d);
    }

    /**
     * 开着录制去开会的，别让他把设备占一下午。
     *
     * <p><b>必须由定时任务驱动，不能只挂在录制自己的请求路径上。</b>
     * 原先只有 {@code start} 和 {@code step} 会调它，于是「没人再来点录制」时它压根不运行——
     * 那台设备就一直被占着，而排队的任务在 30 分钟后被判 BLOCKED。
     * 症状是<b>设备明明闲着，任务却全部失败</b>，而且看不出是谁占的。
     *
     * <p>1 分钟一轮足够：回收精度不需要比这更细，而 sessions 是内存里的小 Map，
     * 扫一遍的代价可以忽略。
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void sweepExpired() {
        LocalDateTime deadline = LocalDateTime.now().minus(IDLE_TIMEOUT);
        for (Session s : new ArrayList<>(sessions.values())) {
            if (s.lastActiveAt.isBefore(deadline)) {
                sessions.remove(s.id);
                deviceLockService.release(s.deviceId, s.id);
                log.info("[record] 录制会话闲置超时，已回收: {}（已录 {} 步，未保存）", s.id, s.steps.size());
            }
        }
    }

    private static String firstNonBlank(String... vs) {
        if (vs == null) return null;
        for (String v : vs) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    /** 序列化给前端 */
    public static Map<String, Object> toMap(Session s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recordingId", s.id);
        m.put("deviceId", s.deviceId);
        m.put("appId", s.appId);
        m.put("platform", s.platform);
        m.put("name", s.name);
        m.put("startedAt", s.startedAt);
        m.put("screenshotUrl", s.screenshotUrl);
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i < s.steps.size(); i++) {
            steps.add(toMap(s.steps.get(i), i + 1));
        }
        m.put("steps", steps);
        return m;
    }

    public static Map<String, Object> toMap(Step st, int index) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", index);
        m.put("keywordCode", st.keywordCode);
        m.put("elementKey", st.elementKey);
        m.put("elementNameZh", st.elementNameZh);
        m.put("locatorType", st.locatorType);
        m.put("locatorValue", st.locatorValue);
        m.put("matchCount", st.matchCount);
        m.put("previewImage", st.previewImage);
        m.put("params", st.params);
        m.put("existingElementId", st.existingElementId);
        m.put("existingElementStatus", st.existingElementStatus);
        m.put("fragile", st.fragile);
        m.put("fragileReason", st.fragileReason);
        return m;
    }
}
