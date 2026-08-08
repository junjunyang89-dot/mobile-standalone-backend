package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.UiPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UiPageMapper {

    List<UiPage> findByAppId(@Param("appId") String appId, @Param("enabledOnly") Boolean enabledOnly);

    UiPage findById(@Param("id") String id);

    UiPage findByAppAndPageId(@Param("appId") String appId, @Param("pageId") String pageId);

    int insert(UiPage page);

    int update(UiPage page);

    int deleteById(@Param("id") String id);
}
