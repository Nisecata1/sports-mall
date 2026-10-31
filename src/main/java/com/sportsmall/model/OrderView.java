package com.sportsmall.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** amount来自orders的历史金额；商品改价不改变此值。 */
public record OrderView(long orderId, long userId, long itemId, String itemTitle,
                        BigDecimal amount, int status, LocalDateTime createdAt) {
}
