package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.AgentKnowledge;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AgentKnowledgeMapper {

    List<AgentKnowledge> search(@Param("query") String query, @Param("limit") int limit);

    int insert(AgentKnowledge row);

    int countAll();
}
