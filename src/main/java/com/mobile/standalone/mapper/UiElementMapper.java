package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.UiElement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UiElementMapper {

    List<UiElement> findByFilters(
            @Param("appId") String appId,
            @Param("platform") String platform,
            @Param("catalog") String catalog,
            @Param("catalogs") List<String> catalogs,
            @Param("moduleTag") String moduleTag,
            @Param("keyword") String keyword
    );

    UiElement findById(@Param("id") String id);

    UiElement findByAppPlatformKey(
            @Param("appId") String appId,
            @Param("platform") String platform,
            @Param("nameEn") String nameEn
    );

    int insert(UiElement element);

    int update(UiElement element);

    int deleteById(@Param("id") String id);
}
