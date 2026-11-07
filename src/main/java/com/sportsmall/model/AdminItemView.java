package com.sportsmall.model;

import java.math.BigDecimal;

/** 管理员商品列表包含上下架状态；不会改变普通用户的在售查询契约。 */
public record AdminItemView(long itemId, String title, long categoryId, String categoryName,
                            BigDecimal price, int status) {}
