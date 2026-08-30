package com.mobile.standalone.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.mobile.standalone.service.apitest.RequirementEndpointService;
import com.mobile.standalone.service.capture.PacketCaptureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 抓包。安卓走 USB（adb reverse），iOS 要 WiFi 代理——见
 * {@link PacketCaptureService} 上的说明。
 */
@Slf4j
@RestController
@RequestMapping("/packet-capture")
@RequiredArgsConstructor
public class PacketCaptureController {

    private final PacketCaptureService captureService;
    private final RequirementEndpointService requirementEndpointService;
    private final com.mobile.standalone.service.apitest.ApiEndpointService endpointService;
    private final com.mobile.standalone.service.ApiTestCaseService caseService;

    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody Map<String, Object> body) {
        try {
            var s = captureService.start(
                    str(body.get("deviceId")), str(body.get("platform")),
                    str(body.get("requirementNo")), str(body.get("hosts")));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("sessionId", s.getId());
            out.put("platform", s.getPlatform());
            out.put("hosts", s.getHosts());
            out.put("manualProxyTip", s.getManualProxyTip());
            return ResponseEntity.ok(out);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", e.getMessage() == null ? "启动抓包失败" : e.getMessage()));
        }
    }

    /** 抓包过程中实时看——不然你不知道代理到底通没通，白操作一遍 */
    @GetMapping("/{sessionId}/peek")
    public ResponseEntity<?> peek(@PathVariable String sessionId) {
        List<JsonNode> rows = captureService.peek(sessionId);
        var seen = captureService.seenHosts(sessionId);
        return ResponseEntity.ok(Map.of(
                "total", rows.size(),
                "flows", rows,
                // 代理上见到的全部域名。抓到 0 条时，这份清单直接说明是哪种原因：
                // 空＝代理压根没通；非空＝通了但域名不在白名单
                "seenHosts", seen,
                "seenTotal", seen.values().stream().mapToInt(Integer::intValue).sum(),
                // 握手失败的单独给：那是「抓不了」，不是「配错了」，处理方式完全不同
                "failedHosts", captureService.failedHosts(sessionId)));
    }

    /** 上一场抓包见到的域名——结束之后才发现「一条都没抓到」时查这个 */
    @GetMapping("/last-seen-hosts")
    public ResponseEntity<?> lastSeenHosts() {
        return ResponseEntity.ok(captureService.lastSeenHosts());
    }

    /**
     * 某一条的请求/响应体。
     * <p>
     * {@code sessionId} 传 {@code last} 取上一场已结束的抓包——抓的时候人在手机上操作，
     * 看响应基本都是抓完之后的事。
     */
    @GetMapping("/{sessionId}/flow/{seq}/detail")
    public ResponseEntity<?> body(@PathVariable String sessionId, @PathVariable int seq) {
        JsonNode n = captureService.body(sessionId, seq);
        if (n == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    // 说清楚为什么没有，不然人会以为抓包坏了
                    "message", "这条没有留详情：抓包的临时文件已经被清掉了，"
                            + "或者这条是上一场抓包的（开新的一场会清掉上一场）"));
        }
        return ResponseEntity.ok(Map.of("available", true, "detail", n));
    }

    /**
     * 抓完之后再挂到需求单上。
     * <p>
     * 开始抓之前经常还不知道该挂哪个单——抓一遍看到走了什么接口，
     * 才想起来「这是 APP-9059 的」。逼人抓之前填，结果就是没人填。
     */
    @PostMapping("/link")
    public ResponseEntity<?> link(@RequestBody Map<String, Object> body) {
        String reqNo = str(body.get("requirementNo"));
        if (reqNo == null || reqNo.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "要挂到哪个需求单？"));
        }
        String project = str(body.get("project"));
        String captureSession = str(body.get("sessionId"));
        if (captureSession == null || captureSession.isBlank()) {
            captureSession = "last";
        }
        Object raw = body.get("flows");
        List<RequirementEndpointService.Link> links = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    String path = str(m.get("path"));
                    if (path == null || path.isBlank()) {
                        continue;
                    }
                    links.add(new RequirementEndpointService.Link(
                            null, str(m.get("method")), path, str(m.get("host")), null, null,
                            intOrNull(m.get("seq")), captureSession));
                }
            }
        }
        if (links.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "没有可挂的接口"));
        }
        int linked = requirementEndpointService.linkCaptured(reqNo, project, links);
        if (linked == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "没选项目且路径也认不出归属——请选项目，或确认路径前缀（如 /gld/…、/appdatacenter/…）"));
        }
        int fieldsExtracted = countCaptureFields(links);
        return ResponseEntity.ok(Map.of(
                "linked", linked,
                "fieldsExtracted", fieldsExtracted));
    }

    @GetMapping("/current")
    public ResponseEntity<?> current() {
        var s = captureService.current();
        if (s == null) {
            return ResponseEntity.ok(Map.of("running", false));
        }
        return ResponseEntity.ok(Map.of(
                "running", true, "sessionId", s.getId(),
                // 刷新页面后要能显示回正在抓哪台机器，否则设备框空着，看着像没选
                "deviceId", s.getDeviceId() == null ? "" : s.getDeviceId(),
                "platform", s.getPlatform(), "hosts", s.getHosts(),
                "requirementNo", s.getRequirementNo() == null ? "" : s.getRequirementNo(),
                "startedAt", s.getStartedAt()));
    }

    /**
     * 结束抓包。带了需求单号就把抓到的接口挂上去。
     * <p>
     * 挂上去的标成 {@code CAPTURE}，和「分析出来的」「手填的」分开——
     * 抓包证明的是「这个操作<b>确实</b>调了它」，那是三者里最硬的证据。
     */
    @PostMapping("/{sessionId}/stop")
    public ResponseEntity<?> stop(@PathVariable String sessionId,
                                  @RequestBody(required = false) Map<String, Object> body) {
        List<JsonNode> rows = captureService.stop(sessionId);
        String reqNo = body == null ? null : str(body.get("requirementNo"));
        String project = body == null ? null : str(body.get("project"));

        // 同一个接口一次操作里会被调好几次（列表页翻页、重试），去重后才是「涉及了哪些接口」
        Set<String> seen = new LinkedHashSet<>();
        List<RequirementEndpointService.Link> links = new ArrayList<>();
        List<Map<String, Object>> distinct = new ArrayList<>();
        for (JsonNode r : rows) {
            String method = r.path("method").asText("GET");
            String path = r.path("path").asText("");
            if (path.isBlank() || !seen.add(method + " " + path)) {
                continue;
            }
            Map<String, Object> d = new LinkedHashMap<>();
            // 抓包工具那两页要的东西全带上：手机上能看到的，这里也要能看到
            d.put("seq", r.path("seq").asInt());
            d.put("method", method);
            d.put("path", path);
            d.put("host", r.path("host").asText(""));
            d.put("url", r.path("url").asText(""));
            d.put("query", r.path("query").asText(""));
            d.put("httpVersion", r.path("httpVersion").asText(""));
            d.put("remote", r.path("remote").asText(""));
            d.put("status", r.path("status").asInt());
            d.put("reason", r.path("reason").asText(""));
            d.put("durationMs", r.path("durationMs").asInt());
            d.put("at", r.path("at").asLong());
            d.put("traceId", r.path("traceId").asText(null));
            d.put("reqContentType", r.path("reqContentType").asText(""));
            d.put("respContentType", r.path("respContentType").asText(""));
            d.put("reqBytes", r.path("reqBytes").asInt());
            d.put("respBytes", r.path("respBytes").asInt());
            d.put("respKind", r.path("respKind").asText(""));
            d.put("respTruncated", r.path("respTruncated").asBoolean());
            d.put("bodyNote", r.path("bodyNote").asText(""));
            d.put("hasDetail", r.path("hasDetail").asBoolean(true));
            distinct.add(d);
            links.add(new RequirementEndpointService.Link(
                    null, method, path, r.path("host").asText(""), null, null,
                    r.path("seq").asInt(), "last"));
        }

        int linked = 0;
        int fieldsExtracted = 0;
        String linkWarn = null;
        if (reqNo != null && !reqNo.isBlank() && !links.isEmpty()) {
            linked = requirementEndpointService.linkCaptured(reqNo, project, links);
            fieldsExtracted = countCaptureFields(links);
            // 真实参数值只有抓包拿得到：文档只给默认值，而必填参数（steamId 这种）
            // 根本没有默认值。没有它，边界用例连一个能跑的请求都拼不出来
            if (project != null && !project.isBlank()) {
                for (Map<String, Object> d : distinct) {
                    endpointService.applyCaptureRequest(project,
                            String.valueOf(d.get("method")), String.valueOf(d.get("path")),
                            String.valueOf(d.get("query")),
                            reqHeadersOf(sessionId, d.get("seq")));
                }
            }
            if (linked == 0) {
                linkWarn = "没选项目且路径也认不出归属，接口进不了清单。"
                        + "选好项目或在路径可识别时（如 /gld/…）重挂一次。";
            }
        }
        return ResponseEntity.ok(Map.of(
                "total", rows.size(),
                "distinct", distinct.size(),
                "linked", linked,
                "fieldsExtracted", fieldsExtracted,
                "flows", distinct,
                "linkWarn", linkWarn == null ? "" : linkWarn,
                "seenHosts", captureService.lastSeenHosts(),
                "failedHosts", captureService.lastFailedHosts()));
    }

    /**
     * 这一条抓包时带的请求头。
     * <p>
     * 只带一个 {@code Accept} 生成出来的用例是跑不起来的——网关要 appId、要 platform，
     * 缺一个就直接回「Service is busy」，而人看到的是一条红用例，
     * 会以为是接口坏了或者断言写错了。
     */
    private Map<String, String> reqHeadersOf(String sessionId, Object seq) {
        try {
            if (seq == null) {
                return null;
            }
            JsonNode detail = captureService.body(sessionId, Integer.parseInt(String.valueOf(seq)));
            if (detail == null || !detail.has("reqHeaders")) {
                return null;
            }
            Map<String, String> out = new LinkedHashMap<>();
            detail.get("reqHeaders").fields()
                    .forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText("")));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把这次抓包用的 token 存到测试环境。
     * <p>
     * 用例里<b>不写死</b> token：它会过期，写死的话过期那天所有用例集体变红，
     * 而红的原因和接口毫无关系。存到环境上，执行时自动注入，
     * 过期了在这儿点一下重存 —— 一处改、全部生效。
     * <p>
     * 这是个会改环境配置的动作，所以做成显式按钮，不在抓包收尾时偷偷写。
     */
    @PostMapping("/{sessionId}/save-token")
    public ResponseEntity<?> saveToken(@PathVariable String sessionId,
                                       @RequestBody(required = false) Map<String, Object> body) {
        String env = body == null || body.get("environment") == null
                ? "TEST" : String.valueOf(body.get("environment"));
        // token 在任意一条请求里都一样，找到第一条就够。
        // 会话可能已经结束了，body(...) 会回落到上一场的落盘文件
        String token = null;
        for (int seq = 1; seq <= 30 && token == null; seq++) {
            token = tokenFrom(reqHeadersOf(sessionId, seq));
        }
        if (token == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "这次抓包的请求头里没找到 token。"
                            + "如果接口本来就不需要登录态，那不用存"));
        }
        try {
            var e = caseService.getEnv(env);
            if (e == null) {
                return ResponseEntity.badRequest().body(Map.of("message", "环境不存在：" + env));
            }
            e.setDefaultToken(token);
            caseService.saveEnv(e);
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "message", "已存到「" + env + "」环境。用例执行时自动带上，过期了回这儿重存一次"));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", ex.getMessage() == null ? "保存失败" : ex.getMessage()));
        }
    }

    private static final List<String> TOKEN_KEYS =
            List.of("token", "authorization", "x-access-token", "x-auth-token");

    private static String tokenFrom(Map<String, String> headers) {
        if (headers == null) {
            return null;
        }
        for (var e : headers.entrySet()) {
            if (TOKEN_KEYS.contains(e.getKey().toLowerCase(java.util.Locale.ROOT))
                    && e.getValue() != null && !e.getValue().isBlank()) {
                // Bearer xxx 只取后面那截：环境里存裸 token，注入时会自己拼 Bearer
                String v = e.getValue().trim();
                return v.regionMatches(true, 0, "Bearer ", 0, 7) ? v.substring(7).trim() : v;
            }
        }
        return null;
    }

    /** 统计本次挂接口时成功解析出响应字段的条数 */
    private int countCaptureFields(List<RequirementEndpointService.Link> links) {
        int n = 0;
        for (RequirementEndpointService.Link l : links) {
            if (l.captureSeq() == null) {
                continue;
            }
            JsonNode detail = captureService.body(
                    l.captureSessionId() == null ? "last" : l.captureSessionId(), l.captureSeq());
            if (detail != null && !detail.path("respBody").asText("").isBlank()) {
                n++;
            }
        }
        return n;
    }

    private static Integer intOrNull(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number num) {
            return num.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
