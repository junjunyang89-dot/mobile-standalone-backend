package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.Application;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ApplicationMapper {
    
    List<Application> findAll();
    
    Application findById(@Param("id") String id);
    
    List<Application> findByPlatform(@Param("platform") String platform);
    
    List<Application> findByDeviceId(@Param("deviceId") String deviceId);
    
    int insert(Application application);
    
    int update(Application application);
    
    int deleteById(@Param("id") String id);

    /** 按设备删除该设备下所有应用（用于刷新应用列表前清空缓存） */
    int deleteByDeviceId(@Param("deviceId") String deviceId);
}
