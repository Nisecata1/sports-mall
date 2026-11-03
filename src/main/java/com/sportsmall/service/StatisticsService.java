package com.sportsmall.service;

import com.sportsmall.dao.ItemDao;
import com.sportsmall.dao.mongo.ActionLogDao;
import com.sportsmall.dao.mongo.CommentDao;
import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.CommentStatistics;
import com.sportsmall.model.HotItemStatistics;
import com.sportsmall.model.ItemView;
import com.sportsmall.model.UserAccount;
import com.sportsmall.model.UserActivityStatistics;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 业务层核对身份和商品，汇总计算交给MongoDB聚合管道。 */
public final class StatisticsService {
    private final AccountReader accounts;
    private final ItemReader items;
    private final CommentStatisticsReader comments;
    private final HotItemReader hotItems;
    private final UserActivityReader activities;
    private final Clock clock;

    public StatisticsService(UserAccountService accounts, ItemDao items, CommentDao comments, ActionLogDao actions) {
        this(Objects.requireNonNull(accounts)::viewMyAccount, Objects.requireNonNull(items)::findActiveById,
                Objects.requireNonNull(comments)::statisticsByItemId, Objects.requireNonNull(actions)::hotItems,
                actions::activityForUser, Clock.systemUTC());
    }

    StatisticsService(AccountReader accounts, ItemReader items, CommentStatisticsReader comments, HotItemReader hotItems,
                      UserActivityReader activities, Clock clock) {
        this.accounts = Objects.requireNonNull(accounts);
        this.items = Objects.requireNonNull(items);
        this.comments = Objects.requireNonNull(comments);
        this.hotItems = Objects.requireNonNull(hotItems);
        this.activities = Objects.requireNonNull(activities);
        this.clock = Objects.requireNonNull(clock);
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

    public HotReport hotItemsFor(AuthenticatedUser currentUser) {
        accounts.read(currentUser);
        Instant through = clock.instant();
        Instant from = through.minus(7, ChronoUnit.DAYS);
        List<RankedItem> ranked = hotItems.read(from, through, 10).stream()
                .map(row -> new RankedItem(row, items.find(row.itemId()))).toList();
        return new HotReport(from, through, ranked);
    }

    public UserActivityReport myActivityFor(AuthenticatedUser currentUser) {
        UserAccount account = accounts.read(currentUser);
        Instant through = clock.instant();
        Instant from = through.minus(7, ChronoUnit.DAYS);
        UserActivityStatistics summary = activities.read(account.userId(), from, through);
        if (summary.userId() != account.userId()) {
            throw new MallDataAccessException("USER_ACTIVITY_DATA_INVALID", "返回的用户报告与当前登录身份不一致。");
        }
        return new UserActivityReport(from, through, summary);
    }

    public record UserActivityReport(Instant fromInclusive, Instant throughInclusive, UserActivityStatistics summary) { }
    public record RankedItem(HotItemStatistics statistics, Optional<ItemView> item) { }
    public record HotReport(Instant fromInclusive, Instant throughInclusive, List<RankedItem> items) {
        public HotReport { items = List.copyOf(items); }
    }

    @FunctionalInterface
    interface AccountReader { UserAccount read(AuthenticatedUser currentUser); }
    @FunctionalInterface
    interface ItemReader { Optional<ItemView> find(long itemId); }
    @FunctionalInterface
    interface CommentStatisticsReader { CommentStatistics read(long itemId); }
    @FunctionalInterface
    interface HotItemReader { List<HotItemStatistics> read(Instant from, Instant through, int limit); }
    @FunctionalInterface
    interface UserActivityReader { UserActivityStatistics read(long userId, Instant from, Instant through); }
}
