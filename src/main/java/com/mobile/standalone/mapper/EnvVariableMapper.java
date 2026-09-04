package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.EnvVariable;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface EnvVariableMapper {

    /** 该环境下的全局变量（project=''），不含任何项目专属的 */
    List<EnvVariable> findByEnvironment(@Param("environment") String environment);

    /**
     * 全局 + 指定项目专属，项目专属排在后面。
     * <p>
     * 顺序是有意的：调用方按顺序放进 Map，后写入的覆盖先写入的，
     * 于是项目专属自然盖过全局同名变量。
     */
    List<EnvVariable> findByEnvironmentAndProject(@Param("environment") String environment,
                                                  @Param("project") String project);

    EnvVariable findOne(@Param("environment") String environment,
                        @Param("project") String project,
                        @Param("name") String name);

    int upsert(EnvVariable row);

    int delete(@Param("environment") String environment,
               @Param("project") String project,
               @Param("name") String name);
}
