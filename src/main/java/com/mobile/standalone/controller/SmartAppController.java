package com.mobile.standalone.controller;

import com.mobile.standalone.service.smartapp.SmartAppService;
import com.mobile.standalone.service.smartapp.SmartAppModels.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.nio.file.NoSuchFileException;
import java.util.*;

@RestController
@RequestMapping("/smart-app")
public class SmartAppController {
    private final SmartAppService service;
    public SmartAppController(SmartAppService service){this.service=service;}
    @GetMapping("/config") public Object config(){return service.config();}
    @GetMapping("/scripts") public ResponseEntity<?> list(){return respond(service::scripts);}
    @GetMapping("/scripts/{id}") public ResponseEntity<?> get(@PathVariable String id){return respond(()->service.script(id));}
    @PostMapping("/scripts") public ResponseEntity<?> save(@RequestBody Script script){return respond(()->service.save(script));}
    @PostMapping("/scripts/{id}/runs") public ResponseEntity<?> start(@PathVariable String id,@RequestBody Start request){return respond(()->service.start(id,request));}
    @GetMapping("/runs") public ResponseEntity<?> runs(){return respond(service::runs);}
    @GetMapping("/runs/{id}") public ResponseEntity<?> run(@PathVariable String id){return respond(()->service.run(id));}
    @PostMapping("/runs/{id}/cancel") public ResponseEntity<?> cancel(@PathVariable String id){return respond(()->service.cancel(id));}
    @GetMapping("/runs/{id}/evidence/{name}") public ResponseEntity<?> evidence(@PathVariable String id,@PathVariable String name){return respond(()->service.evidence(id,name));}
    @PostMapping("/inspect") public ResponseEntity<?> inspect(@RequestBody Map<String,String> body){return respond(()->service.inspect(body.get("deviceId")));}
    @GetMapping("/templates") public List<Script> templates(){
        Target found=target("发现"),mine=target("我的");
        List<Step> smoke=List.of(
                step("打开应用","LAUNCH",null,null),step("回到主导航","BACK_TO",found,null),
                step("进入发现","CLICK",found,null),
                step("进入赛事","CLICK",target("赛事"),null),
                step("检查赛程","ASSERT",target("赛程"),null),
                step("进入社区","CLICK",target("社区"),null),
                step("检查广场","ASSERT",target("广场"),null),
                step("进入数据","CLICK",target("数据"),null),
                step("返回我的","CLICK",mine,null),
                step("确认个人页","ASSERT",target("编辑资料"),null));
        Target news=new Target(null,null,"com.pwrd.steam.esports:id/tvTitle",null,null,null,0);
        List<Step> comment=List.of(
                step("打开应用","LAUNCH",null,null),step("回到主导航","BACK_TO",found,null),step("进入发现","CLICK",found,null),
                step("选择 CS","CLICK",target("CS"),null),step("选择资讯","CLICK",target("资讯"),null),
                new Step("读取首条新闻标题","READ",news,null,"newsTitle",10,null,"READ"),
                step("进入新闻","CLICK",target("${newsTitle}"),null),
                step("确认新闻标题","ASSERT",target("${newsTitle}"),null),
                step("进入评论","CLICK",target("评论"),null),
                step("打开评论输入框","CLICK",target("友好评论，友善社区"),null),
                step("填写评论草稿","INPUT",new Target(null,null,null,null,"input",null,null),"${comment}"),
                step("核验输入内容","ASSERT",new Target(null,null,null,null,"input",null,null),"${comment}"));
        return List.of(new Script(null,"完美世界电竞 · 主导航检查","ANDROID","com.pwrd.steam.esports",Map.of(),smoke,null),
                new Script(null,"完美世界电竞 · CS赛事资讯评论草稿","ANDROID","com.pwrd.steam.esports",Map.of("comment","不错"),comment,null));
    }
    private static Target target(String text){return new Target(text,null,null,null,null,null,null);}
    private static Step step(String name,String action,Target t,String value){return new Step(name,action,t,value,null,10,null,"READ");}
    private interface Work {Object run()throws Exception;}
    private ResponseEntity<?> respond(Work work){
        try{return ResponseEntity.ok(work.run());}
        catch(NoSuchFileException e){return ResponseEntity.status(404).body(Map.of("message","记录不存在"));}
        catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));}
        catch(IllegalStateException e){return ResponseEntity.status(409).body(Map.of("message",e.getMessage()==null?"当前状态不支持此操作":e.getMessage()));}
        catch(Exception e){return ResponseEntity.internalServerError().body(Map.of("message","智能自动化操作失败: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage())));}
    }
}
