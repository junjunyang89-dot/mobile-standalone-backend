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

    /** 扫描心跳：只刷探测到的字段，避免整行 UPDATE 拖长锁 */
    int updatePresence(Device device);
    
    int deleteById(@Param("id") String id);

    /** 记一次派活时间，设备池轮转用 */
    int markUsed(@Param("id") String id, @Param("usedAt") java.time.LocalDateTime usedAt);

    /** 人工停用 / 恢复 */
    int setDisabled(@Param("id") String id, @Param("disabled") boolean disabled);
}
