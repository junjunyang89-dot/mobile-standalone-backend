package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.Device;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DeviceMapper {
    
    List<Device> findAll();
    
    Device findById(@Param("id") String id);
    
    List<Device> findByStatus(@Param("status") String status);
    
    int insert(Device device);
    
    int update(Device device);
    
    int deleteById(@Param("id") String id);
}
