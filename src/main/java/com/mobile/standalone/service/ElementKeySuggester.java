package com.mobile.standalone.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * 中文元素名 → 小写下划线英文 key（{@code 活动中心} → {@code activity_center}）。
 * <p>
 * 跟 {@link PageCodeSuggester} 同一套路：走 Cursor，不再调千问。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ElementKeySuggester {

    private final CursorAskService cursorAsk;

    private static final int MAX_LENGTH = 48;

    /**
     * @return 建议 key；AI 不可用或返回不可用内容时返回 null，由调用方决定是否提示
     */
    public String suggest(String nameZh) {
        if (nameZh == null || nameZh.isBlank()) {
            return null;
        }
        String name = nameZh.trim();
        // 本来就是英文的（有人直接填 login_btn）不用问 AI
        if (name.matches("[A-Za-z0-9_\\- ]+")) {
            return normalize(name);
        }
        try {
            String raw = cursorAsk.complete(prompt(name));
            String key = normalize(raw);
            return key == null || key.isEmpty() ? null : key;
        } catch (Exception e) {
            log.warn("[element-key] Cursor 翻译失败「{}」: {}", name, e.getMessage());
            return null;
        }
    }

    static String prompt(String nameZh) {
        return """
                你是移动端测试平台的助手。把下面这个 UI 控件的中文名，翻译成一个简短的英文元素 key。

                要求：
                1. 全小写，单词之间用下划线分隔（snake_case）
                2. 2~5 个单词，不超过 48 个字符
                3. 用测试和开发都认得的通用词（登录=login，按钮=btn，列表=list，详情=detail，
                   首页=home，我的=profile，设置=settings，搜索=search，活动=activity，中心=center，
                   弹窗=dialog，输入框=input，标签=tab，图标=icon）
                4. 只输出 key 本身，不要引号、不要解释、不要 markdown

                例子：
                登录按钮 -> login_btn
                活动中心 -> activity_center
                温馨提示弹窗 -> warm_tip_dialog
                赛程详情 -> match_schedule_detail
                侧边栏入口 -> sidebar_entry

                中文名：%s
                """.formatted(nameZh);
    }

    /**
     * 把模型返回的东西掰成合法 nameEn。
     * 模型偶尔会带引号、句号或补一句解释——编码是流程引用键，必须兜死。
     */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        int nl = s.indexOf('\n');
        if (nl > 0) {
            s = s.substring(0, nl);
        }
        s = s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("_{2,}", "_")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "");
        if (s.length() > MAX_LENGTH) {
            s = s.substring(0, MAX_LENGTH).replaceAll("_+$", "");
        }
        if (!s.isEmpty() && Character.isDigit(s.charAt(0))) {
            s = "el_" + s;
        }
        return s;
    }
}
