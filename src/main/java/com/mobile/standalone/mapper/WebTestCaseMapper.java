package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.WebTestCase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WebTestCaseMapper {
    List<WebTestCase> findByFilters(
            @Param("channel") String channel,
            @Param("enabledOnly") Boolean enabledOnly,
            @Param("keyword") String keyword
    );

    WebTestCase findById(@Param("id") String id);

    int insert(WebTestCase row);

    int update(WebTestCase row);

    int deleteById(@Param("id") String id);

    long countAll();
}
