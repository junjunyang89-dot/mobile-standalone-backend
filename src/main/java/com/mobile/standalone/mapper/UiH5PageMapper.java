package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.UiH5Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UiH5PageMapper {

    List<UiH5Page> findByFilters(
            @Param("appId") String appId,
            @Param("enabledOnly") Boolean enabledOnly
    );

    UiH5Page findById(@Param("id") String id);

    int insert(UiH5Page row);

    int update(UiH5Page row);

    int deleteById(@Param("id") String id);

    int countAll();
}
