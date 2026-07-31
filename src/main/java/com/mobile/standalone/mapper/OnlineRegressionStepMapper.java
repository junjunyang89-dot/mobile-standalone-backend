package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.OnlineRegressionStep;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OnlineRegressionStepMapper {
    List<OnlineRegressionStep> findByJobId(@Param("jobId") String jobId);

    List<OnlineRegressionStep> findByRunId(@Param("runId") String runId);

    OnlineRegressionStep findById(@Param("id") String id);

    int insert(OnlineRegressionStep step);

    int update(OnlineRegressionStep step);
}
