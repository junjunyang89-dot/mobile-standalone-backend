package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.ApiChangeReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ApiChangeReportMapper {

    int insert(ApiChangeReport row);

    ApiChangeReport findById(@Param("id") Long id);

    List<ApiChangeReport> search(
            @Param("project") String project,
            @Param("limit") int limit
    );

    int deleteById(@Param("id") Long id);
}
