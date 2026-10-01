package com.mobile.standalone.controller;

import com.mobile.standalone.service.asr.LocalAsrService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 语音转文字。前端录音上传，这里转成文字返回，人扫一眼再决定拿它去干什么。
 *
 * <p><b>只负责转写，不直接触发任何动作。</b> 识别是会出错的——
 * 把识别结果直接喂给下一步（比如直接生成流程），错了人根本不知道错在哪一环。
 */
@Slf4j
@RestController
@RequestMapping("/asr")
@RequiredArgsConstructor
public class AsrController {

    private final LocalAsrService asrService;

    /** 能不能用。前端据此决定麦克风按钮是亮还是灰，并把原因显示出来 */
    @GetMapping("/readiness")
    public ResponseEntity<?> readiness() {
        LocalAsrService.Readiness r = asrService.readiness();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ready", r.ready());
        out.put("reason", r.reason());
        return ResponseEntity.ok(out);
    }

    @PostMapping("/transcribe")
    public ResponseEntity<?> transcribe(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "没收到音频"));
        }
        try {
            LocalAsrService.Result r = asrService.transcribe(file.getBytes(), file.getOriginalFilename());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("text", r.text());
            out.put("millis", r.millis());
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            // 没装 whisper / 没模型 / 没录到声音——都是人能自己解决的，原样告诉他
            return ResponseEntity.unprocessableEntity().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.warn("[asr] 转写失败: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", e.getMessage() != null ? e.getMessage() : "语音转文字失败"));
        }
    }
}
