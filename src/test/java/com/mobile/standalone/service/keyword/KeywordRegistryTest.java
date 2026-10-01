package com.mobile.standalone.service.keyword;

import com.mobile.standalone.service.keyword.handlers.ClickHandler;
import com.mobile.standalone.service.keyword.handlers.ColdStartHandler;
import com.mobile.standalone.service.keyword.handlers.HotStartHandler;
import com.mobile.standalone.service.keyword.handlers.LaunchAppHandler;
import com.mobile.standalone.service.keyword.handlers.ScrollUntilHandler;
import com.mobile.standalone.service.keyword.handlers.SwipeHandler;
import com.mobile.standalone.service.keyword.handlers.WaitHandler;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeywordRegistryTest {

    private static final List<KeywordHandler> REAL_HANDLERS = List.of(
            new LaunchAppHandler(), new ColdStartHandler(), new HotStartHandler(),
            new ClickHandler(), new SwipeHandler(), new ScrollUntilHandler(), new WaitHandler());

    @Test
    void 按主code查得到() {
        KeywordRegistry registry = new KeywordRegistry(REAL_HANDLERS);

        assertNotNull(registry.find("CLICK"));
        assertEquals("CLICK", registry.find("CLICK").code());
    }

    @Test
    void code大小写与空格不敏感() {
        KeywordRegistry registry = new KeywordRegistry(REAL_HANDLERS);

        assertNotNull(registry.find("click"));
        assertNotNull(registry.find("  Click  "));
    }

    @Test
    void 未知code返回null() {
        KeywordRegistry registry = new KeywordRegistry(REAL_HANDLERS);

        assertNull(registry.find("NOT_A_KEYWORD"));
        assertNull(registry.find(null));
    }

    /** 平台合并的核心保障：存量流程里的 *_IOS 步骤必须还能跑 */
    @Test
    void 合并掉的平台关键字仍能按别名解析到同一实现() {
        KeywordRegistry registry = new KeywordRegistry(REAL_HANDLERS);

        assertSame(registry.find("SWIPE"), registry.find("SWIPE_IOS"));
        assertSame(registry.find("SCROLL_UNTIL"), registry.find("SCROLL_UNTIL_IOS"));
        assertSame(registry.find("SCROLL_UNTIL"), registry.find("SCROLL_UNTIL_SHORT"));
        assertSame(registry.find("COLD_START"), registry.find("COLD_START_IOS"));
        assertSame(registry.find("HOT_START"), registry.find("HOT_START_IOS"));
        assertSame(registry.find("LAUNCH_APP"), registry.find("LAUNCH_APP_IOS"));
    }

    @Test
    void EditText输入能按别名解析() {
        KeywordRegistry registry = new KeywordRegistry(List.of(
                new com.mobile.standalone.service.keyword.handlers.InputEditTextHandler()));

        assertSame(registry.find("INPUT_EDITTEXT"), registry.find("EDITTEXT"));
        assertEquals("INPUT_EDITTEXT", registry.find("edittext").code());
    }

    @Test
    void 目录只含主关键字不含别名() {
        KeywordRegistry registry = new KeywordRegistry(REAL_HANDLERS);

        List<String> codes = registry.all().stream().map(KeywordHandler::code).toList();

        assertEquals(REAL_HANDLERS.size(), codes.size());
        assertTrue(codes.contains("SWIPE"));
        assertTrue(codes.stream().noneMatch(c -> c.endsWith("_IOS")));
    }

    @Test
    void 目录按sortOrder排序() {
        KeywordRegistry registry = new KeywordRegistry(REAL_HANDLERS);

        List<Integer> orders = registry.all().stream().map(KeywordHandler::sortOrder).toList();
        for (int i = 1; i < orders.size(); i++) {
            assertTrue(orders.get(i - 1) <= orders.get(i), "关键字未按 sortOrder 排序: " + orders);
        }
    }

    @Test
    void code冲突启动即报错() {
        KeywordHandler duplicate = new StubHandler("CLICK", List.of());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new KeywordRegistry(List.of(new ClickHandler(), duplicate)));
        assertTrue(e.getMessage().contains("CLICK"));
    }

    @Test
    void 别名撞上别人的主code也要报错() {
        KeywordHandler stealsClick = new StubHandler("MY_KEYWORD", List.of("CLICK"));

        assertThrows(IllegalStateException.class,
                () -> new KeywordRegistry(List.of(new ClickHandler(), stealsClick)));
    }

    /** 所有真实关键字的 code 与别名全局唯一，防止后续新增时静默覆盖 */
    @Test
    void 真实关键字的code与别名互不重复() {
        Set<String> seen = new HashSet<>();
        for (KeywordHandler h : REAL_HANDLERS) {
            assertTrue(seen.add(h.code()), "重复 code: " + h.code());
            for (String alias : h.aliases()) {
                assertTrue(seen.add(alias), "别名与已有 code 冲突: " + alias);
            }
        }
    }

    private record StubHandler(String code, List<String> aliases) implements KeywordHandler {
        @Override
        public String nameZh() {
            return code;
        }

        @Override
        public String category() {
            return "ACTION";
        }

        @Override
        public String description() {
            return "";
        }

        @Override
        public int sortOrder() {
            return 500;
        }

        @Override
        public List<ParamDef> params() {
            return List.of();
        }

        @Override
        public void execute(KeywordContext ctx) {
            // 测试桩，不执行任何设备操作
        }
    }
}
