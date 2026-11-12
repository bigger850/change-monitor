package com.biggerink.watch.common;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 分页响应 {total, page, size, list}
 */
@Data
@AllArgsConstructor
public class PageResult<T> {

    private long total;
    private long page;
    private long size;
    private List<T> list;

    public static <T> PageResult<T> of(long total, long page, long size, List<T> list) {
        return new PageResult<>(total, page, size, list);
    }
}
