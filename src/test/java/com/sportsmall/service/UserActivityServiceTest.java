package com.sportsmall.service;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.UserAccount;
import com.sportsmall.model.UserActivityStatistics;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class UserActivityServiceTest {
    private static final AuthenticatedUser USER = new AuthenticatedUser(42, "test", "USER");
    private static final UserAccount ACCOUNT = new UserAccount(42, "test", "test@example.test", null, "USER", 1);
    private static final Instant NOW = Instant.parse("2026-11-03T06:00:00Z");

    @Test
    void reportUsesAuthenticatedAccountAndSevenDayWindowWithoutProductLookup() {
        UserActivityStatistics expected = new UserActivityStatistics(42, 3, 2, 2, 15, NOW);
        StatisticsService service = service(current -> {
            assertSame(USER, current); return ACCOUNT;
        }, (userId, from, through) -> {
            assertEquals(42, userId);
            assertEquals(Instant.parse("2026-10-27T06:00:00Z"), from);
            assertEquals(NOW, through);
            return expected;
        });
        assertSame(expected, service.myActivityFor(USER).summary());
    }

    @Test
    void noViewsDoesNotInventAnActiveDateOrLastViewTime() {
        StatisticsService service = service(current -> ACCOUNT, (user, from, through) -> UserActivityStatistics.empty(user));
        UserActivityStatistics empty = service.myActivityFor(USER).summary();
        assertEquals(0, empty.viewCount());
        assertEquals(0, empty.distinctItemCount());
        assertEquals(0, empty.activeDayCount());
        assertNull(empty.lastViewedAt());
    }

    @Test
    void disabledAccountCannotReachTheReport() {
        StatisticsService service = service(current -> {
            throw new MallBusinessException("ACCOUNT_DISABLED", "synthetic disabled account");
        }, (user, from, through) -> fail("不应执行报告"));
        assertEquals("ACCOUNT_DISABLED", assertThrows(MallBusinessException.class, () -> service.myActivityFor(USER)).code());
    }

    @Test
    void failedReportQueryIsNotReplacedWithZeroActivity() {
        StatisticsService service = service(current -> ACCOUNT, (user, from, through) -> {
            throw new MallDataAccessException("USER_ACTIVITY_QUERY_FAILED", "synthetic query failure");
        });
        assertEquals("USER_ACTIVITY_QUERY_FAILED", assertThrows(MallDataAccessException.class, () -> service.myActivityFor(USER)).code());
    }

    @Test
    void reportForAnotherUserIsRejected() {
        StatisticsService service = service(current -> ACCOUNT, (user, from, through) -> UserActivityStatistics.empty(99));
        assertEquals("USER_ACTIVITY_DATA_INVALID", assertThrows(MallDataAccessException.class, () -> service.myActivityFor(USER)).code());
    }

    private static StatisticsService service(StatisticsService.AccountReader accounts, StatisticsService.UserActivityReader activities) {
        return new StatisticsService(accounts, id -> fail("报告不读取商品名"), id -> fail("报告不统计评论"),
                (from, through, limit) -> fail("报告不查询全站榜单"), activities, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
