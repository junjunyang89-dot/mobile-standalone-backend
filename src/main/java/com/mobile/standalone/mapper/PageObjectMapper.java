package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.PageObject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PageObjectMapper {
    
    List<PageObject> findAll();
    
    PageObject findById(@Param("id") String id);
    
    List<PageObject> findByPlatform(@Param("platform") String platform);
    
    List<PageObject> findByAppId(@Param("appId") String appId);

    List<PageObject> findByAppIdAndPlatform(@Param("appId") String appId, @Param("platform") String platform);

    /**
     * 按目录 / 应用 / 平台筛选（任一条件可空）
     */
    List<PageObject> findByFilters(
            @Param("catalog") String catalog,
            @Param("appId") String appId,
            @Param("platform") String platform
    );

    PageObject findByPagePath(@Param("platform") String platform, @Param("appId") String appId, @Param("pagePath") String pagePath);
    
    int insert(PageObject pageObject);
    
    int update(PageObject pageObject);
    
    int deleteById(@Param("id") String id);
}
