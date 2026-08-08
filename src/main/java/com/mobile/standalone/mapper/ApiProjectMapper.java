package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.ApiProject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ApiProjectMapper {
    List<ApiProject> findAll(@Param("enabledOnly") Boolean enabledOnly);

    ApiProject findByCode(@Param("code") String code);

    int insert(ApiProject row);

    int update(ApiProject row);

    int deleteByCode(@Param("code") String code);

    long countAll();
}
