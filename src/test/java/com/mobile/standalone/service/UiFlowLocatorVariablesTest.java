package com.mobile.standalone.service;

import com.mobile.standalone.entity.UiElement;
import com.mobile.standalone.entity.UiFlow;
import com.mobile.standalone.mapper.UiAppMapper;
import com.mobile.standalone.mapper.UiElementMapper;
import com.mobile.standalone.mapper.UiFlowMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UiFlowLocatorVariablesTest {

    private final UiFlowMapper flowMapper = mock(UiFlowMapper.class);
    private final UiElementMapper elementMapper = mock(UiElementMapper.class);
    private final UiFlowService service =
            new UiFlowService(flowMapper, elementMapper, mock(UiAppMapper.class), mock(JdbcTemplate.class));

    @Test
    void skipsVarsAlreadyFilledInStepParams() {
        UiFlow flow = flow("IOS", """
                [
                  {
                    "keywordCode": "IF_ELEMENT_EXISTS",
                    "elementKey": "mall_category_tab",
                    "params": {
                      "timeoutSec": 10,
                      "mall_category_tab": "CS商城",
                      "thenSteps": [],
                      "elseSteps": []
                    }
                  },
                  {
                    "keywordCode": "CLICK",
                    "elementKey": "mall_category_tab",
                    "params": {
                      "mall_category_tab": "刀塔商城"
                    }
                  }
                ]
                """);
        stubElement("mall_category_tab", "//XCUIElementTypeStaticText[@name='${mall_category_tab}']");

        assertThat(service.collectLocatorVariables(flow)).isEmpty();
    }

    @Test
    void stillAsksWhenStepDidNotFillTheVar() {
        UiFlow flow = flow("IOS", """
                [
                  {
                    "keywordCode": "CLICK",
                    "elementKey": "mall_category_tab",
                    "params": { "clickMethod": "AUTO" }
                  }
                ]
                """);
        stubElement("mall_category_tab", "//*[@name='${mall_category_tab}']");

        assertThat(service.collectLocatorVariables(flow)).containsExactly("mall_category_tab");
    }

    @Test
    void nestedThenStepWithoutValueStillNeedsVar() {
        UiFlow flow = flow("ANDROID", """
                [
                  {
                    "keywordCode": "IF_ELEMENT_EXISTS",
                    "elementKey": "mall_category_tab",
                    "params": {
                      "mall_category_tab": "CS商城",
                      "thenSteps": [
                        {
                          "keywordCode": "CLICK",
                          "elementKey": "mall_category_tab",
                          "params": {}
                        }
                      ]
                    }
                  }
                ]
                """);
        stubElement("mall_category_tab", "//*[@text='${mall_category_tab}']");

        // 外层已填，内层 CLICK 没填 → 执行时内层仍缺，要列出
        assertThat(service.collectLocatorVariables(flow)).containsExactly("mall_category_tab");
    }

    private UiFlow flow(String platform, String steps) {
        UiFlow f = new UiFlow();
        f.setId("uif-test");
        f.setAppId("DOMESTIC_ESPORTS");
        f.setPlatform(platform);
        f.setSteps(steps);
        return f;
    }

    private void stubElement(String nameEn, String locator) {
        UiElement el = new UiElement();
        el.setAppId("DOMESTIC_ESPORTS");
        el.setPlatform("IOS");
        el.setNameEn(nameEn);
        el.setLocatorValue(locator);
        el.setStatus("ACTIVE");
        when(elementMapper.findActiveByKey(eq("DOMESTIC_ESPORTS"), eq("IOS"), eq(nameEn)))
                .thenReturn(el);
        when(elementMapper.findActiveByKey(eq("DOMESTIC_ESPORTS"), eq("ANDROID"), eq(nameEn)))
                .thenReturn(el);
    }
}
