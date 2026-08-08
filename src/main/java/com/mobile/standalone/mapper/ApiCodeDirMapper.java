package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.ApiCodeDir;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ApiCodeDirMapper {
    List<ApiCodeDir> findByProject(@Param("project") String project);

    ApiCodeDir findById(@Param("id") Long id);

    int insert(ApiCodeDir row);

    int update(ApiCodeDir row);

    int deleteById(@Param("id") Long id);
}
