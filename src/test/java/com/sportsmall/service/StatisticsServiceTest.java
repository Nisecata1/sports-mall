package com.sportsmall.service;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.CommentStatistics;
import com.sportsmall.model.ItemView;
import com.sportsmall.model.UserAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class StatisticsServiceTest {
    private static final AuthenticatedUser CURRENT = new AuthenticatedUser(42L, "mall_test", "USER");
    private static final UserAccount ACCOUNT = new UserAccount(42L, "mall_test", "mall@example.test", null, "USER", 1);
    private static final ItemView ITEM = new ItemView(7L, "test item", new BigDecimal("100.00"), 1L, "test category");

    private static StatisticsService commentService(StatisticsService.AccountReader accounts,
                                                    StatisticsService.ItemReader items,
                                                    StatisticsService.CommentStatisticsReader comments) {
        return new StatisticsService(accounts, items, comments, (from, through, limit) -> fail("不应查询浏览排行"),
                java.time.Clock.systemUTC());
    }

    @Test
    void statisticsUseSelectedProductAfterCheckingCurrentAccount() {
        CommentStatistics stored = new CommentStatistics(7L, 3, 4.0, List.of(0L, 0L, 0L, 3L, 0L));
        StatisticsService service = commentService(current -> {
            assertSame(CURRENT, current);
            return ACCOUNT;
        }, item -> {
            assertEquals(7L, item);
            return Optional.of(ITEM);
        }, item -> {
            assertEquals(7L, item);
            return stored;
        });
        assertSame(stored, service.commentStatsFor(CURRENT, 7L));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void invalidProductIdNeverReachesDatabase(long itemId) {
        StatisticsService service = commentService(current -> fail("不应读取账号"),
                item -> fail("不应读取商品"), item -> fail("不应执行统计"));
        assertEquals("INVALID_COMMENT_ITEM", assertThrows(MallBusinessException.class,
                () -> service.commentStatsFor(CURRENT, itemId)).code());
    }

    @Test
    void disabledAccountCannotReachStatistics() {
        StatisticsService service = commentService(current -> {
            throw new MallBusinessException("ACCOUNT_DISABLED", "synthetic disabled account");
        }, item -> fail("不应读取商品"), item -> fail("不应执行统计"));
        assertEquals("ACCOUNT_DISABLED", assertThrows(MallBusinessException.class,
                () -> service.commentStatsFor(CURRENT, 7L)).code());
    }

    @Test
    void missingOrInactiveProductCannotReachStatistics() {
        StatisticsService service = commentService(current -> ACCOUNT, item -> Optional.empty(),
                item -> fail("不应执行统计"));
        assertEquals("COMMENT_ITEM_NOT_AVAILABLE", assertThrows(MallBusinessException.class,
                () -> service.commentStatsFor(CURRENT, 7L)).code());
    }

    @Test
    void queryFailureIsNotReportedAsZeroComments() {
        StatisticsService service = commentService(current -> ACCOUNT, item -> Optional.of(ITEM), item -> {
            throw new MallDataAccessException("COMMENT_STATISTICS_FAILED", "synthetic database failure");
        });
        assertEquals("COMMENT_STATISTICS_FAILED", assertThrows(MallDataAccessException.class,
                () -> service.commentStatsFor(CURRENT, 7L)).code());
    }

    @Test
    void productWithNoCommentsHasNoAverageInsteadOfZeroStars() {
        StatisticsService service = commentService(current -> ACCOUNT, item -> Optional.of(ITEM), CommentStatistics::empty);
        CommentStatistics empty = service.commentStatsFor(CURRENT, 7L);
        assertEquals(0, empty.commentCount());
        assertNull(empty.averageRating());
        assertEquals(List.of(0L, 0L, 0L, 0L, 0L), empty.ratingCounts());
    }

    @Test
    void mismatchedProductResultIsNotDisplayedAsTheSelectedProduct() {
        StatisticsService service = commentService(current -> ACCOUNT, item -> Optional.of(ITEM),
                item -> CommentStatistics.empty(99L));
        assertEquals("COMMENT_STATISTICS_DATA_INVALID", assertThrows(MallDataAccessException.class,
                () -> service.commentStatsFor(CURRENT, 7L)).code());
    }
}
