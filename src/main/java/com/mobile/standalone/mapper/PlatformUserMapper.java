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

    PlatformUser findByEmail(@Param("email") String email);

    int countAll();

    /** 还有几个「启用中的管理员」——用来挡住把最后一个管理员停用或降级 */
    int countActiveAdmins();

    int insert(PlatformUser user);

    int update(PlatformUser user);

    /** 改密码同时记 password_changed_at：两件事必须一起发生，分开写迟早有一条路径漏掉 */
    int updatePassword(@Param("id") String id, @Param("passwordHash") String passwordHash);

    int updateEmail(@Param("id") String id, @Param("email") String email);

    /** 单独一条：要能写回 NULL（= 不限制），通用 update 里 null 的意思是「这个字段不动」 */
    int updateAllowedModules(@Param("id") String id, @Param("allowedModules") String allowedModules);

    int setMustChangePassword(@Param("id") String id, @Param("must") boolean must);

    int touchLogin(@Param("id") String id);

    int deleteById(@Param("id") String id);
}
