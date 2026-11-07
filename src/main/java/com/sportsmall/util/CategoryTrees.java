package com.sportsmall.util;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.CategoryView;

import java.util.*;

/** 分类关系的纯计算；写入时必须传入同一事务锁定的记录。 */
public final class CategoryTrees {
    private CategoryTrees() {}

    public static void validate(List<CategoryView> rows) {
        Set<Long> ids = new HashSet<>();
        Map<Long, List<Long>> children = new HashMap<>();
        Queue<Long> pending = new ArrayDeque<>();
        for (CategoryView row : rows) {
            if (row.categoryId() < 1 || row.name() == null || !ids.add(row.categoryId())) throw invalidTree();
            if (row.parentId() == null) pending.add(row.categoryId());
            else children.computeIfAbsent(row.parentId(), ignored -> new ArrayList<>()).add(row.categoryId());
        }
        for (CategoryView row : rows) {
            if (row.parentId() != null && !ids.contains(row.parentId())) throw invalidTree();
        }
        int reachable = 0;
        while (!pending.isEmpty()) {
            long id = pending.remove();
            reachable++;
            pending.addAll(children.getOrDefault(id, List.of()));
        }
        if (reachable != rows.size()) throw invalidTree();
    }

    public static CategoryView requireCategory(List<CategoryView> rows, long id) {
        return rows.stream().filter(row -> row.categoryId() == id).findFirst()
                .orElseThrow(() -> new MallBusinessException("CATEGORY_NOT_FOUND", "所选分类已不存在，请刷新分类树。"));
    }

    public static void validateMove(List<CategoryView> rows, long id, Long parentId) {
        validate(rows);
        requireCategory(rows, id);
        Map<Long, CategoryView> byId = new HashMap<>();
        for (CategoryView row : rows) byId.put(row.categoryId(), row);
        Long ancestor = parentId;
        while (ancestor != null) {
            if (ancestor == id) {
                throw new MallBusinessException("CATEGORY_CYCLE", "上级不能选择分类自身或它的下级，否则会形成循环。" );
            }
            CategoryView parent = byId.get(ancestor);
            if (parent == null) {
                throw new MallBusinessException("CATEGORY_PARENT_NOT_FOUND", "所选上级分类已不存在，请刷新分类树。" );
            }
            ancestor = parent.parentId();
        }
    }

    private static MallDataAccessException invalidTree() {
        return new MallDataAccessException("CATEGORY_TREE_INVALID", "分类记录存在循环、缺失上级或无效编号，请检查categories表。");
    }
}
