package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.ApiTestEnv;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ApiTestEnvMapper {
    List<ApiTestEnv> findAll();

    ApiTestEnv findByEnvironment(@Param("environment") String environment);

    int upsert(ApiTestEnv row);

    long countAll();
}
