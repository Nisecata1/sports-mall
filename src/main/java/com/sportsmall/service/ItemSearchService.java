package com.sportsmall.service;

import com.sportsmall.dao.mysql.CategoryDao;
import com.sportsmall.dao.mysql.ItemSearchDao;
import com.sportsmall.model.*;
import com.sportsmall.util.CategoryTrees;
import java.util.*;
import java.util.function.*;

/** 面向已登录商城用户的只读商品查询；不开放管理员分类维护权限。 */
public final class ItemSearchService {
    private final Function<AuthenticatedUser,UserAccount> accounts;
    private final Function<ItemFilter,ItemPage> search;
    private final Supplier<List<CategoryView>> categories;
    public ItemSearchService(UserAccountService accounts,ItemSearchDao items,CategoryDao categories) {
        this(Objects.requireNonNull(accounts)::viewMyAccount,Objects.requireNonNull(items)::search,Objects.requireNonNull(categories)::findAll);
    }
    ItemSearchService(Function<AuthenticatedUser,UserAccount> accounts,Function<ItemFilter,ItemPage> search,Supplier<List<CategoryView>> categories) {
        this.accounts=Objects.requireNonNull(accounts);this.search=Objects.requireNonNull(search);this.categories=Objects.requireNonNull(categories);
    }
    public ItemPage searchFor(AuthenticatedUser user,ItemFilter filter) {
        accounts.apply(user);return search.apply(Objects.requireNonNull(filter));
    }
    public List<CategoryView> categoriesFor(AuthenticatedUser user) {
        accounts.apply(user);var result=List.copyOf(categories.get());CategoryTrees.validate(result);return result;
    }
}
