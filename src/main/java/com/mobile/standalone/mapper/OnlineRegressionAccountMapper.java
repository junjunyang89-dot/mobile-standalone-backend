package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.OnlineRegressionAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OnlineRegressionAccountMapper {
    /**
     * @param environment 只取这个环境的（含未指定环境的通用账号）；传空则不筛
     */
    List<OnlineRegressionAccount> findEnabled(@Param("appId") String appId,
                                              @Param("environment") String environment);

    OnlineRegressionAccount findById(@Param("id") String id);

    int insert(OnlineRegressionAccount account);
}
