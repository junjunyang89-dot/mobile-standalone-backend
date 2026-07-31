package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.App;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AppMapper {
    
    /**
     * 根据设备ID查询应用列表
     */
    List<App> findByDeviceId(@Param("deviceId") String deviceId);
    
    /**
     * 根据ID查询应用
     */
    App findById(@Param("id") String id);
    
    /**
     * 插入应用
     */
    int insert(App app);
    
    /**
     * 更新应用
     */
    int update(App app);
    
    /**
     * 删除应用
     */
    int deleteById(@Param("id") String id);
    
    /**
     * 根据设备ID删除应用
     */
    int deleteByDeviceId(@Param("deviceId") String deviceId);
}
