package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.AgentConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentConversationMapper {

    AgentConversation findBySessionId(@Param("sessionId") String sessionId);

    int upsert(AgentConversation row);
}
