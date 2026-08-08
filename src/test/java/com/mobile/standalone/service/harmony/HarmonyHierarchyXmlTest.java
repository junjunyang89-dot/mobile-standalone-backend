package com.mobile.standalone.service.harmony;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarmonyHierarchyXmlTest {

    @Test
    void json转xml保留bounds与text() {
        String json = """
                {
                  "attributes": {"bounds":"[0,0][100,200]","text":"","type":"root","clickable":"false","enabled":"true"},
                  "children": [
                    {
                      "attributes": {"bounds":"[10,20][90,60]","text":"登录","type":"Button","id":"loginBtn","clickable":"true","enabled":"true","visible":"true"},
                      "children": []
                    }
                  ]
                }
                """;
        String xml = HarmonyHierarchyXml.toInspectXml(json);
        assertNotNull(xml);
        assertTrue(xml.contains("bounds=\"[10,20][90,60]\""));
        assertTrue(xml.contains("text=\"登录\""));
        assertTrue(xml.contains("resource-id=\"loginBtn\""));
        assertTrue(xml.contains("class=\"Button\""));
        assertTrue(xml.contains("clickable=\"true\""));
    }
}
