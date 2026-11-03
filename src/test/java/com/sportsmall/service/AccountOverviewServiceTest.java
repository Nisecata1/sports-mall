package com.sportsmall.service;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.AccountOverview;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.UserAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class AccountOverviewServiceTest {
    private static final AuthenticatedUser USER = new AuthenticatedUser(42, "test", "USER");
    private static final UserAccount ACCOUNT = new UserAccount(42, "test", "test@example.test", null, "USER", 1);

    @Test
    void queryUsesVerifiedUserIdAndKeepsAccountWithoutAProfile() {
        AccountOverview expected = overview(42, 0, 0, 0, 0, "0.00");
        AccountOverviewService service = new AccountOverviewService(current -> {
            assertSame(USER, current); return ACCOUNT;
        }, userId -> { assertEquals(42, userId); return Optional.of(expected); });
        assertSame(expected, service.viewMine(USER));
        assertNull(expected.realName());
    }

    @Test
    void cancelledOrderIsRetainedWithoutBecomingCompletedAmount() {
        AccountOverview expected = overview(42, 1, 0, 0, 1, "0.00");
        assertEquals(expected, service(expected).viewMine(USER));
    }

    @Test
    void disabledAccountCannotQueryViews() {
        AccountOverviewService service = new AccountOverviewService(current -> {
            throw new MallBusinessException("ACCOUNT_DISABLED", "synthetic disabled account");
        }, id -> fail("停用账号不应读取视图"));
        assertEquals("ACCOUNT_DISABLED", assertThrows(MallBusinessException.class, () -> service.viewMine(USER)).code());
    }

    @Test
    void missingOverviewDoesNotBecomeAnInventedEmptyAccount() {
        AccountOverviewService service = new AccountOverviewService(current -> ACCOUNT, id -> Optional.empty());
        assertEquals("OVERVIEW_NOT_FOUND", assertThrows(MallDataAccessException.class, () -> service.viewMine(USER)).code());
    }

    @Test
    void anotherUsersOverviewIsRejected() {
        assertEquals("OVERVIEW_DATA_INVALID", assertThrows(MallDataAccessException.class,
                () -> service(overview(99, 0, 0, 0, 0, "0.00")).viewMine(USER)).code());
    }

    @Test
    void queryFailureIsNotDisplayedAsZeroOrders() {
        AccountOverviewService service = new AccountOverviewService(current -> ACCOUNT, id -> {
            throw new MallDataAccessException("OVERVIEW_QUERY_FAILED", "synthetic query failure");
        });
        assertEquals("OVERVIEW_QUERY_FAILED", assertThrows(MallDataAccessException.class, () -> service.viewMine(USER)).code());
    }

    @ParameterizedTest
    @MethodSource("inconsistentSummaries")
    void inconsistentCountsOrAmountsAreRejected(long total, long pending, long completed, long cancelled, String amount) {
        assertEquals("OVERVIEW_DATA_INVALID", assertThrows(MallDataAccessException.class,
                () -> service(overview(42, total, pending, completed, cancelled, amount)).viewMine(USER)).code());
    }

    private static Stream<Arguments> inconsistentSummaries() {
        return Stream.of(Arguments.of(1, 1, 0, 1, "0.00"), Arguments.of(1, 0, 0, 1, "100.00"),
                Arguments.of(1, 0, 1, 0, "0.00"), Arguments.of(1, 0, 1, 0, "-1.00"));
    }
    private static AccountOverviewService service(AccountOverview overview) {
        return new AccountOverviewService(current -> ACCOUNT, id -> Optional.of(overview));
    }
    private static AccountOverview overview(long id, long total, long pending, long completed, long cancelled, String amount) {
        return new AccountOverview(id, "test", null, null, total, pending, completed, cancelled, new BigDecimal(amount));
    }
}
