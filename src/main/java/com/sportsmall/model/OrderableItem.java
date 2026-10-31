package com.sportsmall.model;

import java.math.BigDecimal;

/** 下单前展示的商品和报价；最终价格在事务内重新读取并核对。 */
public record OrderableItem(long itemId, String title, BigDecimal price) {
}
