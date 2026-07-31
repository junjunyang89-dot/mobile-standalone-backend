package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.OnlineRegressionAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OnlineRegressionAccountMapper {
    List<OnlineRegressionAccount> findEnabled(@Param("appId") String appId);

    OnlineRegressionAccount findById(@Param("id") String id);

    int insert(OnlineRegressionAccount account);
}
