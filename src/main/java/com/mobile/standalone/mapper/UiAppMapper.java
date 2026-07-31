package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.UiApp;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UiAppMapper {

    List<UiApp> findAll(@Param("enabledOnly") Boolean enabledOnly);

    UiApp findById(@Param("id") String id);

    int insert(UiApp app);

    int update(UiApp app);

    int deleteById(@Param("id") String id);
}
