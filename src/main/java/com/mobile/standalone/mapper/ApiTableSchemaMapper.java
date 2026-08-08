package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.ApiTableSchema;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ApiTableSchemaMapper {
    List<ApiTableSchema> findByProject(@Param("project") String project);

    ApiTableSchema findById(@Param("id") Long id);

    int insert(ApiTableSchema row);

    int update(ApiTableSchema row);

    int deleteById(@Param("id") Long id);
}
