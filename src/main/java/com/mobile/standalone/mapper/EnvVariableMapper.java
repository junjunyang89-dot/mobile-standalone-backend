package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.EnvVariable;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface EnvVariableMapper {

    List<EnvVariable> findByEnvironment(@Param("environment") String environment);

    EnvVariable findOne(@Param("environment") String environment, @Param("name") String name);

    int upsert(EnvVariable row);

    int delete(@Param("environment") String environment, @Param("name") String name);
}
