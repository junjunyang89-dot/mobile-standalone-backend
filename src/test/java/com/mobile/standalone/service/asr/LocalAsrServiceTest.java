package com.mobile.standalone.service.asr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 转写前后那两处容易被忽略的小地方。
 */
class LocalAsrServiceTest {

    /**
     * 文件名是前端给的，会被拼进命令行。
     * <p>不清洗的话，一个 {@code "a.wav; rm -rf ~"} 或者带路径的名字就能把参数带进去。
     * 这里只放行「点 + 1~6 个字母数字」，其余一律退回 .bin。
     */
    @Test
    void 扩展名只认干净的那几种() {
        assertEquals(".webm", LocalAsrService.ext("voice.webm"));
        assertEquals(".m4a", LocalAsrService.ext("录音.M4A"));
        assertEquals(".wav", LocalAsrService.ext("a.wav"));
    }

    @Test
    void 可疑文件名退回默认扩展名() {
        assertEquals(".bin", LocalAsrService.ext(null));
        assertEquals(".bin", LocalAsrService.ext("noext"));
        assertEquals(".bin", LocalAsrService.ext("a."));
        assertEquals(".bin", LocalAsrService.ext("a.wav; rm -rf ~"));
        assertEquals(".bin", LocalAsrService.ext("../../etc/passwd"));
        assertEquals(".bin", LocalAsrService.ext("a.verylongextension"));
    }

    /**
     * 出错时只回最后几行。
     * <p>whisper 加载模型会刷几十行，整坨塞进错误提示里，
     * 人得翻半天才看到真正那句——而那句永远在最后。
     */
    @Test
    void 报错只取最后几行() {
        // 真实的 whisper 输出是几十行加载信息 + 最后一行错误
        StringBuilder sb = new StringBuilder("whisper_init_from_file_with_params_no_state: loading model\n");
        sb.append("whisper_model_load: n_vocab = 51865\n");
        for (int i = 0; i < 20; i++) {
            sb.append("whisper_model_load: 加载中 ").append(i).append('\n');
        }
        sb.append("error: failed to open 'in.wav' as WAV file");
        String noisy = sb.toString();
        String tail = LocalAsrService.tail(noisy);
        assertTrue(tail.contains("failed to open"), "真正的错误那句必须在：" + tail);
        assertTrue(!tail.contains("n_vocab"), "加载模型的刷屏不该带进来：" + tail);
    }

    @Test
    void 空输出也有说法() {
        assertEquals("(无输出)", LocalAsrService.tail(null));
        assertEquals("(无输出)", LocalAsrService.tail("   "));
    }
}
