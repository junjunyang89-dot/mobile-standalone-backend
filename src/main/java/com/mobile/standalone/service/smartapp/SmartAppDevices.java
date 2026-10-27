package com.mobile.standalone.service.smartapp;

import com.mobile.standalone.entity.Device;
import com.mobile.standalone.service.driver.*;
import io.appium.java_client.AppiumDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import io.appium.java_client.ios.options.XCUITestOptions;
import org.openqa.selenium.*;
import org.openqa.selenium.interactions.PointerInput;
import org.openqa.selenium.interactions.Sequence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static com.mobile.standalone.service.smartapp.SmartAppModels.*;

/** Own Appium process and sessions; never connects to or kills another service. */
@Component
public class SmartAppDevices {
    private final PlatformRouter router;
    private final int port;
    private final String command;
    private final String teamId;
    private final String wdaBundle;
    private Process process;
    private final Map<String,int[]> allocated = new HashMap<>();
    public SmartAppDevices(PlatformRouter router,
            @Value("${smart-app.appium-port:14731}") int port,
            @Value("${smart-app.appium-command:appium}") String command,
            @Value("${ios.wda.xcode-org-id:}") String teamId,
            @Value("${ios.wda.bundle-id:com.travYang.wda.Runner}") String wdaBundle) {
        this.router=router;this.port=port;this.command=command;this.teamId=teamId;this.wdaBundle=wdaBundle;
    }
    public int port(){return port;}
    private synchronized int[] ports(String device) throws Exception {
        if(allocated.containsKey(device))return allocated.get(device);
        // Reserve unique ports per device; occupied candidates are skipped, never killed.
        for(int i=0;i<500;i++){
            int[] p={18231+i,19231+i,17231+i};
            boolean used=allocated.values().stream().anyMatch(v->v[0]==p[0]);
            if(!used&&free(p[0])&&free(p[1])&&free(p[2])){allocated.put(device,p);return p;}
        }
        throw new IllegalStateException("没有可用的独立设备通信端口");
    }
    private static boolean free(int p){try(var s=new ServerSocket()){s.bind(new InetSocketAddress("127.0.0.1",p));return true;}catch(Exception e){return false;}}
    private synchronized void ensureServer() throws Exception {
        if(process!=null&&process.isAlive())return;
        if(!free(port))throw new IllegalStateException("智能自动化端口 "+port+" 已被其他服务占用；请修改 smart-app.appium-port，不会停止占用进程");
        Path log=Path.of(System.getProperty("java.io.tmpdir"),"smart-app-appium.log");
        process=new ProcessBuilder(command,"--address","127.0.0.1","--port",String.valueOf(port),"--base-path","/","--log-level","warn")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        long end=System.nanoTime()+Duration.ofSeconds(25).toNanos();
        while(System.nanoTime()<end){
            if(!process.isAlive())throw new IllegalStateException("独立 Appium 启动失败，请检查 "+log);
            try{HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/status").openConnection();c.setConnectTimeout(400);c.setReadTimeout(700);int status=c.getResponseCode();c.disconnect();if(status==200)return;}catch(Exception ignored){}
            Thread.sleep(250);
        }
        process.destroy();throw new IllegalStateException("独立 Appium 启动超时，请检查 "+log);
    }
    public Session open(Device d) throws Exception {
        if(Device.usesHdcAutomation(d.getPlatform()))return new Session(null,router.getDriver(d),d.getId(),true);
        ensureServer();int[] p=ports(d.getId());URL url=new URL("http://127.0.0.1:"+port);
        AppiumDriver driver;
        if(Device.usesAndroidAutomation(d.getPlatform())){
            UiAutomator2Options o=new UiAutomator2Options();o.setUdid(d.getId());o.setNoReset(true);o.setSystemPort(p[0]);o.setMjpegServerPort(p[1]);o.setNewCommandTimeout(Duration.ofSeconds(180));
            o.setCapability("skipDeviceInitialization",true);
            driver=new io.appium.java_client.android.AndroidDriver(url,o);
        }else if(Device.usesIosAutomation(d.getPlatform())){
            XCUITestOptions o=new XCUITestOptions();o.setUdid(d.getId());o.setNoReset(true);o.setWdaLocalPort(p[2]);o.setNewCommandTimeout(Duration.ofSeconds(180));
            o.setCapability("updatedWDABundleId",wdaBundle);o.setCapability("useNewWDA",false);
            if(!teamId.isBlank()){o.setCapability("xcodeOrgId",teamId);o.setCapability("xcodeSigningId","Apple Development");}
            driver=new io.appium.java_client.ios.IOSDriver(url,o);
        }else throw new IllegalArgumentException("不支持的设备平台");
        return new Session(driver,null,d.getId(),false);
    }
    @PreDestroy public synchronized void shutdown(){if(process!=null&&process.isAlive())process.destroy();}
    public static final class Session implements AutoCloseable {
        private final AppiumDriver driver;private final PlatformDriver harmony;private final String id;private final boolean isHarmony;
        Session(AppiumDriver driver,PlatformDriver harmony,String id,boolean isHarmony){this.driver=driver;this.harmony=harmony;this.id=id;this.isHarmony=isHarmony;}
        public String source(){return isHarmony?harmony.getPageSource(id):driver.getPageSource();}
        public void click(Node n){if(!n.bounded())throw new IllegalStateException("目标没有可点击边界");tap((n.left()+n.right())/2,(n.top()+n.bottom())/2);}
        private void tap(int x,int y){
            if(isHarmony){ok(harmony.click(id,x,y));return;}
            PointerInput finger=new PointerInput(PointerInput.Kind.TOUCH,"smart-app-finger");Sequence seq=new Sequence(finger,0);
            seq.addAction(finger.createPointerMove(Duration.ZERO,PointerInput.Origin.viewport(),x,y));seq.addAction(finger.createPointerDown(0));seq.addAction(finger.createPointerUp(0));driver.perform(List.of(seq));
        }
        public void input(Node n,String value){
            if(!n.editable())throw new IllegalStateException("目标不是输入框，请先打开输入面板");
            if(isHarmony){click(n);ok(harmony.inputText(id,value));return;}
            WebElement e=driver.findElement(By.xpath(n.path()));e.click();e.clear();e.sendKeys(value);
        }
        public void launch(String pkg){
            if(isHarmony){ok(harmony.launchApp(id,pkg));return;}
            if(driver instanceof io.appium.java_client.android.AndroidDriver a)a.activateApp(pkg);
            else if(driver instanceof io.appium.java_client.ios.IOSDriver i)i.activateApp(pkg);
        }
        public void back(){if(isHarmony)ok(harmony.pressBack(id));else driver.navigate().back();}
        public void swipe(String direction){
            List<Node> nodes=SmartAppTree.parse(source());
            Node root=nodes.stream().filter(Node::bounded).findFirst().orElseThrow(()->new IllegalStateException("无法读取屏幕范围"));
            int x=(root.left()+root.right())/2,y1=root.top()+(root.bottom()-root.top())*3/4,y2=root.top()+(root.bottom()-root.top())/4;
            if("DOWN".equals(direction)){int t=y1;y1=y2;y2=t;}
            if(isHarmony){ok(harmony.swipe(id,x,y1,x,y2,400));return;}
            PointerInput f=new PointerInput(PointerInput.Kind.TOUCH,"smart-app-swipe");Sequence s=new Sequence(f,0);
            s.addAction(f.createPointerMove(Duration.ZERO,PointerInput.Origin.viewport(),x,y1));s.addAction(f.createPointerDown(0));s.addAction(f.createPointerMove(Duration.ofMillis(400),PointerInput.Origin.viewport(),x,y2));s.addAction(f.createPointerUp(0));driver.perform(List.of(s));
        }
        public void screenshot(Path file) throws Exception{
            if(isHarmony){String saved=harmony.takeScreenshot(id,file.toString());if(saved==null||!Files.isRegularFile(file))throw new IllegalStateException("设备截图失败");}
            else Files.write(file,driver.getScreenshotAs(OutputType.BYTES));
        }
        private static void ok(boolean ok){if(!ok)throw new IllegalStateException("设备驱动执行失败");}
        @Override public void close(){if(driver!=null)driver.quit();}
    }
}
