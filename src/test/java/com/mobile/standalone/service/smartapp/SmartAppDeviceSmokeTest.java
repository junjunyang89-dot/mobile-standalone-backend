package com.mobile.standalone.service.smartapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.controller.SmartAppController;
import com.mobile.standalone.entity.Device;
import com.mobile.standalone.mapper.DeviceMapper;
import com.mobile.standalone.service.DeviceLockService;
import com.mobile.standalone.service.driver.PlatformRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.mobile.standalone.service.smartapp.SmartAppModels.*;

/** Explicit opt-in, real device acceptance. Never submits a comment. */
@EnabledIfEnvironmentVariable(named="SMART_APP_DEVICE",matches=".+")
class SmartAppDeviceSmokeTest {
    @TempDir Path dir;
    @Test void realAndroidNewsCommentDraft()throws Exception{
        String serial=System.getenv("SMART_APP_DEVICE");
        Path output=System.getenv("SMART_APP_SMOKE_OUTPUT")==null?Files.createTempDirectory("smart-app-smoke-"):Path.of(System.getenv("SMART_APP_SMOKE_OUTPUT"));Files.createDirectories(output);
        var d=new Device();d.setId(serial);d.setPlatform(Device.Platform.ANDROID);d.setStatus(Device.DeviceStatus.CONNECTED);
        var mapper=mock(DeviceMapper.class);when(mapper.findById(serial)).thenReturn(d);
        var sessions=new SmartAppDevices(mock(PlatformRouter.class),14731,"appium","","");
        var locks=new DeviceLockService();var service=new SmartAppService(new ObjectMapper(),mapper,locks,sessions,dir.toString());
        try{
            Script template=new SmartAppController(service).templates().get(1);
            Script saved=service.save(template);Run run=service.start(saved.id(),new Start(serial,Map.of("comment","不错"),false));
            Run result=null;
            for(int i=0;i<180;i++){Thread.sleep(1000);result=service.run(run.id());if(result.finishedAt()!=null)break;}
            assertNotNull(result);
            Files.writeString(output.resolve("real-device-result.json"),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
            Path evidence=dir.resolve("evidence").resolve(run.id());
            if(Files.isDirectory(evidence))try(var f=Files.list(evidence)){for(Path p:f.toList())Files.copy(p,output.resolve("device-"+p.getFileName()),StandardCopyOption.REPLACE_EXISTING);}
            assertEquals("SUCCEEDED",result.status(),result.message());assertEquals(12,result.events().size());assertNull(locks.current(serial));
        }finally{service.stop();sessions.shutdown();}
    }
}
