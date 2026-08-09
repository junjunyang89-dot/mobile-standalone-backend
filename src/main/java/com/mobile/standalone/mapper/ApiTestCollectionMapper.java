package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.ApiTestCollection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ApiTestCollectionMapper {

    List<ApiTestCollection> findAll(@Param("project") String project);

    ApiTestCollection findById(@Param("id") String id);

    ApiTestCollection findByName(@Param("project") String project, @Param("name") String name);

    int insert(ApiTestCollection row);

    int update(ApiTestCollection row);

    int deleteById(@Param("id") String id);

    /** 把用例挂到集合上；collectionId 传 null 即移出 */
    int assignCases(@Param("collectionId") String collectionId, @Param("caseIds") List<String> caseIds);

    /** 集合内的用例 id，按执行顺序 */
    List<String> caseIdsOf(@Param("collectionId") String collectionId, @Param("enabledOnly") boolean enabledOnly);
}
