package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.EngineeringMemory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface EngineeringMemoryMapper {

    int insert(EngineeringMemory row);

    EngineeringMemory findById(@Param("id") Long id);

    List<EngineeringMemory> search(
            @Param("memType") String memType,
            @Param("tag") String tag,
            @Param("q") String q,
            @Param("limit") int limit
    );

    int countAll();
}
