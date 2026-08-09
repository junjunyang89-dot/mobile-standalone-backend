package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.ApiTestSchedule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ApiTestScheduleMapper {

    List<ApiTestSchedule> findAll();

    ApiTestSchedule findById(@Param("id") String id);

    /** 到点该跑的 */
    List<ApiTestSchedule> findDue(@Param("now") LocalDateTime now);

    int insert(ApiTestSchedule row);

    int update(ApiTestSchedule row);

    int updateAfterRun(ApiTestSchedule row);

    int deleteById(@Param("id") String id);
}
