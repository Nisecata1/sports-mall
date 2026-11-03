package com.sportsmall.model;

import java.util.List;

/** 对一个商品全部评论的汇总；无评论时平均分为null，评分分布按1至5分排列。 */
public record CommentStatistics(long itemId, long commentCount, Double averageRating, List<Long> ratingCounts) {
    public CommentStatistics {
        ratingCounts = List.copyOf(ratingCounts);
    }

    public static CommentStatistics empty(long itemId) {
        return new CommentStatistics(itemId, 0, null, List.of(0L, 0L, 0L, 0L, 0L));
    }
}
