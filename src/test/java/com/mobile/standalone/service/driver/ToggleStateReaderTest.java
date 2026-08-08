package com.mobile.standalone.service.driver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToggleStateReaderTest {

  @Test
  void 空串不算未勾选() {
    assertNull(ToggleStateReader.parseToggleValue(""));
    assertNull(ToggleStateReader.parseToggleValue("   "));
  }

  @Test
  void 常见布尔值可解析() {
    assertTrue(ToggleStateReader.parseToggleValue("true"));
    assertTrue(ToggleStateReader.parseToggleValue("1"));
    assertFalse(ToggleStateReader.parseToggleValue("false"));
    assertFalse(ToggleStateReader.parseToggleValue("0"));
  }

  @Test
  void 合并勾选态时pageSource优先() {
    assertTrue(ToggleStateReader.mergeElementAndPageSource(false, true));
    assertFalse(ToggleStateReader.mergeElementAndPageSource(false, false));
    assertFalse(ToggleStateReader.mergeElementAndPageSource(false, null));
    assertTrue(ToggleStateReader.mergeElementAndPageSource(null, true));
    assertTrue(ToggleStateReader.mergeElementAndPageSource(true, false));
  }

  @Test
  void contentDesc可推断勾选态() {
    assertTrue(ToggleStateReader.inferCheckedFromDescription("协议已勾选"));
    assertFalse(ToggleStateReader.inferCheckedFromDescription("未勾选"));
  }

  @Test
  void 协议固定文案不能当成已勾选() {
    assertNull(ToggleStateReader.inferCheckedFromDescription("我已阅读并同意《完美世界电竞用户协议》"));
    assertNull(ToggleStateReader.inferCheckedFromDescription("请阅读个人信息保护政策"));
  }

  @Test
  void 协议框只读checked属性忽略文案() {
    String xml = """
        <hierarchy>
          <android.widget.ImageView resource-id="com.pwrd.steam.esports:id/policy_checkbox"
            content-desc="我已阅读并同意《用户协议》" checked="false" checkable="true" />
        </hierarchy>
        """;
    assertFalse(ToggleStateReader.readCheckedAttrFromPageSource(
            xml, "id:com.pwrd.steam.esports:id/policy_checkbox"));
  }

  @Test
  void 从pageSource读自定义ImageView勾选态() {
    String xml = """
        <hierarchy>
          <android.widget.ImageView index="2" resource-id="com.pwrd.steam.esports:id/policy_checkbox"
            class="android.widget.ImageView" package="com.pwrd.steam.esports" checkable="true"
            checked="true" clickable="true" bounds="[48,900][96,948]" />
        </hierarchy>
        """;

    Boolean checked = ToggleStateReader.readCheckedFromPageSource(
        xml, "id:com.pwrd.steam.esports:id/policy_checkbox");
    assertTrue(checked);

    String uncheckedXml = xml.replace("checked=\"true\"", "checked=\"false\"");
    Boolean unchecked = ToggleStateReader.readCheckedFromPageSource(
        uncheckedXml, "id:com.pwrd.steam.esports:id/policy_checkbox");
    assertFalse(unchecked);
  }
}
