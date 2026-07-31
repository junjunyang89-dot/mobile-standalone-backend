package com.mobile.standalone.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.entity.TestCase;
import com.mobile.standalone.mapper.TestCaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 测试用例服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TestCaseService {
    
    private final TestCaseMapper testCaseMapper;
    
    public List<TestCase> getAllCases() {
        return testCaseMapper.findAll();
    }
    
    public TestCase getCaseById(String id) {
        return testCaseMapper.findById(id);
    }
    
    public List<TestCase> getCasesByPlatform(TestCase.Platform platform) {
        return testCaseMapper.findByPlatform(platform != null ? platform.name() : null);
    }
    
    public List<TestCase> getCasesByAppId(String appId) {
        return testCaseMapper.findByAppId(appId);
    }
    
    public List<TestCase> getCasesByCaseType(TestCase.CaseType caseType) {
        return testCaseMapper.findByCaseType(caseType != null ? caseType.name() : null);
    }
    
    @Transactional
    public TestCase createCase(TestCase testCase) {
        if (testCase.getId() == null) {
            testCase.setId(UUID.randomUUID().toString());
        }
        testCaseMapper.insert(testCase);
        return testCase;
    }
    
    @Transactional
    public TestCase updateCase(String id, TestCase testCaseData) {
        TestCase testCase = testCaseMapper.findById(id);
        if (testCase != null) {
            if (testCaseData.getName() != null) testCase.setName(testCaseData.getName());
            if (testCaseData.getDescription() != null) testCase.setDescription(testCaseData.getDescription());
            if (testCaseData.getPlatform() != null) testCase.setPlatform(testCaseData.getPlatform());
            if (testCaseData.getAppId() != null) testCase.setAppId(testCaseData.getAppId());
            if (testCaseData.getCaseType() != null) testCase.setCaseType(testCaseData.getCaseType());
            if (testCaseData.getSteps() != null) testCase.setSteps(testCaseData.getSteps());
            if (testCaseData.getExpectedResult() != null) testCase.setExpectedResult(testCaseData.getExpectedResult());
            if (testCaseData.getPriority() != null) testCase.setPriority(testCaseData.getPriority());
            if (testCaseData.getStatus() != null) testCase.setStatus(testCaseData.getStatus());
            
            testCaseMapper.update(testCase);
            return testCase;
        }
        return null;
    }
    
    @Transactional
    public boolean deleteCase(String id) {
        TestCase testCase = testCaseMapper.findById(id);
        if (testCase != null) {
            testCaseMapper.deleteById(id);
            return true;
        }
        return false;
    }
}
