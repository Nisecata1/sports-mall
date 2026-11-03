package com.sportsmall.model;

/** 时间窗口内一件商品的浏览次数与累计停留秒数，由MongoDB聚合生成。 */
public record HotItemStatistics(long itemId, long viewCount, long durationSeconds) {
}
