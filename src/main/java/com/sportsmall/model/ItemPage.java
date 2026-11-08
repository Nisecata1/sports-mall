package com.sportsmall.model;

import java.util.List;

/** HTTP分页结果，不是新表；total是筛选后且分页前的在售商品总数。 */
public record ItemPage(List<ItemView> items, long total, int page, int pageSize) {
    public ItemPage {
        items = List.copyOf(items);
        if (total < 0 || page < 1 || pageSize < 1 || pageSize > 100 || items.size() > pageSize) throw new IllegalArgumentException("无效分页结果");
    }
    public long totalPages() { return total / pageSize + (total % pageSize == 0 ? 0 : 1); }
    public boolean hasPrevious() { return page > 1; }
    public boolean hasNext() { return page < Integer.MAX_VALUE && page < totalPages(); }
}
