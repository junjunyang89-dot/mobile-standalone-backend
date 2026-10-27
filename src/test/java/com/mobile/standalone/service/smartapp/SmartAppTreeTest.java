package com.mobile.standalone.service.smartapp;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.mobile.standalone.service.smartapp.SmartAppModels.*;

class SmartAppTreeTest {
    private final String android="""
      <hierarchy><node class="android.widget.FrameLayout" bounds="[0,0][1080,2340]">
      <node class="android.widget.TextView" text="评论" bounds="[0,0][100,60]"/>
      <node class="android.widget.TextView" text="评论" bounds="[0,60][100,120]"/>
      <node class="android.widget.EditText" resource-id="comment" text="不错" bounds="[0,120][500,240]"/>
      <node class="android.widget.TextView" text="隐藏" displayed="false" bounds="[0,0][100,60]"/>
      </node></hierarchy>""";
    @Test void duplicateTextDoesNotSilentlyPickFirst(){assertThrows(SmartAppTree.Ambiguous.class,()->SmartAppTree.resolve(SmartAppTree.parse(android),new Target("评论",null,null,null,null,null,null)));}
    @Test void explicitIndexSelectsSecondAndKeepsCorrectXPath(){Node n=SmartAppTree.resolve(SmartAppTree.parse(android),new Target("评论",null,null,null,null,null,1));assertEquals(60,n.top());assertEquals("/hierarchy[1]/node[1]/node[2]",n.path());}
    @Test void inputRoleFindsActualEditableNode(){Node n=SmartAppTree.resolve(SmartAppTree.parse(android),new Target(null,null,null,null,"input",null,null));assertEquals("不错",n.text());assertEquals("comment",n.id());}
    @Test void hiddenNodesExcluded(){assertThrows(SmartAppTree.Missing.class,()->SmartAppTree.resolve(SmartAppTree.parse(android),new Target("隐藏",null,null,null,null,null,null)));}
    @Test void parsesIosValueAndGeometry(){String xml="<AppiumAUT><XCUIElementTypeTextView type='XCUIElementTypeTextView' value='不错' name='comment' x='10' y='20' width='100' height='50'/></AppiumAUT>";Node n=SmartAppTree.resolve(SmartAppTree.parse(xml),new Target(null,null,null,null,"input",null,null));assertEquals("不错",n.text());assertEquals(110,n.right());}
    @Test void parsesHarmonyAttributes(){String h="""
      {"attributes":{"type":"Root","bounds":"[0,0][100,200]"},"children":[{"attributes":{"type":"TextInput","id":"comment","text":"不错","bounds":"[0,20][100,70]"}}]}
      """;Node n=SmartAppTree.resolve(SmartAppTree.parse(h),new Target(null,null,null,null,"input",null,null));assertEquals("不错",n.text());assertEquals(70,n.bottom());}
    @Test void scopesRepeatedLabelToEnclosingRegion(){String xml="<root><node text='导航' bounds='[0,0][100,100]'><node text='评论' bounds='[0,0][50,50]'/></node><node text='评论' bounds='[0,150][50,200]'/></root>";Node n=SmartAppTree.resolve(SmartAppTree.parse(xml),new Target("评论",null,null,null,null,"导航",null));assertEquals(0,n.top());}
    @Test void rejectsDtdAndExternalEntities(){assertThrows(IllegalStateException.class,()->SmartAppTree.parse("<!DOCTYPE x [<!ENTITY p SYSTEM 'file:///etc/passwd'>]><x>&p;</x>"));}
    @Test void missingVariableStopsBeforeAnAction(){assertThrows(IllegalArgumentException.class,()->SmartAppService.expand("${phone}",Map.of()));assertEquals("不错 $1",SmartAppService.expand("${comment}",Map.of("comment","不错 $1")));}
}
