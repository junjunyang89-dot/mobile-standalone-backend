package com.mobile.standalone.service.smartapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.entity.Device;
import com.mobile.standalone.mapper.DeviceMapper;
import com.mobile.standalone.service.DeviceLockService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;
import static com.mobile.standalone.service.smartapp.SmartAppModels.*;

@Service
public class SmartAppService {
    private static final Set<String> ACTIONS=Set.of("LAUNCH","CLICK","INPUT","WAIT","ASSERT","READ","BACK","BACK_TO","SWIPE","SNAPSHOT");
    private static final Pattern VARIABLE=Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9_]*)}");
    private final ObjectMapper json;
    private final DeviceMapper devices;
    private final DeviceLockService locks;
    private final SmartAppDevices sessions;
    private final Path root;
    private final ExecutorService workers=new ThreadPoolExecutor(4,4,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(20),r->{Thread t=new Thread(r,"smart-app-run");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final ConcurrentMap<String,Live> active=new ConcurrentHashMap<>();
    private static final class Live {
        final AtomicBoolean cancelled=new AtomicBoolean();
        final List<Event> events=new CopyOnWriteArrayList<>();
        final String id,scriptId,scriptName,deviceId,platform,startedAt;
        volatile String status="QUEUED",message="等待执行",finishedAt;
        Live(String id,Script s,Device d){this.id=id;scriptId=s.id();scriptName=s.name();deviceId=d.getId();platform=d.getPlatform().name();startedAt=Instant.now().toString();}
        Run view(){
            boolean stopping=cancelled.get()&&Set.of("QUEUED","RUNNING").contains(status);
            return new Run(id,scriptId,scriptName,deviceId,platform,stopping?"CANCELLING":status,stopping?"正在停止，等待当前设备命令返回":message,startedAt,finishedAt,List.copyOf(events));
        }
    }
    public SmartAppService(ObjectMapper json,DeviceMapper devices,DeviceLockService locks,SmartAppDevices sessions,
            @Value("${smart-app.storage-dir:./data/smart-app}") String dir) throws Exception{
        this.json=json;this.devices=devices;this.locks=locks;this.sessions=sessions;root=Path.of(dir).toAbsolutePath().normalize();
        Files.createDirectories(root.resolve("scripts"));Files.createDirectories(root.resolve("runs"));
        for(Run r:readAll("runs",Run.class))if(Set.of("QUEUED","RUNNING","CANCELLING").contains(r.status()))
            write(root.resolve("runs").resolve(r.id()+".json"),new Run(r.id(),r.scriptId(),r.scriptName(),r.deviceId(),r.platform(),"INTERRUPTED","服务重启导致执行中断，请检查现场后重新运行",r.startedAt(),Instant.now().toString(),r.events()));
    }
    public Map<String,Object> config(){return Map.of("appiumPort",sessions.port(),"actions",ACTIONS.stream().sorted().toList(),"mode","SCRIPT","storage","执行节点本地保存","platforms",List.of("ANDROID","IOS","HARMONYOS"));}
    public List<Script> scripts() throws Exception{return readAll("scripts",Script.class).stream().sorted(Comparator.comparing(Script::updatedAt).reversed()).toList();}
    public Script script(String id) throws Exception{return read(file("scripts",id),Script.class);}
    public synchronized Script save(Script draft) throws Exception{
        validate(draft);String id=draft.id();
        if(id==null||id.isBlank())id=UUID.randomUUID().toString();else if(!Files.exists(file("scripts",id)))throw new IllegalArgumentException("用例不存在，请作为新用例保存");
        Script s=new Script(id,draft.name().trim(),draft.platform(),draft.packageName(),draft.variables()==null?Map.of():Map.copyOf(draft.variables()),List.copyOf(draft.steps()),Instant.now().toString());
        write(file("scripts",id),s);return s;
    }
    public static void validate(Script s){
        if(s==null||s.name()==null||s.name().isBlank()||s.name().length()>120)throw new IllegalArgumentException("用例名称需为 1–120 字");
        if(!Set.of("ANDROID","IOS","HARMONYOS").contains(s.platform()==null?"":s.platform()))throw new IllegalArgumentException("请选择 Android、iOS 或鸿蒙");
        if(s.steps()==null||s.steps().isEmpty()||s.steps().size()>100)throw new IllegalArgumentException("用例需要 1–100 个步骤");
        if(s.variables()!=null&&(s.variables().size()>50||s.variables().entrySet().stream().anyMatch(e->e.getKey()==null||!e.getKey().matches("[A-Za-z][A-Za-z0-9_]*")||e.getValue()==null||e.getValue().length()>4000)))throw new IllegalArgumentException("变量名需为英文标识，最多 50 个变量，每个值不超过 4000 字");
        for(Step st:s.steps()){
            if(st==null||!ACTIONS.contains(st.action()==null?"":st.action()))throw new IllegalArgumentException("不支持的动作，仅支持 "+ACTIONS);
            if(st.timeoutSeconds()!=null&&(st.timeoutSeconds()<1||st.timeoutSeconds()>30))throw new IllegalArgumentException("每步等待需为 1–30 秒");
            if(st.value()!=null&&st.value().length()>4000)throw new IllegalArgumentException("步骤内容过长");
            if(st.effect()!=null&&!Set.of("READ","WRITE").contains(st.effect()))throw new IllegalArgumentException("effect 仅支持 READ / WRITE");
            if(Set.of("CLICK","INPUT","WAIT","ASSERT","READ","BACK_TO").contains(st.action())){
                Target t=st.target();
                if(t==null||(SmartAppTree.empty(t.text())&&SmartAppTree.empty(t.contains())&&SmartAppTree.empty(t.id())&&SmartAppTree.empty(t.description())&&SmartAppTree.empty(t.role())))throw new IllegalArgumentException("操作步骤需要目标描述");
                if(t.index()!=null&&t.index()<0)throw new IllegalArgumentException("index 从 0 开始");
                if(t.role()!=null&&!Set.of("input","text").contains(t.role()))throw new IllegalArgumentException("role 仅支持 input / text");
            }
            if("READ".equals(st.action())&&(st.saveAs()==null||!st.saveAs().matches("[A-Za-z][A-Za-z0-9_]*")))throw new IllegalArgumentException("读取步骤需要有效的 saveAs 变量名");
            if("INPUT".equals(st.action())&&st.value()==null)throw new IllegalArgumentException("输入步骤需要 value");
            if("SWIPE".equals(st.action())&&!Set.of("UP","DOWN").contains(st.direction()==null?"":st.direction()))throw new IllegalArgumentException("滑动方向仅支持 UP / DOWN");
        }
    }
    public Run start(String id,Start request) throws Exception{
        Script s=script(id);validate(s);
        if(request==null)throw new IllegalArgumentException("请选择设备");
        Device d=eligible(request.deviceId(),s.platform());
        if(s.steps().stream().anyMatch(SmartAppService::writes)&&!request.allowWrites())throw new IllegalArgumentException("此用例含发布等业务写入步骤，请明确允许本次执行写操作");
        Map<String,String> vars=new HashMap<>(s.variables());if(request.variables()!=null){if(request.variables().size()>50||request.variables().values().stream().anyMatch(v->v==null||v.length()>4000))throw new IllegalArgumentException("执行变量格式不正确");vars.putAll(request.variables());}
        String runId=UUID.randomUUID().toString();Live run=new Live(runId,s,d);
        acquire(d.getId(),runId,s.name());active.put(runId,run);
        try{persist(run);workers.submit(()->execute(run,s,d,vars));}
        catch(Exception e){active.remove(runId);locks.release(d.getId(),runId);run.status="FAILED";run.message="无法提交执行: "+e.getMessage();run.finishedAt=Instant.now().toString();persist(run);throw e;}
        return run.view();
    }
    private static boolean writes(Step s){
        if("WRITE".equals(s.effect()))return true;
        // The sample's publication action must remain opt-in even if effect is accidentally omitted.
        return "CLICK".equals(s.action())&&s.target()!=null&&Set.of("发布","发送","删除","确认支付").contains(s.target().text()==null?"":s.target().text());
    }
    private Device eligible(String id,String platform){
        if(id==null||id.isBlank())throw new IllegalArgumentException("请选择设备");Device d=devices.findById(id);
        if(d==null||d.getPlatform()==null||d.getStatus()!=Device.DeviceStatus.CONNECTED||Boolean.TRUE.equals(d.getDisabled()))throw new IllegalArgumentException("设备离线、已停用或不存在");
        if(platform!=null&&!Device.matchesAutomationDeviceListFilter(d.getPlatform(),Device.Platform.valueOf(platform)))throw new IllegalArgumentException("用例平台与设备平台不一致");
        if(Device.isEmulator(d)&&"PROD".equals(d.getEnvironment()))throw new IllegalArgumentException("模拟器不能运行正式环境用例");
        return d;
    }
    private void acquire(String device,String ref,String label){var b=locks.tryAcquire(device,DeviceLockService.Kind.TASK,ref,"智能自动化 · "+label);if(b!=null)throw new IllegalStateException("设备正被 "+b.describe()+" 占用");}
    private void execute(Live r,Script script,Device d,Map<String,String> vars){
        SmartAppDevices.Session session=null;long deadline=System.nanoTime()+Duration.ofMinutes(30).toNanos();
        try{
            check(r,deadline);r.status="RUNNING";r.message="连接设备";persist(r);session=sessions.open(d);
            for(int i=0;i<script.steps().size();i++){
                check(r,deadline);locks.renew(d.getId(),r.id);Step raw=script.steps().get(i);String started=Instant.now().toString();long t=System.nanoTime();String name=raw.name()==null?raw.action():raw.name();r.message=name;persist(r);
                String state="PASSED",message="已完成",evidence=String.format("step-%03d",i+1);
                try{perform(session,r,raw,script.packageName(),vars,deadline);}
                catch(CancellationException e){state="CANCELLED";message="已请求停止";}
                catch(Exception e){state="FAILED";message=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
                String warning=capture(session,r.id,evidence);
                if(!warning.isEmpty())message+="；"+warning;
                message=redact(message,vars);
                r.events.add(new Event(i+1,name,raw.action(),state,message,evidence,started,(System.nanoTime()-t)/1_000_000));persist(r);
                if(!"PASSED".equals(state)){r.status=state;r.message=message;return;}
            }
            r.status="SUCCEEDED";r.message="全部步骤通过";
        }catch(CancellationException e){r.status="CANCELLED";r.message="已停止";}
        catch(Exception e){r.status="FAILED";r.message=redact(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage(),vars);}
        finally{
            // Keep the device lock until the session has actually closed; cancel does not release it early.
            if(session!=null)try{session.close();}catch(Exception e){r.message+="；会话清理失败，请检查设备连接";}
            r.finishedAt=Instant.now().toString();try{persist(r);}catch(Exception ignored){}
            locks.release(d.getId(),r.id);active.remove(r.id);
        }
    }
    private void perform(SmartAppDevices.Session session,Live run,Step step,String pkg,Map<String,String> vars,long deadline) throws Exception{
        String value=expand(step.value(),vars);Target target=expand(step.target(),vars);
        switch(step.action()){
            case "LAUNCH"->{String app=SmartAppTree.empty(value)?expand(pkg,vars):value;if(SmartAppTree.empty(app))throw new IllegalArgumentException("启动步骤需要包名 / Bundle ID");session.launch(app);}
            case "BACK"->session.back();
            case "BACK_TO"->{
                boolean found=false;
                for(int i=0;i<=5;i++){
                    check(run,deadline);
                    try{SmartAppTree.resolve(SmartAppTree.parse(session.source()),target);found=true;break;}
                    catch(SmartAppTree.Missing e){if(i<5){session.back();Thread.sleep(400);}}
                }
                if(!found)throw new IllegalStateException("返回 5 次后仍未找到目标页面");
            }
            case "SWIPE"->session.swipe(step.direction());
            case "SNAPSHOT"->SmartAppTree.parse(session.source());
            default->{
                Node node=await(session,run,target,step.timeoutSeconds()==null?10:step.timeoutSeconds(),deadline,step.action().equals("ASSERT")?value:null);
                check(run,deadline);
                switch(step.action()){
                    case "CLICK"->session.click(node); // Never retry a mutation after command submission.
                    case "INPUT"->session.input(node,value);
                    case "READ"->vars.put(step.saveAs(),node.text());
                    case "ASSERT","WAIT"->{}
                    default->throw new IllegalArgumentException("未知动作");
                }
            }
        }
    }
    private Node await(SmartAppDevices.Session s,Live run,Target target,int seconds,long deadline,String expected) throws Exception{
        long end=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();String last="";
        do{
            check(run,deadline);
            try{Node n=SmartAppTree.resolve(SmartAppTree.parse(s.source()),target);if(expected==null||expected.equals(n.text()))return n;last="目标内容与预期不一致";}
            catch(SmartAppTree.Missing e){last=e.getMessage();}
            // Ambiguity, malformed hierarchy and driver errors are not treated as absence.
            Thread.sleep(300);
        }while(System.nanoTime()<end);
        throw new IllegalStateException(last+"（等待 "+seconds+" 秒）");
    }
    static String redact(String message,Map<String,String> vars){
        if(message==null)return "执行失败";
        for(String value:vars.values())if(value!=null&&value.length()>=3)message=message.replace(value,"****");
        return message;
    }
    static String expand(String value,Map<String,String> vars){
        if(value==null)return null;Matcher m=VARIABLE.matcher(value);StringBuffer b=new StringBuffer();
        while(m.find()){String v=vars.get(m.group(1));if(v==null)throw new IllegalArgumentException("缺少变量: "+m.group(1));m.appendReplacement(b,Matcher.quoteReplacement(v));}m.appendTail(b);return b.toString();
    }
    private Target expand(Target t,Map<String,String> v){return t==null?null:new Target(expand(t.text(),v),expand(t.contains(),v),expand(t.id(),v),expand(t.description(),v),t.role(),expand(t.withinText(),v),t.index());}
    private void check(Live r,long end){if(r.cancelled.get()||Thread.currentThread().isInterrupted())throw new CancellationException();if(System.nanoTime()>end)throw new IllegalStateException("用例执行超过 30 分钟上限");if(!locks.isHeldBy(r.deviceId,r.id))throw new IllegalStateException("设备占用已失效，停止执行");}
    private String capture(SmartAppDevices.Session s,String runId,String name){
        List<String> warning=new ArrayList<>();Path dir=root.resolve("evidence").resolve(runId);
        try{Files.createDirectories(dir);}catch(Exception e){return "证据目录不可写";}
        try{String source=s.source();if(source==null)throw new IllegalStateException();Files.writeString(dir.resolve(name+".source"),source);}catch(Exception e){warning.add("页面结构保存失败");}
        try{s.screenshot(dir.resolve(name+".png"));}catch(Exception e){warning.add("截图保存失败");}
        return String.join("；",warning);
    }
    public Run run(String id) throws Exception{Live l=active.get(id);return l==null?read(file("runs",id),Run.class):l.view();}
    public List<Run> runs() throws Exception{
        Map<String,Run> out=new HashMap<>();for(Run r:readAll("runs",Run.class))out.put(r.id(),r);active.forEach((id,r)->out.put(id,r.view()));return out.values().stream().sorted(Comparator.comparing(Run::startedAt).reversed()).limit(100).toList();
    }
    public Run cancel(String id) throws Exception{Live r=active.get(id);if(r==null)return run(id);r.cancelled.set(true);persist(r);return r.view();}
    public Evidence inspect(String deviceId) throws Exception{
        Device d=eligible(deviceId,null);String id=UUID.randomUUID().toString();acquire(d.getId(),id,"读取页面");
        try(var s=sessions.open(d)){
            String source=s.source();List<Node> nodes=SmartAppTree.parse(source);Path p=Files.createTempFile("smart-app-inspect-",".png");
            String image=null,warning="";try{s.screenshot(p);image=dataImage(p);}catch(Exception e){warning="页面结构已读取，截图失败";}finally{Files.deleteIfExists(p);}
            return new Evidence(source,nodes,image,warning);
        }finally{locks.release(d.getId(),id);}
    }
    public Evidence evidence(String runId,String name) throws Exception{
        run(runId);if(!name.matches("step-\\d{3}"))throw new IllegalArgumentException("证据编号不正确");
        Path dir=root.resolve("evidence").resolve(validId(runId));String source=Files.exists(dir.resolve(name+".source"))?Files.readString(dir.resolve(name+".source")):"";
        List<Node> nodes=List.of();String warning="";if(!source.isEmpty())try{nodes=SmartAppTree.parse(source);}catch(Exception e){warning="页面结构无法解析，保留原文";}
        return new Evidence(source,nodes,Files.exists(dir.resolve(name+".png"))?dataImage(dir.resolve(name+".png")):null,warning);
    }
    private String dataImage(Path p)throws Exception{return "data:image/png;base64,"+Base64.getEncoder().encodeToString(Files.readAllBytes(p));}
    private void persist(Live r)throws Exception{write(file("runs",r.id),r.view());}
    private Path file(String kind,String id){return root.resolve(kind).resolve(validId(id)+".json");}
    private String validId(String id){if(id==null||!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IllegalArgumentException("记录 ID 不正确");return id;}
    private <T>T read(Path p,Class<T> type)throws Exception{if(!Files.exists(p))throw new NoSuchFileException("记录不存在");return json.readValue(Files.readString(p),type);}
    private <T>List<T> readAll(String dir,Class<T> type)throws Exception{try(var files=Files.list(root.resolve(dir))){List<T> out=new ArrayList<>();for(Path p:files.filter(p->p.getFileName().toString().matches("[a-f0-9-]{36}\\.json")).toList())out.add(read(p,type));return out;}}
    private synchronized void write(Path path,Object value)throws Exception{
        Path tmp=Files.createTempFile(path.getParent(),".pending-",".json");
        try{json.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(),value);try{Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(tmp);}
    }
    @PreDestroy public void stop(){active.values().forEach(r->r.cancelled.set(true));workers.shutdown();try{if(!workers.awaitTermination(5,TimeUnit.SECONDS))workers.shutdownNow();}catch(InterruptedException e){workers.shutdownNow();Thread.currentThread().interrupt();}}
}
