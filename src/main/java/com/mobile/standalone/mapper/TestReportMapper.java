package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.TestReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TestReportMapper {
    
    List<TestReport> findAll();
    
    TestReport findById(@Param("id") String id);
    
    List<TestReport> findByPlanId(@Param("planId") String planId);
    
    List<TestReport> findByCaseId(@Param("caseId") String caseId);
    
    List<TestReport> findByDeviceId(@Param("deviceId") String deviceId);
    
    List<TestReport> findByExecutionId(@Param("executionId") String executionId);
    
    int insert(TestReport testReport);
    
    int update(TestReport testReport);
    
    int deleteById(@Param("id") String id);
}
