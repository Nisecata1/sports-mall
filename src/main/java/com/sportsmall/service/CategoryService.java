package com.sportsmall.service;

import com.sportsmall.dao.mysql.CategoryDao;
import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.util.CategoryTrees;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.CategoryView;
import com.sportsmall.model.UserAccount;

import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/** 管理员分类维护；输入与权限在这里校验，防环及引用检查在写事务内执行。 */
public final class CategoryService {
    private final Function<AuthenticatedUser, UserAccount> accounts;
    private final Supplier<List<CategoryView>> categories;
    private final CategoryWriter writer;
    private final CategoryUpdater updater;
    private final CategoryDeleter deleter;

    public CategoryService(UserAccountService accounts, CategoryDao categories) {
        this(Objects.requireNonNull(accounts)::viewMyAccount, Objects.requireNonNull(categories)::findAll,
                categories::create, categories::update, categories::delete);
    }

    CategoryService(Function<AuthenticatedUser, UserAccount> accounts,
                    Supplier<List<CategoryView>> categories, CategoryWriter writer) {
        this(accounts, categories, writer,
                (actor, id, name, parent) -> { throw new UnsupportedOperationException("No update callback"); },
                (actor, id) -> { throw new UnsupportedOperationException("No delete callback"); });
    }

    CategoryService(Function<AuthenticatedUser, UserAccount> accounts,
                    Supplier<List<CategoryView>> categories, CategoryWriter writer,
                    CategoryUpdater updater, CategoryDeleter deleter) {
        this.accounts = Objects.requireNonNull(accounts);
        this.categories = Objects.requireNonNull(categories);
        this.writer = Objects.requireNonNull(writer);
        this.updater = Objects.requireNonNull(updater);
        this.deleter = Objects.requireNonNull(deleter);
    }

    public List<CategoryView> treeFor(AuthenticatedUser currentUser) {
        requireAdministrator(currentUser);
        List<CategoryView> rows = List.copyOf(categories.get());
        CategoryTrees.validate(rows);
        return rows;
    }

    public CategoryView createFor(AuthenticatedUser currentUser, String inputName, Long parentId) {
        UserAccount account = requireAdministrator(currentUser);
        String name = validatedName(inputName);
        validateParentId(parentId);
        return writer.create(account.userId(), name, parentId);
    }

    public CategoryView updateFor(AuthenticatedUser currentUser, long id, String inputName, Long parentId) {
        UserAccount account = requireAdministrator(currentUser);
        validateId(id);
        String name = validatedName(inputName);
        validateParentId(parentId);
        return updater.update(account.userId(), id, name, parentId);
    }

    public boolean deleteFor(AuthenticatedUser currentUser, long id) {
        UserAccount account = requireAdministrator(currentUser);
        validateId(id);
        return deleter.delete(account.userId(), id);
    }

    private static String validatedName(String inputName) {
        String name = inputName == null ? "" : inputName.strip();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 50) {
            throw new MallBusinessException("INVALID_CATEGORY_NAME", "分类名称须为1至50个字符。");
        }
        return name;
    }

    private static void validateId(long id) {
        if (id < 1) throw new MallBusinessException("INVALID_CATEGORY_ID", "分类编号须为正整数。");
    }

    private static void validateParentId(Long parentId) {
        if (parentId != null && parentId < 1) {
            throw new MallBusinessException("INVALID_CATEGORY_PARENT", "上级分类编号须为正整数，根分类不指定上级。");
        }
    }

    private UserAccount requireAdministrator(AuthenticatedUser currentUser) {
        UserAccount account = accounts.apply(currentUser);
        if (!"ADMIN".equals(account.role())) {
            throw new MallBusinessException("ADMIN_REQUIRED", "此操作仅限管理员账号。");
        }
        return account;
    }

    @FunctionalInterface
    interface CategoryWriter {
        CategoryView create(long actorId, String name, Long parentId);
    }

    @FunctionalInterface
    interface CategoryUpdater {
        CategoryView update(long actorId, long id, String name, Long parentId);
    }

    @FunctionalInterface
    interface CategoryDeleter {
        boolean delete(long actorId, long id);
    }
}
