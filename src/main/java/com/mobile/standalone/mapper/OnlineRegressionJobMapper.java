package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.OnlineRegressionJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OnlineRegressionJobMapper {
    List<OnlineRegressionJob> findByRunId(@Param("runId") String runId);

    OnlineRegressionJob findById(@Param("id") String id);

    int insert(OnlineRegressionJob job);

    int update(OnlineRegressionJob job);
}
