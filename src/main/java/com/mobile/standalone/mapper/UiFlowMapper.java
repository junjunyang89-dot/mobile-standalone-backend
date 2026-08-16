package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.UiFlow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UiFlowMapper {

    List<UiFlow> findByFilters(
            @Param("appId") String appId,
            @Param("platform") String platform,
            @Param("catalog") String catalog,
            @Param("componentOnly") Boolean componentOnly,
            @Param("environment") String environment,
            @Param("name") String name
    );

    /**
     * 批量执行的候选用例：启用、非组件、catalog 落在轨道内、环境匹配。
     * <p>
     * catalogs 由轨道给（回归 ONLINE/SHARED、自动化 AUTOMATION/SHARED），
     * environment 传空按 PROD——存量调用与加这两维之前完全一致。
     */
    List<UiFlow> findForBatch(
            @Param("appId") String appId,
            @Param("platform") String platform,
            @Param("environment") String environment,
            @Param("catalogs") List<String> catalogs,
            /** 已发布的线上包只跑 ONLINE 流程——它里面没有本版新功能 */
            @Param("onlineOnly") boolean onlineOnly
    );

    UiFlow findById(@Param("id") String id);

    int insert(UiFlow flow);

    int update(UiFlow flow);

    int deleteById(@Param("id") String id);
}
