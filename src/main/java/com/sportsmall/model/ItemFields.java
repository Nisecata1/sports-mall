package com.sportsmall.model;

import java.math.BigDecimal;

/** 一次商品基础信息保存的字段；校验在业务层执行，DAO只接收已校验的值。 */
public record ItemFields(String title, long categoryId, BigDecimal price, int status) {}
