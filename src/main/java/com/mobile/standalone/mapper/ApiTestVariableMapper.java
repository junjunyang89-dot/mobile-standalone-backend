package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.ApiTestVariable;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ApiTestVariableMapper {

    List<ApiTestVariable> findByEnvironment(@Param("environment") String environment);

    ApiTestVariable findOne(@Param("environment") String environment, @Param("name") String name);

    int upsert(ApiTestVariable row);

    int delete(@Param("environment") String environment, @Param("name") String name);
}
