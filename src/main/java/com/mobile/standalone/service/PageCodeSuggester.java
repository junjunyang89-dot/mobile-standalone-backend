package com.mobile.standalone.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * 中文页面名 → 大写下划线页面编码（{@code 输入验证码} → {@code LOGIN_SMS_INPUT}）。
 * <p>
 * 走 Cursor（与主菜单 AI 助手同一通道），不再调千问。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PageCodeSuggester {

    private final CursorAskService cursorAsk;

    private static final int MAX_LENGTH = 40;

    /**
     * @return 建议编码；Cursor 不可用或返回不可用内容时返回 null，由调用方保留原来的随机码兜底
     */
    public String suggest(String nameZh) {
        if (nameZh == null || nameZh.isBlank()) {
            return null;
        }
        String name = nameZh.trim();
        // 本来就是英文的（有人直接填 LOGIN_SMS）不用问 AI，白花一次调用
        if (name.matches("[A-Za-z0-9_\\- ]+")) {
            return normalize(name);
        }
        try {
            String raw = cursorAsk.complete(prompt(name));
            String code = normalize(raw);
            return code == null || code.isEmpty() ? null : code;
        } catch (Exception e) {
            // 翻译不出来不该挡住建页面——退回随机码，人照样能用
            log.warn("[page-code] Cursor 生成编码失败「{}」: {}", name, e.getMessage());
            return null;
        }
    }

    static String prompt(String nameZh) {
        return """
                你是移动端测试平台的助手。把下面这个 APP 页面/弹窗的中文名，翻译成一个简短的英文页面编码。

                要求：
                1. 全大写，单词之间用下划线分隔
                2. 2~4 个单词，不超过 40 个字符
                3. 用测试和开发都认得的通用词（登录=LOGIN，弹窗=DIALOG/POPUP，列表=LIST，详情=DETAIL，
                   首页=HOME，我的=PROFILE，设置=SETTINGS，搜索=SEARCH，赛程=SCHEDULE，赛事=MATCH，
                   电竞=ESPORTS）
                4. 只输出编码本身，不要引号、不要解释、不要 markdown

                例子：
                输入验证码 -> LOGIN_SMS_CODE
                温馨提示弹窗 -> PRIVACY_WARM_TIP
                赛程详情 -> MATCH_SCHEDULE_DETAIL
                侧边栏 -> SIDEBAR
                电竞APP-首页 -> ESPORTS_HOME

                中文名：%s
                """.formatted(nameZh);
    }

    /**
     * 把模型返回的东西掰成合法编码。
     * <p>
     * 模型偶尔会带上引号、反引号、句号，或者多写一句解释。
     * 与其相信它每次都听话，不如在这里兜住——不兜的话，那次不听话就会存进一个带引号的编码，
     * 而编码是流程里引用元素的键，改起来要连着流程一起改。
     */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        // 只取第一行：模型有时会在下面补一句「这个编码表示……」
        int nl = s.indexOf('\n');
        if (nl > 0) {
            s = s.substring(0, nl);
        }
        s = s.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9_]+", "_")   // 空格/连字符/标点一律变下划线
                .replaceAll("_{2,}", "_")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "");
        if (s.length() > MAX_LENGTH) {
            s = s.substring(0, MAX_LENGTH).replaceAll("_+$", "");
        }
        // 不能以数字开头：编码会被当成标识符用
        if (!s.isEmpty() && Character.isDigit(s.charAt(0))) {
            s = "P_" + s;
        }
        return s;
    }
}
