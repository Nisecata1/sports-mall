package com.sportsmall.service;

import com.mongodb.MongoException;
import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BrowsingServiceTest {
    private static final AuthenticatedUser USER = new AuthenticatedUser(42, "test", "USER");
    private static final UserAccount ACCOUNT = new UserAccount(42, "test", "test@example.test", null, "USER", 1);
    private static final ItemView ITEM = new ItemView(7, "test item", new BigDecimal("100.00"), 1, "test category");
    private static final ItemDetail DETAIL = new ItemDetail(7, "商品说明", List.of(), "zh-CN");

    @Test
    void openingCombinesTheSameProductAcrossBothDatabasesWithoutWriting() {
        BrowsingService service = service(current -> {
            assertSame(USER, current); return ACCOUNT;
        }, id -> { assertEquals(7, id); return Optional.of(ITEM); }, id -> {
            assertEquals(7, id); return Optional.of(DETAIL);
        }, (owner, item, seconds) -> fail("打开详情尚不写浏览记录"));
        BrowsingService.OpenedItem page = service.openFor(USER, 7);
        assertEquals(42, page.userId());
        assertSame(ITEM, page.item());
        assertEquals(Optional.of(DETAIL), page.detail());
    }

    @Test
    void missingOptionalDetailDoesNotHideTheMysqlProduct() {
        assertTrue(service(current -> ACCOUNT, id -> Optional.of(ITEM), id -> Optional.empty(),
                (owner, item, seconds) -> fail("不应写入")).openFor(USER, 7).detail().isEmpty());
    }

    @Test
    void missingOrInactiveProductCannotOpenOrLog() {
        BrowsingService service = service(current -> ACCOUNT, id -> Optional.empty(), id -> fail("不应读取详情"),
                (owner, item, seconds) -> fail("不应写日志"));
        assertEquals("BROWSE_ITEM_NOT_AVAILABLE", assertThrows(MallBusinessException.class,
                () -> service.openFor(USER, 7)).code());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 12})
    void finishingUsesLoggedInOwnerAndOpenedProductSnapshot(long seconds) {
        BrowsingService service = service(current -> ACCOUNT, id -> fail("结束时不重新筛除已浏览商品"),
                id -> fail("不应重读详情"), (owner, item, duration) -> {
                    assertEquals(42, owner); assertEquals(7, item); assertEquals(seconds, duration);
                    return log(owner, item, duration);
                });
        assertEquals(seconds, service.finishFor(USER, page(42), seconds).durationSeconds());
    }

    @Test
    void differentUserCannotFinishSomeoneElsesPage() {
        assertEquals("BROWSE_IDENTITY_MISMATCH", assertThrows(MallBusinessException.class,
                () -> ordinaryService().finishFor(USER, page(99), 1)).code());
    }

    @Test
    void accountDisabledDuringViewingCannotWriteOnFinish() {
        BrowsingService service = service(current -> {
            throw new MallBusinessException("ACCOUNT_DISABLED", "synthetic disabled account");
        }, id -> fail("不应读商品"), id -> fail("不应读详情"), (owner, item, seconds) -> fail("不应写入"));
        assertEquals("ACCOUNT_DISABLED", assertThrows(MallBusinessException.class,
                () -> service.finishFor(USER, page(42), 3)).code());
    }

    @Test
    void invalidDurationCannotReachStorage() {
        assertEquals("INVALID_BROWSE_DURATION", assertThrows(MallBusinessException.class,
                () -> ordinaryService().finishFor(USER, page(42), -1)).code());
    }

    @Test
    void unconfirmedWriteIsNotAutomaticallyRepeated() {
        AtomicInteger attempts = new AtomicInteger();
        BrowsingService service = service(current -> ACCOUNT, id -> Optional.of(ITEM), id -> Optional.of(DETAIL),
                (owner, item, seconds) -> {
                    attempts.incrementAndGet();
                    throw new MallDataAccessException("ACTION_LOG_UNCONFIRMED", "synthetic uncertain write");
                });
        assertEquals("ACTION_LOG_UNCONFIRMED", assertThrows(MallDataAccessException.class,
                () -> service.finishFor(USER, page(42), 3)).code());
        assertEquals(1, attempts.get());
    }

    @Test
    void detailsConnectionFailureIsNotAnEmptyDetail() {
        BrowsingService service = service(current -> ACCOUNT, id -> Optional.of(ITEM), id -> {
            throw new MongoException("synthetic unavailable database");
        }, (owner, item, seconds) -> fail("不应写入"));
        assertThrows(MallDataAccessException.class, () -> service.openFor(USER, 7));
    }

    @Test
    void malformedDetailDoesNotTurnIntoAMissingDetail() {
        BrowsingService service = service(current -> ACCOUNT, id -> Optional.of(ITEM), id -> {
            throw new IllegalArgumentException("synthetic invalid BSON");
        }, (owner, item, seconds) -> fail("不应写入"));
        assertEquals("MONGO_DATA_INVALID", assertThrows(MallDataAccessException.class,
                () -> service.openFor(USER, 7)).code());
    }

    @Test
    void invalidProductNumberCannotOpen() {
        assertEquals("INVALID_BROWSE_ITEM", assertThrows(MallBusinessException.class,
                () -> ordinaryService().openFor(USER, 0)).code());
    }

    private static BrowsingService ordinaryService() {
        return service(current -> ACCOUNT, id -> Optional.of(ITEM), id -> Optional.of(DETAIL),
                (owner, item, seconds) -> fail("当前测试不应写入"));
    }
    private static BrowsingService service(BrowsingService.AccountReader accounts, BrowsingService.ItemReader items,
                                          BrowsingService.DetailReader details, BrowsingService.ViewWriter writer) {
        return new BrowsingService(accounts, List::of, items, details, writer);
    }
    private static BrowsingService.OpenedItem page(long owner) { return new BrowsingService.OpenedItem(owner, ITEM, Optional.of(DETAIL)); }
    private static ActionLogView log(long owner, long item, long seconds) {
        return new ActionLogView("000000000000000000000001", owner, item, seconds, Instant.parse("2026-11-03T05:00:00Z"));
    }
}
