package com.sportsmall.model;

import java.time.Instant;
import java.util.List;

/** 一条MongoDB评论的显示对象；引用MySQL编号，标签保持为数组。 */
public record CommentView(String commentId, long userId, long itemId, String content,
                          int rating, List<String> tags, Instant createdAt) {
    public CommentView {
        tags = List.copyOf(tags);
    }
}
