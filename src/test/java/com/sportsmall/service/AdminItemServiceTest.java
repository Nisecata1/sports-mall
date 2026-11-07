package com.sportsmall.service;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AdminItemServiceTest {
    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(42, "admin", "ADMIN");
    private static final UserAccount ACCOUNT = new UserAccount(42, "admin", "a@example.test", null, "ADMIN", 1);

    @Test
    void listIncludesInactiveProductsAndHasExplicitBound() {
        var expected = List.of(new AdminItemView(9, "商品", 1, "分类", new BigDecimal("10.00"), 0));
        var service = new AdminItemService(user -> ACCOUNT, limit -> {
            assertEquals(100, limit); return expected;
        }, (actor, fields) -> fail("Read-only"), (actor, id, fields) -> fail("Read-only"));
        assertEquals(expected, service.listFor(ADMIN));
    }

    @Test
    void createUsesVerifiedActorAndNormalizedPriceAndTitle() {
        var service = new AdminItemService(user -> ACCOUNT, limit -> fail("Write"), (actor, fields) -> {
            assertEquals(42, actor); assertEquals("训练篮球", fields.title());
            assertEquals(new BigDecimal("89.90"), fields.price());
            assertEquals(7, fields.categoryId()); assertEquals(0, fields.status());
            return new AdminItemView(9, fields.title(), fields.categoryId(), "分类", fields.price(), fields.status());
        }, (actor, id, fields) -> fail("Creation is not update"));
        assertEquals(9, service.createFor(ADMIN, " 训练篮球 ", 7, new BigDecimal("89.900"), 0).itemId());
    }

    @Test
    void updateRetainsTargetIdAndPassesSelectedStatus() {
        var service = new AdminItemService(user -> ACCOUNT, limit -> fail("Write"), (actor, fields) -> fail("Update is not insert"),
                (actor, id, fields) -> {
                    assertEquals(42, actor); assertEquals(9, id); assertEquals(1, fields.status());
                    return new AdminItemView(id, fields.title(), fields.categoryId(), "分类", fields.price(), fields.status());
                });
        assertEquals(9, service.updateFor(ADMIN, 9, "已修改", 7, new BigDecimal("90"), 1).itemId());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void emptyTitlesCannotReachDao(String title) {
        rejected("INVALID_ITEM_TITLE", () -> noWrites().createFor(ADMIN, title, 1, BigDecimal.ONE, 1));
    }

    @Test
    void unicodeTitleLimitUsesCharactersAndAcceptsBoundary() {
        var service = new AdminItemService(user -> ACCOUNT, limit -> List.of(),
                (actor, fields) -> new AdminItemView(9, fields.title(), 1, "分类", fields.price(), fields.status()),
                (actor, id, fields) -> fail("Not needed"));
        assertEquals("𠮷".repeat(200), service.createFor(ADMIN, "𠮷".repeat(200), 1, BigDecimal.ONE, 0).title());
        rejected("INVALID_ITEM_TITLE", () -> service.createFor(ADMIN, "字".repeat(201), 1, BigDecimal.ONE, 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.001", "12.345", "100000000.00"})
    void invalidPricesCannotBeRoundedOrOverflowDatabase(String price) {
        rejected("INVALID_ITEM_PRICE", () -> noWrites().createFor(ADMIN, "名称", 1, new BigDecimal(price), 1));
        rejected("INVALID_ITEM_PRICE", () -> noWrites().updateFor(ADMIN, 9, "名称", 1, new BigDecimal(price), 0));
    }

    @Test
    void nullPriceIsRejected() {
        rejected("INVALID_ITEM_PRICE", () -> noWrites().createFor(ADMIN, "名称", 1, null, 0));
    }

    @Test
    void exactDecimalMaximumIsAccepted() {
        var service = new AdminItemService(user -> ACCOUNT, limit -> List.of(),
                (actor, fields) -> new AdminItemView(9, fields.title(), 1, "分类", fields.price(), fields.status()),
                (actor, id, fields) -> fail("Not needed"));
        assertEquals(new BigDecimal("99999999.99"), service.createFor(ADMIN, "名称", 1, new BigDecimal("99999999.99"), 1).price());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void invalidIdsCannotReachDao(long id) {
        rejected("INVALID_ITEM_CATEGORY", () -> noWrites().createFor(ADMIN, "名称", id, BigDecimal.ONE, 1));
        rejected("INVALID_ITEM_ID", () -> noWrites().updateFor(ADMIN, id, "名称", 1, BigDecimal.ONE, 1));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 2})
    void unknownSaleStatusesAreRejected(int status) {
        rejected("INVALID_ITEM_STATUS", () -> noWrites().createFor(ADMIN, "名称", 1, BigDecimal.ONE, status));
    }

    @Test
    void revokedRoleCannotReadCreateOrUpdateEvenWithOldAdminSession() {
        var service = new AdminItemService(user -> new UserAccount(42, "admin", "a@example.test", null, "USER", 1),
                limit -> fail("Denied read"), (actor, fields) -> fail("Denied insert"), (actor, id, fields) -> fail("Denied update"));
        rejected("ADMIN_REQUIRED", () -> service.listFor(ADMIN));
        rejected("ADMIN_REQUIRED", () -> service.createFor(ADMIN, "名称", 1, BigDecimal.ONE, 1));
        rejected("ADMIN_REQUIRED", () -> service.updateFor(ADMIN, 9, "名称", 1, BigDecimal.ONE, 1));
    }

    @Test
    void disabledAccountRejectionPropagatesBeforeWrite() {
        var service = new AdminItemService(user -> { throw new MallBusinessException("ACCOUNT_DISABLED", "disabled"); },
                limit -> fail("Denied read"), (actor, fields) -> fail("Denied insert"), (actor, id, fields) -> fail("Denied update"));
        rejected("ACCOUNT_DISABLED", () -> service.createFor(ADMIN, "名称", 1, BigDecimal.ONE, 1));
    }

    private static AdminItemService noWrites() {
        return new AdminItemService(user -> ACCOUNT, limit -> fail("Not a read"),
                (actor, fields) -> fail("Invalid insert"), (actor, id, fields) -> fail("Invalid update"));
    }
    private static void rejected(String code, Runnable action) {
        assertEquals(code, assertThrows(MallBusinessException.class, action::run).code());
    }
}
