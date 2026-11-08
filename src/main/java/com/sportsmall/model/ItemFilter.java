package com.sportsmall.model;

import com.sportsmall.exception.MallBusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/** 在售商品筛选：条件取交集，分类精确匹配；页码从1开始。 */
public record ItemFilter(String keyword, Long categoryId, BigDecimal minPrice, BigDecimal maxPrice,
                         String sort, int page, int pageSize) {
    public ItemFilter {
        keyword = keyword == null || keyword.isBlank() ? null : keyword.strip();
        if (keyword != null && keyword.codePointCount(0, keyword.length()) > 200) throw invalid("名称关键词最多200个字符。");
        if (categoryId != null && categoryId < 1) throw invalid("分类编号须为正整数。");
        minPrice = price(minPrice); maxPrice = price(maxPrice);
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) throw invalid("最低价不能高于最高价。");
        sort = sort == null || sort.isBlank() ? "id" : sort;
        if (!Set.of("id", "price_asc", "price_desc").contains(sort)) throw invalid("请选择编号顺序、价格升序或价格降序。");
        if (page < 1 || pageSize < 1 || pageSize > 100) throw invalid("页码须大于0，每页须为1至100条。");
    }
    public static ItemFilter defaults() { return new ItemFilter(null,null,null,null,"id",1,10); }
    public ItemFilter withPage(int value) { return new ItemFilter(keyword,categoryId,minPrice,maxPrice,sort,value,pageSize); }
    public long offset() { return (page - 1L) * pageSize; }
    public String keywordPattern() {
        return keyword == null ? null : "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
    private static BigDecimal price(BigDecimal value) {
        if (value == null) return null;
        if (value.signum() < 0 || value.compareTo(new BigDecimal("99999999.99")) > 0) throw invalid("筛选价格须在0至99999999.99元之间。");
        try { return value.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException failure) { throw invalid("筛选价格最多两位小数。"); }
    }
    private static MallBusinessException invalid(String message) { return new MallBusinessException("INVALID_ITEM_FILTER",message); }
}
