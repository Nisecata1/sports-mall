package com.sportsmall.service;

import com.sportsmall.dao.mysql.OrderDao;
import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.OrderFilter;
import com.sportsmall.model.OrderableItem;
import com.sportsmall.model.OrderView;
import com.sportsmall.model.UserAccount;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** 订单归属来自当前登录身份；金额由订单DAO在事务内从商品表读取。 */
public final class OrderService {
    private final CurrentAccountReader accounts;
    private final ItemReader items;
    private final OrderCreator creator;
    private final OrderReader reader;
    private final OrderCanceller canceller;

    public OrderService(UserAccountService accounts, OrderDao orders) {
        this(Objects.requireNonNull(accounts)::viewMyAccount, Objects.requireNonNull(orders)::findAvailableItems,
                orders::create, orders::findByUserId, orders::cancel);
    }

    OrderService(CurrentAccountReader accounts, ItemReader items, OrderCreator creator,
                 OrderReader reader, OrderCanceller canceller) {
        this.accounts = Objects.requireNonNull(accounts);
        this.items = Objects.requireNonNull(items);
        this.creator = Objects.requireNonNull(creator);
        this.reader = Objects.requireNonNull(reader);
        this.canceller = Objects.requireNonNull(canceller);
    }

    public List<OrderableItem> availableFor(AuthenticatedUser currentUser) {
        accounts.read(currentUser);
        return items.find();
    }

    public OrderView createFor(AuthenticatedUser currentUser, long itemId, BigDecimal displayedPrice) {
        if (itemId < 1 || displayedPrice == null || displayedPrice.signum() <= 0) {
            throw new MallBusinessException("INVALID_ORDER_ITEM", "请从可下单商品中选择一个商品。");
        }
        UserAccount account = accounts.read(currentUser);
        return creator.create(account.userId(), itemId, displayedPrice);
    }

    public List<OrderView> findMine(AuthenticatedUser currentUser) {
        return findMine(currentUser, OrderFilter.all());
    }

    public List<OrderView> findMine(AuthenticatedUser currentUser, OrderFilter filter) {
        Objects.requireNonNull(filter);
        UserAccount account = accounts.read(currentUser);
        return reader.find(account.userId(), filter);
    }

    public OrderView cancelMine(AuthenticatedUser currentUser, long orderId) {
        if (orderId < 1) {
            throw new MallBusinessException("INVALID_ORDER_ID", "请选择本人待处理订单的编号。");
        }
        UserAccount account = accounts.read(currentUser);
        return canceller.cancel(account.userId(), orderId);
    }

    @FunctionalInterface
    interface CurrentAccountReader {
        UserAccount read(AuthenticatedUser currentUser);
    }

    @FunctionalInterface
    interface ItemReader {
        List<OrderableItem> find();
    }

    @FunctionalInterface
    interface OrderCreator {
        OrderView create(long userId, long itemId, BigDecimal displayedPrice);
    }

    @FunctionalInterface
    interface OrderReader {
        List<OrderView> find(long userId, OrderFilter filter);
    }

    @FunctionalInterface
    interface OrderCanceller {
        OrderView cancel(long userId, long orderId);
    }
}
