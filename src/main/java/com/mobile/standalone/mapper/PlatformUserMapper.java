package com.mobile.standalone.mapper;

import com.mobile.standalone.auth.PlatformUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PlatformUserMapper {

    List<PlatformUser> findAll();

    PlatformUser findById(@Param("id") String id);

    PlatformUser findByUsername(@Param("username") String username);

    int countAll();

    /** 还有几个「启用中的管理员」——用来挡住把最后一个管理员停用或降级 */
    int countActiveAdmins();

    int insert(PlatformUser user);

    int update(PlatformUser user);

    int updatePassword(@Param("id") String id, @Param("passwordHash") String passwordHash);

    int touchLogin(@Param("id") String id);

    int deleteById(@Param("id") String id);
}
