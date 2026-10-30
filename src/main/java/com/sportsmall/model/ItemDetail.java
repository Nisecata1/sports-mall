package com.sportsmall.model;

import java.util.List;

/** 商品的一份扩展详情；不包含 MySQL 管理的价格和分类。 */
public record ItemDetail(long itemId, String description, List<String> images, String language) {
    public ItemDetail {
        if (itemId < 1 || description == null || description.isBlank()
                || language == null || language.isBlank() || images == null) {
            throw new IllegalArgumentException("商品详情缺少有效的必填内容");
        }
        for (String image : images) {
            if (image == null || image.isBlank()) {
                throw new IllegalArgumentException("图片列表中的每一项必须是非空文字");
            }
        }
        images = List.copyOf(images);
    }
}
