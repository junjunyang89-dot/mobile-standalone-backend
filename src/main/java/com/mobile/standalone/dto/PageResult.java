package com.mobile.standalone.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分页结果，用于任务列表等接口。
 *
 * @param <T> 当前页数据项类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {
    /** 当前页数据 */
    private List<T> content;
    /** 总记录数 */
    private long totalElements;
    /** 总页数 */
    private int totalPages;
    /** 当前页码（从 1 开始） */
    private int page;
    /** 每页条数 */
    private int size;
}
