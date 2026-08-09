package com.mobile.standalone.service;

import com.mobile.standalone.service.driver.AppiumDriverAdapter;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * iOS WDA / 签名修复工具箱。
 * <p>
 * 证书吊销、同名歧义、装完立刻消失、冷却期内反复失败——这类问题会反复出现。
 * 这里把排查拆成有序步骤，能自动修的给出按钮，必须人点的给出手机路径提示。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IosWdaToolkitService {

    private static final Pattern IDENTITY_LINE = Pattern.compile(
            "^\\s*\\d+\\)\\s+([A-F0-9]{40})\\s+\"([^\"]+)\"(.*)$",
            Pattern.CASE_INSENSITIVE);

    private final DeviceManager deviceManager;
    private final AppiumDriverAdapter appiumDriverAdapter;
    private final IosDeviceReadiness iosDeviceReadiness;

    @Value("${ios.wda.bundle-id:com.travYang.wda.Runner}")
    private String wdaBundleId;

    @Value("${ios.wda.xcode-org-id:37ZX3X6QJY}")
    private String wdaTeamId;

    @Value("${ios.wda.xcode-signing-id:Apple Development}")
    private String wdaSigningId;

    @Data
    public static class Step {
        private String id;
        private int order;
        /** PASS / FAIL / WARN / UNKNOWN / MANUAL */
        private String status;
        private String title;
        private String detail;
        /** 给操作人看的下一步提示（可含路径） */
        private String tip;
        /** 平台可一键执行的动作 id；空表示只能人工 */
        private String actionId;
        private String actionLabel;
        /** 危险操作需要 confirm=true */
        private boolean confirmRequired;
    }

    @Data
    public static class ActionCatalogItem {
        private String id;
        private String label;
        private String description;
        private boolean confirmRequired;
        private boolean longRunning;
    }

    @Data
    public static class Report {
        private String udid;
        private boolean healthy;
        private String summary;
        private String nextTip;
        private Map<String, Object> config = new LinkedHashMap<>();
        private Map<String, Object> runtime = new LinkedHashMap<>();
        private List<Step> steps = new ArrayList<>();
        private List<ActionCatalogItem> actions = new ArrayList<>();
    }

    @Data
    public static class ActionResult {
        private boolean ok;
        private String action;
        private String message;
        private Report reportAfter;
    }

    public Report diagnose(String udid) {
        Report r = new Report();
        r.setUdid(udid);
        r.getConfig().put("bundleId", wdaBundleId);
        r.getConfig().put("teamId", wdaTeamId);
        r.getConfig().put("signingId", wdaSigningId);
        r.getConfig().put("wdaProject", defaultWdaProject().toString());

        int port = appiumDriverAdapter.resolveWdaLocalPort(udid);
        r.getRuntime().put("localPort", port);
        r.getRuntime().putAll(deviceManager.getIosWdaRuntimeState(udid));
        r.getRuntime().put("statusOk", deviceManager.isIosWdaStatusOk(port));

        int order = 1;
        r.getSteps().add(stepDevice(udid, order++));
        r.getSteps().add(stepSigning(order++));
        r.getSteps().add(stepProject(order++));
        r.getSteps().add(stepInstalled(udid, order++));
        r.getSteps().add(stepCooldown(udid, order++));
        r.getSteps().add(stepRunning(udid, port, order++));
        r.getSteps().add(stepTrust(order++));

        r.setActions(catalog());
        boolean hasFailOrWarn = r.getSteps().stream()
                .anyMatch(s -> "FAIL".equals(s.getStatus()) || "WARN".equals(s.getStatus()));
        boolean statusOk = Boolean.TRUE.equals(r.getRuntime().get("statusOk"));
        // MANUAL（信任开发者）不挡 healthy；FAIL/WARN 或 /status 不通则不算健康
        r.setHealthy(!hasFailOrWarn && statusOk);
        List<Step> bad = r.getSteps().stream()
                .filter(s -> "FAIL".equals(s.getStatus()) || "WARN".equals(s.getStatus()))
                .toList();
        r.setSummary(buildSummary(r, bad, statusOk));
        r.setNextTip(buildNextTip(r.getSteps(), statusOk));
        return r;
    }

    public ActionResult runAction(String udid, String action, boolean confirm) {
        ActionResult ar = new ActionResult();
        ar.setAction(action);
        if (udid == null || udid.isBlank()) {
            ar.setOk(false);
            ar.setMessage("缺少设备 UDID");
            return ar;
        }
        if (action == null || action.isBlank()) {
            ar.setOk(false);
            ar.setMessage("缺少 action");
            return ar;
        }
        String a = action.trim().toLowerCase(Locale.ROOT);
        try {
            switch (a) {
                case "clear_cooldown" -> {
                    deviceManager.clearIosWdaCooldown(udid);
                    ar.setOk(true);
                    ar.setMessage("已清除该设备的 WDA 失败冷却与失败计数，可立即再试拉起");
                }
                case "ensure_running" -> {
                    deviceManager.clearIosWdaCooldown(udid);
                    int port = appiumDriverAdapter.resolveWdaLocalPort(udid);
                    boolean ok = deviceManager.ensureIosWdaRunning(udid, port);
                    ar.setOk(ok);
                    ar.setMessage(ok
                            ? "WDA 已就绪：http://127.0.0.1:" + port + "/status"
                            : "拉起失败：" + nullTo(deviceManager.getLastIosWdaError(udid), "见执行机 /tmp/wda-*.log"));
                }
                case "rebuild_install" -> {
                    deviceManager.clearIosWdaCooldown(udid);
                    int port = appiumDriverAdapter.resolveWdaLocalPort(udid);
                    String msg = deviceManager.rebuildAndInstallIosWda(udid, port);
                    boolean ok = msg == null;
                    ar.setOk(ok);
                    ar.setMessage(ok
                            ? "已重新编译并安装 WDA，/status 可用。若手机弹出未受信任开发者，请按「信任开发者」步骤操作"
                            : msg);
                }
                case "remove_revoked_certs" -> {
                    if (!confirm) {
                        ar.setOk(false);
                        ar.setMessage("删除吊销证书会改本机钥匙串，请在请求里传 confirm=true 后再执行");
                        return finish(ar, udid);
                    }
                    List<String> removed = removeRevokedIdentities();
                    ar.setOk(true);
                    ar.setMessage(removed.isEmpty()
                            ? "钥匙串里没有标记为 REVOKED 的开发者身份，无需删除"
                            : "已删除 " + removed.size() + " 个吊销身份：" + String.join(", ", removed)
                            + "。同名歧义应已消除，可继续「重新编译安装」");
                }
                default -> {
                    ar.setOk(false);
                    ar.setMessage("未知动作：" + action + "。可用："
                            + "clear_cooldown / ensure_running / rebuild_install / remove_revoked_certs");
                }
            }
        } catch (Exception e) {
            log.warn("[ios-wda-toolkit] action={} failed: {}", a, e.getMessage());
            ar.setOk(false);
            ar.setMessage("执行失败：" + e.getMessage());
        }
        return finish(ar, udid);
    }

    private ActionResult finish(ActionResult ar, String udid) {
        try {
            ar.setReportAfter(diagnose(udid));
        } catch (Exception e) {
            log.debug("diagnose after action: {}", e.getMessage());
        }
        return ar;
    }

    private List<ActionCatalogItem> catalog() {
        List<ActionCatalogItem> list = new ArrayList<>();
        list.add(item("remove_revoked_certs", "清理吊销证书",
                "删除钥匙串中 CSSMERR_TP_CERT_REVOKED 的 Apple Development 身份，解决同名歧义导致的签名失败",
                true, false));
        list.add(item("clear_cooldown", "清除失败冷却",
                "签名硬失败后平台会冷却约 10 分钟不再反复 xcodebuild；修完证书后点这个再拉起",
                false, false));
        list.add(item("rebuild_install", "重新编译并安装 WDA",
                "xcodebuild build-for-testing + test-without-building，把新签名的 Runner 装到这台手机（约 1～3 分钟）",
                false, true));
        list.add(item("ensure_running", "仅拉起 WDA",
                "不重装，只跑 test-without-building + iproxy，适合手机上已有 Runner、证书已信任",
                false, true));
        return list;
    }

    private static ActionCatalogItem item(String id, String label, String desc,
                                          boolean confirm, boolean longRunning) {
        ActionCatalogItem i = new ActionCatalogItem();
        i.setId(id);
        i.setLabel(label);
        i.setDescription(desc);
        i.setConfirmRequired(confirm);
        i.setLongRunning(longRunning);
        return i;
    }

    private Step stepDevice(String udid, int order) {
        Step s = base("device_paired", order, "设备已连接并配对");
        IosDeviceReadiness.Report ready = iosDeviceReadiness.check(udid);
        IosDeviceReadiness.Check pairing = ready.getChecks().stream()
                .filter(c -> c.getName() != null && c.getName().contains("配对"))
                .findFirst().orElse(null);
        if (pairing == null) {
            s.setStatus("UNKNOWN");
            s.setDetail("未能完成配对检查");
            s.setTip("用数据线连接，手机点「信任此电脑」，执行机跑 idevicepair pair");
            return s;
        }
        s.setStatus(pairing.getStatus());
        s.setDetail(pairing.getDetail());
        s.setTip(pairing.getHowToFix());
        return s;
    }

    private Step stepSigning(int order) {
        Step s = base("signing_identities", order, "本机开发者证书可用");
        List<SigningIdentity> ids = listSigningIdentities();
        if (ids.isEmpty()) {
            s.setStatus("FAIL");
            s.setDetail("钥匙串里没有可用的 codesigning 身份");
            s.setTip("打开 Xcode → Settings → Accounts，登录 Apple ID，Download Manual Profiles；"
                    + "或在钥匙串确认存在「Apple Development: …」证书与私钥");
            return s;
        }
        long revoked = ids.stream().filter(SigningIdentity::revoked).count();
        long valid = ids.stream().filter(i -> !i.revoked()).count();
        Map<String, Long> byName = new LinkedHashMap<>();
        for (SigningIdentity i : ids) {
            byName.merge(i.name(), 1L, Long::sum);
        }
        boolean ambiguous = byName.values().stream().anyMatch(n -> n > 1);
        StringBuilder detail = new StringBuilder();
        for (SigningIdentity i : ids) {
            detail.append(i.hash().substring(0, 8)).append("… ")
                    .append(i.name())
                    .append(i.revoked() ? " [REVOKED]" : " [OK]")
                    .append('\n');
        }
        s.setDetail(detail.toString().trim());
        if (valid == 0) {
            s.setStatus("FAIL");
            s.setTip("全部证书已吊销。到 Apple Developer / Xcode Accounts 新建 Development 证书，"
                    + "装进钥匙串后再点「清理吊销证书」去掉旧的");
            s.setActionId("remove_revoked_certs");
            s.setActionLabel("清理吊销证书");
            s.setConfirmRequired(true);
            return s;
        }
        if (revoked > 0 || ambiguous) {
            s.setStatus("WARN");
            s.setTip("存在吊销或同名证书时，xcodebuild 选「Apple Development」会报 ambiguous，"
                    + "编译卡在 Embed app icon。先点「清理吊销证书」，再「重新编译并安装 WDA」");
            s.setActionId("remove_revoked_certs");
            s.setActionLabel("清理吊销证书");
            s.setConfirmRequired(true);
            return s;
        }
        s.setStatus("PASS");
        s.setTip("签名身份正常。Team=" + wdaTeamId + " / Bundle=" + wdaBundleId
                + "（见 application.yml ios.wda）");
        return s;
    }

    private Step stepProject(int order) {
        Step s = base("wda_project", order, "本机存在 WebDriverAgent 工程");
        Path project = defaultWdaProject();
        if (Files.isDirectory(project)) {
            s.setStatus("PASS");
            s.setDetail(project.toString());
            s.setTip("工程来自 Appium XCUITest 驱动依赖；勿随意删 ~/.appium");
        } else {
            s.setStatus("FAIL");
            s.setDetail("未找到：" + project);
            s.setTip("在执行机安装 Appium 与 xcuitest 驱动："
                    + "npm i -g appium && appium driver install xcuitest");
        }
        return s;
    }

    private Step stepInstalled(String udid, int order) {
        Step s = base("wda_installed", order, "手机上已安装 WebDriverAgentRunner");
        String out = exec(30, "ideviceinstaller", "-u", udid, "list");
        if (out == null || out.isBlank()) {
            s.setStatus("UNKNOWN");
            s.setDetail("列不出已装应用");
            s.setTip("确认 ideviceinstaller 可用且设备已配对");
            return s;
        }
        boolean has = out.contains("WebDriverAgent")
                || out.toLowerCase(Locale.ROOT).contains("wda")
                || (wdaBundleId != null && out.contains(wdaBundleId));
        if (has) {
            s.setStatus("PASS");
            s.setDetail("已检测到 WDA / Runner");
            s.setTip("若装完立刻消失，多半是免费账号 7 天描述文件过期或设备未进 team——"
                    + "用「重新编译并安装」刷新签名");
            s.setActionId("rebuild_install");
            s.setActionLabel("重新编译并安装 WDA");
        } else {
            s.setStatus("FAIL");
            s.setDetail("设备上没有 WDA");
            s.setTip("点「重新编译并安装 WDA」。装完后到手机：设置 → 通用 → VPN与设备管理 → 信任开发者");
            s.setActionId("rebuild_install");
            s.setActionLabel("重新编译并安装 WDA");
        }
        return s;
    }

    private Step stepCooldown(String udid, int order) {
        Step s = base("wda_cooldown", order, "平台未处于 WDA 失败冷却");
        Map<String, Object> state = deviceManager.getIosWdaRuntimeState(udid);
        long left = ((Number) state.getOrDefault("cooldownLeftSec", 0)).longValue();
        String err = String.valueOf(state.getOrDefault("lastError", ""));
        if (left > 0) {
            s.setStatus("WARN");
            s.setDetail("冷却剩余 " + left + " 秒；上次原因：" + (err.isBlank() ? "—" : err));
            s.setTip("证书/信任修好后点「清除失败冷却」，否则业务侧会一直跳过拉起 WDA");
            s.setActionId("clear_cooldown");
            s.setActionLabel("清除失败冷却");
        } else {
            s.setStatus("PASS");
            s.setDetail(err.isBlank() ? "无冷却" : "无冷却；最近错误缓存：" + err);
            s.setTip("硬失败（0xe8008018 等）会冷却约 10 分钟，避免一直重装刷屏");
        }
        return s;
    }

    private Step stepRunning(String udid, int port, int order) {
        Step s = base("wda_running", order, "本机可访问 WDA /status");
        boolean ok = deviceManager.isIosWdaStatusOk(port);
        if (ok) {
            s.setStatus("PASS");
            s.setDetail("http://127.0.0.1:" + port + "/status 正常");
            s.setTip("已可跑截图 / 真机点选 / Appium 挂接");
            return s;
        }
        s.setStatus("FAIL");
        String err = deviceManager.getLastIosWdaError(udid);
        s.setDetail("端口 " + port + " 无响应"
                + (err == null || err.isBlank() ? "" : "；" + err));
        s.setTip("手机已装 Runner 时先「仅拉起 WDA」；未装或签名坏了用「重新编译并安装」。"
                + "若日志提示未信任，先做下一步人工信任");
        s.setActionId("ensure_running");
        s.setActionLabel("仅拉起 WDA");
        return s;
    }

    private Step stepTrust(int order) {
        Step s = base("developer_trust", order, "手机已信任开发者证书（需人工确认）");
        s.setStatus("MANUAL");
        s.setDetail("Apple 不允许远程查询「是否已信任」；装完 WDA 后若闪退或 0xe8008018，几乎都是这一步");
        s.setTip("iPhone：设置 → 通用 → VPN与设备管理 → 开发者 App → 信任「Apple Development: …」。"
                + "信任后回平台点「仅拉起 WDA」或「清除冷却」再试");
        return s;
    }

    private static Step base(String id, int order, String title) {
        Step s = new Step();
        s.setId(id);
        s.setOrder(order);
        s.setTitle(title);
        return s;
    }

    private static String buildSummary(Report r, List<Step> bad, boolean statusOk) {
        if (statusOk && bad.stream().noneMatch(s -> "FAIL".equals(s.getStatus()) || "WARN".equals(s.getStatus()))) {
            return "WDA 工具箱检查通过，本机 /status 可用";
        }
        Step first = r.getSteps().stream()
                .filter(s -> "FAIL".equals(s.getStatus()) || "WARN".equals(s.getStatus()))
                .findFirst()
                .orElse(null);
        if (first != null) {
            return "先处理：" + first.getTitle()
                    + (first.getActionLabel() != null ? " → 建议点「" + first.getActionLabel() + "」" : "");
        }
        if (!statusOk) {
            return "WDA /status 不可用，建议「仅拉起」或「重新编译并安装」";
        }
        return "请确认手机已信任开发者证书";
    }

    private static String buildNextTip(List<Step> steps, boolean statusOk) {
        for (Step s : steps) {
            if ("FAIL".equals(s.getStatus()) || "WARN".equals(s.getStatus())) {
                return s.getTip();
            }
        }
        if (!statusOk) {
            return "点「仅拉起 WDA」；仍失败再「重新编译并安装」";
        }
        return "日常跑任务即可；证书快过期时再回来用本工具箱重签";
    }

    private Path defaultWdaProject() {
        return Path.of(System.getProperty("user.home"),
                ".appium/node_modules/appium-xcuitest-driver/node_modules/appium-webdriveragent/WebDriverAgent.xcodeproj");
    }

    record SigningIdentity(String hash, String name, boolean revoked) {
    }

    static List<SigningIdentity> parseIdentities(String out) {
        List<SigningIdentity> list = new ArrayList<>();
        if (out == null || out.isBlank()) {
            return list;
        }
        for (String line : out.split("\r?\n")) {
            Matcher m = IDENTITY_LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String hash = m.group(1).toUpperCase(Locale.ROOT);
            String name = m.group(2);
            String rest = m.group(3) == null ? "" : m.group(3);
            boolean revoked = rest.toUpperCase(Locale.ROOT).contains("REVOKED");
            list.add(new SigningIdentity(hash, name, revoked));
        }
        return list;
    }

    private List<SigningIdentity> listSigningIdentities() {
        return parseIdentities(exec(15, "security", "find-identity", "-v", "-p", "codesigning"));
    }

    private List<String> removeRevokedIdentities() {
        List<String> removed = new ArrayList<>();
        for (SigningIdentity id : listSigningIdentities()) {
            if (!id.revoked()) {
                continue;
            }
            String out = exec(30, "security", "delete-identity", "-Z", id.hash());
            // delete-identity 成功时 stdout 往往为空；失败信息在输出里
            if (out != null && out.toLowerCase(Locale.ROOT).contains("could not")) {
                log.warn("delete-identity failed {}: {}", id.hash(), out);
                continue;
            }
            removed.add(id.hash().substring(0, 8) + "…(" + id.name() + ")");
            log.info("[ios-wda-toolkit] removed revoked identity {}", id.hash());
        }
        return removed;
    }

    private static String nullTo(String v, String d) {
        return v == null || v.isBlank() ? d : v;
    }

    private String exec(int timeoutSec, String... cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null && sb.length() < 200_000) {
                    sb.append(line).append('\n');
                }
            }
            p.waitFor(timeoutSec, TimeUnit.SECONDS);
            return sb.toString();
        } catch (Exception e) {
            log.debug("[ios-wda-toolkit] exec {} : {}", cmd[0], e.getMessage());
            return null;
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }
}
