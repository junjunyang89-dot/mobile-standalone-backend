package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.TestCase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TestCaseMapper {
    
    List<TestCase> findAll();
    
    TestCase findById(@Param("id") String id);
    
    List<TestCase> findByPlatform(@Param("platform") String platform);
    
    List<TestCase> findByAppId(@Param("appId") String appId);
    
    List<TestCase> findByCaseType(@Param("caseType") String caseType);
    
    int insert(TestCase testCase);
    
    int update(TestCase testCase);
    
    int deleteById(@Param("id") String id);
}
