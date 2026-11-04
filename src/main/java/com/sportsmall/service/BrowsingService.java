package com.sportsmall.service;

import com.mongodb.MongoException;
import com.sportsmall.dao.ItemDao;
import com.sportsmall.dao.mongo.ActionLogDao;
import com.sportsmall.dao.mongo.ItemDetailDao;
import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.ActionLogView;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.ItemDetail;
import com.sportsmall.model.ItemView;
import com.sportsmall.model.UserAccount;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 先组合两库详情；浏览结束时为同一登录用户保存一次实际VIEW行为。 */
public final class BrowsingService {
    private final AccountReader accounts;
    private final ItemListReader itemList;
    private final ItemReader items;
    private final DetailReader details;
    private final ViewWriter writer;

    public BrowsingService(UserAccountService accounts, ItemDao items, ItemDetailDao details, ActionLogDao actions) {
        this(Objects.requireNonNull(accounts)::viewMyAccount, Objects.requireNonNull(items)::findActive,
                items::findActiveById, Objects.requireNonNull(details)::findByItemId, Objects.requireNonNull(actions)::recordView);
    }

    public static BrowsingService forDesktop(UserAccountService accounts, ItemDao items,
                                             ItemDetailDao details, ActionLogDao actions) {
        return new BrowsingService(Objects.requireNonNull(accounts)::viewMyAccount,
                Objects.requireNonNull(items)::findActive, items::findActiveById,
                Objects.requireNonNull(details)::findByItemId,
                Objects.requireNonNull(actions)::recordDesktopView);
    }

    BrowsingService(AccountReader accounts, ItemListReader itemList, ItemReader items, DetailReader details, ViewWriter writer) {
        this.accounts = Objects.requireNonNull(accounts);
        this.itemList = Objects.requireNonNull(itemList);
        this.items = Objects.requireNonNull(items);
        this.details = Objects.requireNonNull(details);
        this.writer = Objects.requireNonNull(writer);
    }

    public List<ItemView> availableFor(AuthenticatedUser user) {
        accounts.read(user);
        return itemList.find();
    }

    public OpenedItem openFor(AuthenticatedUser user, long itemId) {
        if (itemId < 1) {
            throw new MallBusinessException("INVALID_BROWSE_ITEM", "请选择列表中的商品编号。");
        }
        UserAccount account = accounts.read(user);
        ItemView item = items.find(itemId).orElseThrow(() ->
                new MallBusinessException("BROWSE_ITEM_NOT_AVAILABLE", "该商品不存在或已不在售，请重新选择。"));
        try {
            return new OpenedItem(account.userId(), item, details.find(itemId));
        } catch (MongoException exception) {
            throw MallDataAccessException.fromMongoFailure(exception);
        } catch (IllegalArgumentException exception) {
            throw new MallDataAccessException("MONGO_DATA_INVALID", "该商品详情字段不符合设计，请检查item_details。");
        }
    }

    public ActionLogView finishFor(AuthenticatedUser user, OpenedItem page, long durationSeconds) {
        if (page == null || durationSeconds < 0) {
            throw new MallBusinessException("INVALID_BROWSE_DURATION", "本次浏览信息或时长无效，请重新打开商品。");
        }
        UserAccount account = accounts.read(user);
        if (page.userId() != account.userId()) {
            throw new MallBusinessException("BROWSE_IDENTITY_MISMATCH", "本次浏览与当前登录身份不一致。");
        }
        // 商品在打开时已核对；后续下架不应抹去刚发生的浏览。
        return writer.record(account.userId(), page.item().itemId(), durationSeconds);
    }

    public record OpenedItem(long userId, ItemView item, Optional<ItemDetail> detail) {
        public OpenedItem {
            Objects.requireNonNull(item);
            Objects.requireNonNull(detail);
        }
    }

    @FunctionalInterface interface AccountReader { UserAccount read(AuthenticatedUser user); }
    @FunctionalInterface interface ItemListReader { List<ItemView> find(); }
    @FunctionalInterface interface ItemReader { Optional<ItemView> find(long itemId); }
    @FunctionalInterface interface DetailReader { Optional<ItemDetail> find(long itemId); }
    @FunctionalInterface interface ViewWriter { ActionLogView record(long userId, long itemId, long durationSeconds); }
}
