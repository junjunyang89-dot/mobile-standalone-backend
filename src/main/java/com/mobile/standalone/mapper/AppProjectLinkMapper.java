package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.AppProjectLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface AppProjectLinkMapper {

    /** 两个条件都可为空，空则不过滤 */
    List<AppProjectLink> findByFilters(@Param("appId") String appId,
                                       @Param("projectCode") String projectCode);

    AppProjectLink findOne(@Param("appId") String appId,
                           @Param("projectCode") String projectCode);

    int insert(AppProjectLink row);

    int update(AppProjectLink row);

    int delete(@Param("appId") String appId, @Param("projectCode") String projectCode);

    /**
     * 沿关联统计各项目的 UI 资产量，供树节点显示「这个项目测了多少」。
     * <p>
     * 元素按 platform 过滤（关联的 platform 为空时不过滤）；流程与页面在
     * {@code ui_flows} / {@code ui_pages} 上没有端的维度，只能给到 App 级的数，
     * 因此同一个 App 的两个端会看到相同的流程数——这是数据本身的粒度，不是重复计算。
     */
    List<Map<String, Object>> countAssetsByProject(@Param("projectCode") String projectCode);

    /** 还没挂上任何项目的 App */
    List<Map<String, Object>> findUnlinkedApps();
}
