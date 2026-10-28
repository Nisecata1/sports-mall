package com.sportsmall.model;

import java.math.BigDecimal;

/** 一条“商品与分类关联查询”的结果，不是数据库中的新表。 */
public record ItemView(long itemId, String title, BigDecimal price, long categoryId, String categoryName) {
}
