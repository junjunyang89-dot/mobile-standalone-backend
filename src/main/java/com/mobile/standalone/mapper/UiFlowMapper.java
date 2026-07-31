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
            @Param("componentOnly") Boolean componentOnly
    );

    UiFlow findById(@Param("id") String id);

    int insert(UiFlow flow);

    int update(UiFlow flow);

    int deleteById(@Param("id") String id);
}
