package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.KnowledgeHandbook;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface KnowledgeHandbookMapper {

    int insert(KnowledgeHandbook row);

    int update(KnowledgeHandbook row);

    int deleteById(@Param("id") Long id);

    KnowledgeHandbook findById(@Param("id") Long id);

    List<KnowledgeHandbook> search(
            @Param("category") String category,
            @Param("project") String project,
            @Param("q") String q,
            @Param("limit") int limit
    );

    int countAll();
}
