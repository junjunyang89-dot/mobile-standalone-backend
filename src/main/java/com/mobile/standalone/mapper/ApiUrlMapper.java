package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.ApiUrlEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ApiUrlMapper {
    List<ApiUrlEntry> findByFilters(
            @Param("project") String project,
            @Param("environment") String environment,
            @Param("enabledOnly") Boolean enabledOnly
    );

    ApiUrlEntry findById(@Param("id") String id);

    int insert(ApiUrlEntry row);

    int update(ApiUrlEntry row);

    int deleteById(@Param("id") String id);

    long countByProject(@Param("project") String project);
}
