package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.DebugApiTest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DebugApiTestMapper {

    int insert(DebugApiTest row);

    int update(DebugApiTest row);

    int deleteById(@Param("id") Long id);

    DebugApiTest findById(@Param("id") Long id);

    DebugApiTest findByName(@Param("name") String name);

    List<DebugApiTest> search(
            @Param("status") String status,
            @Param("keyword") String keyword,
            @Param("limit") int limit
    );

    int countAll();
}
