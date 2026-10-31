package com.sportsmall.service;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.OrderView;
import com.sportsmall.model.UserAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class OrderServiceTest {
    private static final AuthenticatedUser CURRENT = new AuthenticatedUser(42L, "mall_test", "USER");
    private static final UserAccount ACCOUNT = new UserAccount(42L, "mall_test", "mall@example.com", null, "USER", 1);

    @Test
    void newOrderOwnershipComesFromAuthenticatedAccount() {
        OrderView created = order("100.00");
        OrderService service = new OrderService(currentUser -> {
            assertSame(CURRENT, currentUser);
            return ACCOUNT;
        }, List::of, (userId, itemId, quote) -> {
            assertEquals(42L, userId);
            assertEquals(7L, itemId);
            assertEquals(0, quote.compareTo(new BigDecimal("100.00")));
            return created;
        }, userId -> List.of());
        assertSame(created, service.createFor(CURRENT, 7L, new BigDecimal("100.00")));
    }

    @Test
    void myOrderQueryUsesOwnerIdAndKeepsStoredHistoricalAmount() {
        OrderView historical = order("88.00");
        OrderService service = new OrderService(currentUser -> ACCOUNT, List::of,
                (userId, itemId, quote) -> { fail("查看订单不应新建订单"); return null; }, userId -> {
                    assertEquals(42L, userId);
                    return List.of(historical);
                });
        assertEquals(new BigDecimal("88.00"), service.findMine(CURRENT).get(0).amount());
    }

    @ParameterizedTest
    @MethodSource("invalidChoices")
    void invalidProductChoiceNeverCreatesOrder(long itemId, BigDecimal price) {
        OrderService service = new OrderService(currentUser -> {
            fail("无效选择不应进入数据库");
            return ACCOUNT;
        }, List::of, (owner, item, quote) -> { fail("无效选择不应保存"); return null; }, owner -> List.of());
        MallBusinessException failure = assertThrows(MallBusinessException.class,
                () -> service.createFor(CURRENT, itemId, price));
        assertEquals("INVALID_ORDER_ITEM", failure.code());
    }

    private static Stream<Arguments> invalidChoices() {
        return Stream.of(
                Arguments.of(0L, new BigDecimal("100.00")),
                Arguments.of(7L, null),
                Arguments.of(7L, BigDecimal.ZERO),
                Arguments.of(7L, new BigDecimal("-1.00"))
        );
    }

    @Test
    void disabledAccountCannotReachOrderCreation() {
        OrderService service = new OrderService(currentUser -> {
            throw new MallBusinessException("ACCOUNT_DISABLED", "synthetic disabled account");
        }, List::of, (owner, item, quote) -> { fail("停用账号不能下单"); return null; }, owner -> List.of());
        MallBusinessException failure = assertThrows(MallBusinessException.class,
                () -> service.createFor(CURRENT, 7L, new BigDecimal("100.00")));
        assertEquals("ACCOUNT_DISABLED", failure.code());
    }

    @Test
    void unconfirmedDatabaseResultIsNotTurnedIntoSuccess() {
        OrderService service = new OrderService(currentUser -> ACCOUNT, List::of, (owner, item, quote) -> {
            throw new MallDataAccessException("ORDER_SAVE_UNCONFIRMED", "synthetic unavailable database");
        }, owner -> List.of());
        MallDataAccessException failure = assertThrows(MallDataAccessException.class,
                () -> service.createFor(CURRENT, 7L, new BigDecimal("100.00")));
        assertEquals("ORDER_SAVE_UNCONFIRMED", failure.code());
    }

    private static OrderView order(String amount) {
        return new OrderView(81L, 42L, 7L, "synthetic test item", new BigDecimal(amount), 0,
                LocalDateTime.of(2026, 10, 31, 17, 0));
    }
}
