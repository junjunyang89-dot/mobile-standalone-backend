package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.OnlineRegressionRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OnlineRegressionRunMapper {
    OnlineRegressionRun findById(@Param("id") String id);

    /** 历史批次列表，按结束/创建时间倒序 */
    List<OnlineRegressionRun> findRecent(@Param("limit") int limit);

    int insert(OnlineRegressionRun run);

    int update(OnlineRegressionRun run);

    /** 清空全部历史批次（jobs/steps 靠外键 CASCADE） */
    int deleteAll();
}
