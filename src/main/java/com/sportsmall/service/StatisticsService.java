package com.sportsmall.service;

import com.sportsmall.dao.ItemDao;
import com.sportsmall.dao.mongo.CommentDao;
import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.CommentStatistics;
import com.sportsmall.model.ItemView;
import com.sportsmall.model.UserAccount;

import java.util.Objects;
import java.util.Optional;

/** 业务层核对身份和商品，汇总计算交给MongoDB聚合管道。 */
public final class StatisticsService {
    private final AccountReader accounts;
    private final ItemReader items;
    private final CommentStatisticsReader comments;

    public StatisticsService(UserAccountService accounts, ItemDao items, CommentDao comments) {
        this(Objects.requireNonNull(accounts)::viewMyAccount, Objects.requireNonNull(items)::findActiveById,
                Objects.requireNonNull(comments)::statisticsByItemId);
    }

    StatisticsService(AccountReader accounts, ItemReader items, CommentStatisticsReader comments) {
        this.accounts = Objects.requireNonNull(accounts);
        this.items = Objects.requireNonNull(items);
        this.comments = Objects.requireNonNull(comments);
    }

    public CommentStatistics commentStatsFor(AuthenticatedUser currentUser, long itemId) {
        if (itemId < 1) {
            throw new MallBusinessException("INVALID_COMMENT_ITEM", "请选择列表中的商品编号。");
        }
        accounts.read(currentUser);
        if (items.find(itemId).isEmpty()) {
            throw new MallBusinessException("COMMENT_ITEM_NOT_AVAILABLE", "该商品不存在或已不在售，请重新选择商品。");
        }
        CommentStatistics result = comments.read(itemId);
        if (result.itemId() != itemId) {
            throw new MallDataAccessException("COMMENT_STATISTICS_DATA_INVALID", "返回的统计商品编号与本次选择不一致。");
        }
        return result;
    }

    @FunctionalInterface
    interface AccountReader { UserAccount read(AuthenticatedUser currentUser); }
    @FunctionalInterface
    interface ItemReader { Optional<ItemView> find(long itemId); }
    @FunctionalInterface
    interface CommentStatisticsReader { CommentStatistics read(long itemId); }
}
