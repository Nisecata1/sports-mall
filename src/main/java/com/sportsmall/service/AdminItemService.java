package com.sportsmall.service;

import com.sportsmall.dao.mysql.AdminItemDao;
import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.model.AdminItemView;
import com.sportsmall.model.ItemFields;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.UserAccount;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntFunction;

/** 商品基础信息维护；价格精度及范围在进入DAO前验证，不依赖数据库静默取整。 */
public final class AdminItemService {
    private final Function<AuthenticatedUser, UserAccount> accounts;
    private final IntFunction<List<AdminItemView>> items;
    private final ItemCreator creator;
    private final ItemUpdater updater;

    public AdminItemService(UserAccountService accounts, AdminItemDao items) {
        this(Objects.requireNonNull(accounts)::viewMyAccount, Objects.requireNonNull(items)::findAll,
                items::create, items::update);
    }

    AdminItemService(Function<AuthenticatedUser, UserAccount> accounts,
                     IntFunction<List<AdminItemView>> items, ItemCreator creator, ItemUpdater updater) {
        this.accounts = Objects.requireNonNull(accounts);
        this.items = Objects.requireNonNull(items);
        this.creator = Objects.requireNonNull(creator);
        this.updater = Objects.requireNonNull(updater);
    }

    public List<AdminItemView> listFor(AuthenticatedUser user) {
        requireAdministrator(user);
        return List.copyOf(items.apply(100));
    }

    public AdminItemView createFor(AuthenticatedUser user, String title, long categoryId, BigDecimal price, int status) {
        UserAccount actor = requireAdministrator(user);
        return creator.create(actor.userId(), validated(title, categoryId, price, status));
    }

    public AdminItemView updateFor(AuthenticatedUser user, long id, String title, long categoryId, BigDecimal price, int status) {
        UserAccount actor = requireAdministrator(user);
        if (id < 1) throw new MallBusinessException("INVALID_ITEM_ID", "商品编号须为正整数。");
        return updater.update(actor.userId(), id, validated(title, categoryId, price, status));
    }

    private UserAccount requireAdministrator(AuthenticatedUser user) {
        UserAccount account = accounts.apply(user);
        if (!"ADMIN".equals(account.role())) {
            throw new MallBusinessException("ADMIN_REQUIRED", "此操作仅限管理员账号。");
        }
        return account;
    }

    private static ItemFields validated(String inputTitle, long categoryId, BigDecimal inputPrice, int status) {
        String title = inputTitle == null ? "" : inputTitle.strip();
        if (title.isEmpty() || title.codePointCount(0, title.length()) > 200) {
            throw new MallBusinessException("INVALID_ITEM_TITLE", "商品名称须为1至200个字符。");
        }
        if (categoryId < 1) throw new MallBusinessException("INVALID_ITEM_CATEGORY", "请选择有效的商品分类。");
        if (inputPrice == null || inputPrice.signum() <= 0 || inputPrice.compareTo(new BigDecimal("99999999.99")) > 0) {
            throw new MallBusinessException("INVALID_ITEM_PRICE", "商品单价须大于0且不超过99999999.99元，最多两位小数。");
        }
        BigDecimal price;
        try { price = inputPrice.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException failure) {
            throw new MallBusinessException("INVALID_ITEM_PRICE", "商品单价最多保留两位小数，不会自动四舍五入。");
        }
        if (status != 0 && status != 1) throw new MallBusinessException("INVALID_ITEM_STATUS", "商品状态只能为0下架或1上架。");
        return new ItemFields(title, categoryId, price, status);
    }

    @FunctionalInterface interface ItemCreator { AdminItemView create(long actorId, ItemFields fields); }
    @FunctionalInterface interface ItemUpdater { AdminItemView update(long actorId, long id, ItemFields fields); }
}
