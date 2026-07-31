package com.mobile.standalone.mapper;

import com.mobile.standalone.entity.UiKeyword;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UiKeywordMapper {

    List<UiKeyword> findAll(@Param("category") String category, @Param("enabledOnly") Boolean enabledOnly);

    UiKeyword findById(@Param("id") String id);

    UiKeyword findByCode(@Param("code") String code);

    int insert(UiKeyword keyword);

    int update(UiKeyword keyword);

    int deleteById(@Param("id") String id);
}
