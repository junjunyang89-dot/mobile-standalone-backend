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
            @Param("pageId") String pageId,
            @Param("source") String source,
            @Param("status") String status,
            @Param("keyword") String keyword,
            @Param("offset") Integer offset,
            @Param("limit") Integer limit
    );

    long countByFilters(
            @Param("appId") String appId,
            @Param("platform") String platform,
            @Param("catalog") String catalog,
            @Param("catalogs") List<String> catalogs,
            @Param("moduleTag") String moduleTag,
            @Param("pageId") String pageId,
            @Param("source") String source,
            @Param("status") String status,
            @Param("keyword") String keyword
    );

    /**
     * 某个页面下，各平台各有多少元素。
     * <p>
     * 页面是<b>按应用</b>建的、不分平台，元素才分平台。所以「Android 下看着是空的」
     * 完全可能同时「iOS 下还有 2 个」——删除时必须能说清楚元素到底在哪一端，
     * 否则人只会觉得系统在瞎报。
     */
    List<java.util.Map<String, Object>> countByPageGroupByPlatform(
            @Param("appId") String appId, @Param("pageId") String pageId);

    UiElement findById(@Param("id") String id);

    UiElement findByAppPlatformKey(
            @Param("appId") String appId,
            @Param("platform") String platform,
            @Param("nameEn") String nameEn,
            @Param("source") String source
    );

    /** 执行用：优先 ACTIVE，同名多源时 HUMAN_AI 优先 */
    UiElement findActiveByKey(
            @Param("appId") String appId,
            @Param("platform") String platform,
            @Param("nameEn") String nameEn
    );

    int insert(UiElement element);

    int update(UiElement element);

    int deleteById(@Param("id") String id);

    /** 跨页面去重：同应用同平台同定位类型+值 */
    UiElement findByExactLocator(
            @Param("appId") String appId,
            @Param("platform") String platform,
            @Param("locatorType") String locatorType,
            @Param("locatorValue") String locatorValue
    );

    /** 点选命中：按 locator 精确/模糊匹配已有元素 */
    List<UiElement> findByLocatorHint(
            @Param("appId") String appId,
            @Param("platform") String platform,
            @Param("locatorValue") String locatorValue,
            @Param("shortId") String shortId,
            @Param("limit") int limit
    );
}
