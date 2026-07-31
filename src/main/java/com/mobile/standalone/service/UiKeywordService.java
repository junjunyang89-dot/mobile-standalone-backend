package com.mobile.standalone.service;

import com.mobile.standalone.entity.UiKeyword;
import com.mobile.standalone.mapper.UiKeywordMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UiKeywordService {

    private final UiKeywordMapper uiKeywordMapper;

    @PostConstruct
    public void seedBuiltins() {
        try {
            seed("LAUNCH_APP", "启动应用程序", "DEVICE", false, 5,
                    "[{\"name\":\"packageName\",\"label\":\"包名/BundleId\",\"type\":\"string\",\"required\":false},"
                            + "{\"name\":\"activity\",\"label\":\"Activity(可选)\",\"type\":\"string\",\"required\":false}]",
                    "启动应用：优先用步骤填写的包名；为空则用流程所选应用在应用字典中的包名/BundleId");
            seed("CLICK", "点击", "ACTION", true, 10,
                    "[{\"name\":\"elementKey\",\"label\":\"元素英文名\",\"type\":\"element\",\"required\":true}]",
                    "点击指定元素");
            seed("INPUT", "输入", "ACTION", true, 20,
                    "[{\"name\":\"elementKey\",\"label\":\"元素英文名\",\"type\":\"element\",\"required\":true},"
                            + "{\"name\":\"text\",\"label\":\"输入内容\",\"type\":\"string\",\"required\":true}]",
                    "向元素输入文本");
            seed("CLEAR", "清空", "ACTION", true, 30,
                    "[{\"name\":\"elementKey\",\"label\":\"元素英文名\",\"type\":\"element\",\"required\":true}]",
                    "清空输入框");
            seed("WAIT", "固定延迟", "WAIT", false, 40,
                    "[{\"name\":\"durationMs\",\"label\":\"毫秒\",\"type\":\"number\",\"required\":true,\"default\":1000}]",
                    "固定等待一段时间（可配置，不写死在代码里）");
            seed("WAIT_ELEMENT", "等待元素", "WAIT", true, 50,
                    "[{\"name\":\"elementKey\",\"label\":\"元素英文名\",\"type\":\"element\",\"required\":true},"
                            + "{\"name\":\"waitType\",\"label\":\"Presence/Visible/Clickable\",\"type\":\"string\",\"default\":\"Visible\"},"
                            + "{\"name\":\"timeoutSec\",\"label\":\"超时秒\",\"type\":\"number\",\"default\":10}]",
                    "智能等待元素出现/可见/可点");
            seed("IF_ELEMENT_EXISTS", "元素存在判断", "FLOW", true, 55,
                    "[{\"name\":\"elementKey\",\"label\":\"判断的元素\",\"type\":\"element\",\"required\":true},"
                            + "{\"name\":\"timeoutSec\",\"label\":\"探测超时秒\",\"type\":\"number\",\"default\":3},"
                            + "{\"name\":\"thenSteps\",\"label\":\"存在时步骤\",\"type\":\"steps\"},"
                            + "{\"name\":\"elseSteps\",\"label\":\"不存在时步骤\",\"type\":\"steps\"},"
                            + "{\"name\":\"elseMessage\",\"label\":\"不存在时返回提示\",\"type\":\"string\"}]",
                    "判断元素是否存在：存在执行 thenSteps；不存在优先返回 elseMessage（若填写），否则执行 elseSteps");
            seed("RETURN_MESSAGE", "返回提示", "FLOW", false, 56,
                    "[{\"name\":\"message\",\"label\":\"提示文案\",\"type\":\"string\",\"required\":true}]",
                    "结束当前分支/流程并返回指定提示文案（常用于元素不存在时）");
            seed("SWIPE", "滑动", "ACTION", false, 60,
                    "[{\"name\":\"direction\",\"label\":\"up/down/left/right\",\"type\":\"string\",\"required\":true},"
                            + "{\"name\":\"durationMs\",\"label\":\"毫秒\",\"type\":\"number\",\"default\":500}]",
                    "屏幕滑动");
            seed("BACK", "返回", "DEVICE", false, 70, "[]", "系统返回键");
            seed("SCREENSHOT", "截图", "DEVICE", false, 80,
                    "[{\"name\":\"label\",\"label\":\"步骤备注\",\"type\":\"string\"}]",
                    "保存当前截图");
            seed("ASSERT_TEXT", "断言文案", "ASSERT", true, 90,
                    "[{\"name\":\"elementKey\",\"label\":\"元素英文名\",\"type\":\"element\",\"required\":true},"
                            + "{\"name\":\"expected\",\"label\":\"期望包含\",\"type\":\"string\",\"required\":true}]",
                    "断言元素文案包含期望值");
            seed("CALL_COMPONENT", "调用组件", "FLOW", false, 100,
                    "[{\"name\":\"flowId\",\"label\":\"组件流程ID\",\"type\":\"string\",\"required\":true}]",
                    "复用已保存的流程组件（如登录）");
        } catch (Exception e) {
            log.warn("初始化关键字库失败（请先执行 schema_ui_assets.sql）: {}", e.getMessage());
        }
    }

    private void seed(String code, String nameZh, String category, boolean needsElement, int order,
                      String paramSchema, String description) {
        if (uiKeywordMapper.findByCode(code) != null) return;
        UiKeyword k = new UiKeyword();
        k.setId("uik-" + code.toLowerCase());
        k.setCode(code);
        k.setNameZh(nameZh);
        k.setDescription(description);
        k.setCategory(category);
        k.setNeedsElement(needsElement);
        k.setParamSchema(paramSchema);
        k.setBuiltin(true);
        k.setEnabled(true);
        k.setSortOrder(order);
        LocalDateTime now = LocalDateTime.now();
        k.setCreatedAt(now);
        k.setUpdatedAt(now);
        uiKeywordMapper.insert(k);
        log.info("已种子关键字: {} {}", code, nameZh);
    }

    public List<UiKeyword> list(String category, Boolean enabledOnly) {
        return uiKeywordMapper.findAll(blankToNull(category), enabledOnly);
    }

    public UiKeyword get(String id) {
        return uiKeywordMapper.findById(id);
    }

    public UiKeyword getByCode(String code) {
        return uiKeywordMapper.findByCode(code);
    }

    @Transactional
    public UiKeyword create(UiKeyword k) {
        if (k.getCode() == null || k.getCode().isBlank()) {
            throw new IllegalArgumentException("code 不能为空");
        }
        if (k.getNameZh() == null || k.getNameZh().isBlank()) {
            throw new IllegalArgumentException("中文名不能为空");
        }
        String code = k.getCode().trim().toUpperCase();
        if (uiKeywordMapper.findByCode(code) != null) {
            throw new IllegalArgumentException("关键字 code 已存在: " + code);
        }
        k.setId("uik-" + UUID.randomUUID());
        k.setCode(code);
        k.setNameZh(k.getNameZh().trim());
        if (k.getCategory() == null || k.getCategory().isBlank()) k.setCategory("ACTION");
        if (k.getNeedsElement() == null) k.setNeedsElement(false);
        if (k.getBuiltin() == null) k.setBuiltin(false);
        if (k.getEnabled() == null) k.setEnabled(true);
        if (k.getSortOrder() == null) k.setSortOrder(999);
        LocalDateTime now = LocalDateTime.now();
        k.setCreatedAt(now);
        k.setUpdatedAt(now);
        uiKeywordMapper.insert(k);
        return k;
    }

    @Transactional
    public UiKeyword update(String id, UiKeyword patch) {
        UiKeyword existing = uiKeywordMapper.findById(id);
        if (existing == null) return null;
        if (patch.getNameZh() != null) existing.setNameZh(patch.getNameZh().trim());
        if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
        if (patch.getCategory() != null) existing.setCategory(patch.getCategory());
        if (patch.getNeedsElement() != null) existing.setNeedsElement(patch.getNeedsElement());
        if (patch.getParamSchema() != null) existing.setParamSchema(patch.getParamSchema());
        if (patch.getEnabled() != null) existing.setEnabled(patch.getEnabled());
        if (patch.getSortOrder() != null) existing.setSortOrder(patch.getSortOrder());
        existing.setUpdatedAt(LocalDateTime.now());
        uiKeywordMapper.update(existing);
        return existing;
    }

    @Transactional
    public boolean delete(String id) {
        UiKeyword existing = uiKeywordMapper.findById(id);
        if (existing == null) return false;
        if (Boolean.TRUE.equals(existing.getBuiltin())) {
            throw new IllegalArgumentException("内置关键字不可删除，可禁用");
        }
        return uiKeywordMapper.deleteById(id) > 0;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
