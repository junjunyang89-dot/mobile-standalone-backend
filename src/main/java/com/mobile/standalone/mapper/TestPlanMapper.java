package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.TestPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TestPlanMapper {
    
    List<TestPlan> findAll();
    
    TestPlan findById(@Param("id") String id);
    
    List<TestPlan> findByDeviceId(@Param("deviceId") String deviceId);
    
    List<TestPlan> findByAppId(@Param("appId") String appId);

    List<TestPlan> findByPlanType(@Param("planType") TestPlan.PlanType planType);
    
    List<TestPlan> findEnabledPlans();
    
    List<TestPlan> findPlansToRun(@Param("currentTime") LocalDateTime currentTime);
    
    int insert(TestPlan testPlan);
    
    int update(TestPlan testPlan);
    
    int deleteById(@Param("id") String id);
    
    int updateLastRunTime(@Param("id") String id, @Param("lastRunTime") LocalDateTime lastRunTime);
    
    int updateNextRunTime(@Param("id") String id, @Param("nextRunTime") LocalDateTime nextRunTime);
    
    int incrementRunCount(@Param("id") String id);
}
