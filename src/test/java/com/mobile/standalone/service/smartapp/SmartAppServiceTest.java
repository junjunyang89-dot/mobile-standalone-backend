package com.mobile.standalone.service.smartapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.controller.SmartAppController;
import com.mobile.standalone.entity.Device;
import com.mobile.standalone.mapper.DeviceMapper;
import com.mobile.standalone.service.DeviceLockService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.mobile.standalone.service.smartapp.SmartAppModels.*;

class SmartAppServiceTest {
    @TempDir Path dir;
    SmartAppService service;
    DeviceMapper mapper;
    SmartAppDevices devices;
    DeviceLockService locks;
    Device device;
    @BeforeEach void setup()throws Exception{
        mapper=mock(DeviceMapper.class);devices=mock(SmartAppDevices.class);locks=new DeviceLockService();
        device=new Device();device.setId("phone");device.setPlatform(Device.Platform.ANDROID);device.setStatus(Device.DeviceStatus.CONNECTED);when(mapper.findById("phone")).thenReturn(device);
        service=new SmartAppService(new ObjectMapper(),mapper,locks,devices,dir.toString());
    }
    @AfterEach void stop(){service.stop();}
    Script save(List<Step> steps)throws Exception{return service.save(new Script(null,"test","ANDROID","pkg",Map.of(),steps,null));}
    Step click(){return new Step("发布一次","CLICK",new Target("发布",null,null,null,null,null,null),null,null,1,null,"WRITE");}
    @Test void httpContractSavesLoadsAndRejectsInvalidScripts()throws Exception{
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new SmartAppController(service)).build();
        String payload="{\"name\":\"snapshot\",\"platform\":\"ANDROID\",\"variables\":{},\"steps\":[{\"name\":\"现场\",\"action\":\"SNAPSHOT\"}]}";
        var result=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/smart-app/scripts").contentType("application/json").content(payload)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        String id=new ObjectMapper().readTree(result.getResponse().getContentAsString()).path("id").asText();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/smart-app/scripts/"+id)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.name").value("snapshot"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/smart-app/scripts").contentType("application/json").content(payload.replace("SNAPSHOT","SHELL"))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/smart-app/scripts/"+UUID.randomUUID())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    }
    @Test void templatesAreValid(){for(Script s:new SmartAppController(service).templates())assertDoesNotThrow(()->SmartAppService.validate(s));}
    @Test void writeRequiresExplicitRunOption()throws Exception{Script s=save(List.of(click()));assertThrows(IllegalArgumentException.class,()->service.start(s.id(),new Start("phone",Map.of(),false)));verify(devices,never()).open(any());assertNull(locks.current("phone"));}
    @Test void sharedDeviceLockPreventsCompetingAutomation()throws Exception{Script s=save(List.of(new Step("现场","SNAPSHOT",null,null,null,null,null,null)));locks.tryAcquire("phone",DeviceLockService.Kind.TASK,"old-flow","原流程");assertThrows(IllegalStateException.class,()->service.start(s.id(),new Start("phone",Map.of(),false)));assertTrue(locks.isHeldBy("phone","old-flow"));verify(devices,never()).open(any());}
    @Test void failedPublishIsNotRetriedAndLockIsReleased()throws Exception{
        Script s=save(List.of(click()));var session=mock(SmartAppDevices.Session.class);when(devices.open(device)).thenReturn(session);when(session.source()).thenReturn("<root><node text='发布' bounds='[0,0][100,100]'/></root>");doThrow(new IllegalStateException("发送结果未知")).when(session).click(any());
        Run r=service.start(s.id(),new Start("phone",Map.of(),true));Run done=await(r.id());assertEquals("FAILED",done.status());assertTrue(done.events().get(0).message().contains("发送结果未知"));verify(session,times(1)).click(any());verify(session).close();assertNull(locks.current("phone"));
    }
    @Test void cancellationKeepsLockUntilCommandReturns()throws Exception{
        Script s=save(List.of(new Step("现场","SNAPSHOT",null,null,null,null,null,null)));var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var session=mock(SmartAppDevices.Session.class);when(devices.open(device)).thenAnswer(inv->{entered.countDown();release.await(5,TimeUnit.SECONDS);return session;});when(session.source()).thenReturn("<root/>");
        Run r=service.start(s.id(),new Start("phone",Map.of(),false));assertTrue(entered.await(2,TimeUnit.SECONDS));service.cancel(r.id());assertTrue(locks.isHeldBy("phone",r.id()));release.countDown();assertEquals("CANCELLED",await(r.id()).status());assertNull(locks.current("phone"));
    }
    @Test void cancellationDuringCleanupCannotOverwriteCompletedResult()throws Exception{
        Script s=save(List.of(new Step("现场","SNAPSHOT",null,null,null,null,null,null)));var closing=new CountDownLatch(1);var release=new CountDownLatch(1);var session=mock(SmartAppDevices.Session.class);
        when(devices.open(device)).thenReturn(session);when(session.source()).thenReturn("<root/>");doAnswer(inv->{closing.countDown();release.await(5,TimeUnit.SECONDS);return null;}).when(session).close();
        Run r=service.start(s.id(),new Start("phone",Map.of(),false));assertTrue(closing.await(2,TimeUnit.SECONDS));assertEquals("SUCCEEDED",service.cancel(r.id()).status());release.countDown();assertEquals("SUCCEEDED",await(r.id()).status());
    }
    @Test void noSecretExecutionVariablesPersistedInReport()throws Exception{
        Script s=save(List.of(new Step("读取页面","SNAPSHOT",null,null,null,null,null,null)));var session=mock(SmartAppDevices.Session.class);when(devices.open(device)).thenReturn(session);when(session.source()).thenReturn("<root/>");Run r=service.start(s.id(),new Start("phone",Map.of("phone","secret-number"),false));await(r.id());assertFalse(Files.readString(dir.resolve("runs").resolve(r.id()+".json")).contains("secret-number"));
    }
    @Test void driverErrorDoesNotExposeRuntimeCredentials(){assertEquals("Command value=****",SmartAppService.redact("Command value=fixed-otp",Map.of("code","fixed-otp")));}
    @Test void pathTraversalRejected() {assertThrows(IllegalArgumentException.class,()->service.script("../../outside"));}
    @Test void restartMarksUnfinishedRunInterrupted()throws Exception{
        String id=UUID.randomUUID().toString();new ObjectMapper().writeValue(dir.resolve("runs").resolve(id+".json").toFile(),new Run(id,"s","n","d","ANDROID","RUNNING","busy","2026-10-26T00:00:00Z",null,List.of()));
        var second=new SmartAppService(new ObjectMapper(),mapper,locks,devices,dir.toString());try{assertEquals("INTERRUPTED",second.run(id).status());}finally{second.stop();}
    }
    private Run await(String id)throws Exception{for(int i=0;i<300;i++){Run r=service.run(id);if(r.finishedAt()!=null)return r;Thread.sleep(10);}fail("execution did not finish");return null;}
}
