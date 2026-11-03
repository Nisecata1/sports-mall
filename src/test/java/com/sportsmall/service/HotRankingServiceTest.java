package com.sportsmall.service;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class HotRankingServiceTest {
    private static final AuthenticatedUser USER = new AuthenticatedUser(42, "test", "USER");
    private static final UserAccount ACCOUNT = new UserAccount(42, "test", "test@example.test", null, "USER", 1);
    private static final Instant NOW = Instant.parse("2026-11-03T05:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void rankingUsesSevenDayWindowAndKeepsUnavailableHistoricalProductIds() {
        StatisticsService service = new StatisticsService(current -> ACCOUNT, id -> id == 7
                ? Optional.of(new ItemView(7, "test item", BigDecimal.ONE, 1, "category")) : Optional.empty(),
                id -> fail("不应统计评论"), (from, through, limit) -> {
                    assertEquals(Instant.parse("2026-10-27T05:00:00Z"), from);
                    assertEquals(NOW, through); assertEquals(10, limit);
                    return List.of(new HotItemStatistics(7, 3, 20), new HotItemStatistics(8, 1, 0));
                }, (userId, from, through) -> fail("不应读取用户报告"), CLOCK);
        StatisticsService.HotReport report = service.hotItemsFor(USER);
        assertEquals(2, report.items().size());
        assertEquals("test item", report.items().get(0).item().orElseThrow().title());
        assertEquals(8, report.items().get(1).statistics().itemId());
        assertTrue(report.items().get(1).item().isEmpty());
    }

    @Test
    void emptyRankingRemainsAnOrdinaryEmptyReport() {
        StatisticsService service = new StatisticsService(current -> ACCOUNT, id -> fail("空排行不应读商品"),
                id -> fail("不应统计评论"), (from, through, limit) -> List.of(), (userId, from, through) -> fail("不应读取用户报告"), CLOCK);
        assertTrue(service.hotItemsFor(USER).items().isEmpty());
    }

    @Test
    void rankingFailureIsNotTurnedIntoNoVisits() {
        StatisticsService service = new StatisticsService(current -> ACCOUNT, id -> fail("不应读商品"),
                id -> fail("不应统计评论"), (from, through, limit) -> {
                    throw new MallDataAccessException("HOT_RANKING_FAILED", "synthetic query failure");
                }, (userId, from, through) -> fail("不应读取用户报告"), CLOCK);
        assertEquals("HOT_RANKING_FAILED", assertThrows(MallDataAccessException.class, () -> service.hotItemsFor(USER)).code());
    }

    @Test
    void disabledUserCannotQueryRanking() {
        StatisticsService service = new StatisticsService(current -> {
            throw new MallBusinessException("ACCOUNT_DISABLED", "synthetic disabled account");
        }, id -> fail("不应读商品"), id -> fail("不应统计评论"), (from, through, limit) -> fail("不应读排行"), (userId, from, through) -> fail("不应读取用户报告"), CLOCK);
        assertEquals("ACCOUNT_DISABLED", assertThrows(MallBusinessException.class, () -> service.hotItemsFor(USER)).code());
    }
}
