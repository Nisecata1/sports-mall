package com.sportsmall.service;

import com.sportsmall.dao.mysql.CategoryDao;
import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.CategoryView;
import com.sportsmall.model.UserAccount;

import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/** 管理员新增分类；读取时验证既有父子关系可组成树，避免把异常数据画成正常树。 */
public final class CategoryService {
    private final Function<AuthenticatedUser, UserAccount> accounts;
    private final Supplier<List<CategoryView>> categories;
    private final CategoryWriter writer;

    public CategoryService(UserAccountService accounts, CategoryDao categories) {
        this(Objects.requireNonNull(accounts)::viewMyAccount, Objects.requireNonNull(categories)::findAll,
                categories::create);
    }

    CategoryService(Function<AuthenticatedUser, UserAccount> accounts,
                    Supplier<List<CategoryView>> categories, CategoryWriter writer) {
        this.accounts = Objects.requireNonNull(accounts);
        this.categories = Objects.requireNonNull(categories);
        this.writer = Objects.requireNonNull(writer);
    }

    public List<CategoryView> treeFor(AuthenticatedUser currentUser) {
        requireAdministrator(currentUser);
        List<CategoryView> rows = List.copyOf(categories.get());
        validateTree(rows);
        return rows;
    }

    public CategoryView createFor(AuthenticatedUser currentUser, String inputName, Long parentId) {
        UserAccount account = requireAdministrator(currentUser);
        String name = inputName == null ? "" : inputName.strip();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 50) {
            throw new MallBusinessException("INVALID_CATEGORY_NAME", "分类名称须为1至50个字符。");
        }
        if (parentId != null && parentId < 1) {
            throw new MallBusinessException("INVALID_CATEGORY_PARENT", "上级分类编号须为正整数，根分类不指定上级。");
        }
        return writer.create(account.userId(), name, parentId);
    }

    private UserAccount requireAdministrator(AuthenticatedUser currentUser) {
        UserAccount account = accounts.apply(currentUser);
        if (!"ADMIN".equals(account.role())) {
            throw new MallBusinessException("ADMIN_REQUIRED", "此操作仅限管理员账号。");
        }
        return account;
    }

    private static void validateTree(List<CategoryView> rows) {
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

    private static MallDataAccessException invalidTree() {
        return new MallDataAccessException("CATEGORY_TREE_INVALID", "分类记录存在循环、缺失上级或无效编号，请检查categories表。");
    }

    @FunctionalInterface
    interface CategoryWriter {
        CategoryView create(long actorId, String name, Long parentId);
    }
}
