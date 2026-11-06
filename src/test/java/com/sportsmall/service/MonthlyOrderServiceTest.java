package com.sportsmall.service;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.MonthlyOrderReport;
import com.sportsmall.model.UserAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class MonthlyOrderServiceTest {
    private static final AuthenticatedUser USER = new AuthenticatedUser(42, "monthly-user", "USER");
    private static final UserAccount ACCOUNT = new UserAccount(42, "monthly-user", "monthly@example.test", null, "USER", 1);
    private static final LocalDate MONTH = LocalDate.of(2026, 10, 1);

    @Test
    void ownershipComesFromVerifiedAccountAndMonthIsAnchoredOnFirstDay() {
        MonthlyOrderReport expected = report(42, MONTH, 1, 0, 0, 1, "0.00");
        MonthlyOrderService service = new MonthlyOrderService(current -> {
            assertSame(USER, current);
            return ACCOUNT;
        }, (owner, month) -> {
            assertEquals(42, owner);
            assertEquals(MONTH, month);
            return expected;
        });
        assertSame(expected, service.viewMine(USER, "2026-10"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"2026-1", "2026-00", "2026-13", "2026-10-01", "0999-12", "10000-01", "2026-10&user_id=99"})
    void invalidMonthIsRejectedBeforeReadingData(String month) {
        MonthlyOrderService service = new MonthlyOrderService(current -> fail("Invalid month reached account query"),
                (owner, date) -> fail("Invalid month reached report query"));
        assertEquals("INVALID_REPORT_MONTH",
                assertThrows(MallBusinessException.class, () -> service.viewMine(USER, month)).code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1000-01", "2024-02", "2026-12", "9999-12"})
    void databaseCalendarBoundaryMonthsAreSupported(String month) {
        LocalDate first = LocalDate.parse(month + "-01");
        MonthlyOrderService service = service(report(42, first, 0, 0, 0, 0, "0.00"));
        assertEquals(first, service.viewMine(USER, month).monthStart());
    }

    @Test
    void disabledAccountStopsBeforeReportRead() {
        MonthlyOrderService service = new MonthlyOrderService(current -> {
            throw new MallBusinessException("ACCOUNT_DISABLED", "Disabled fixture");
        }, (owner, month) -> fail("Disabled account reached report query"));
        assertEquals("ACCOUNT_DISABLED", assertThrows(MallBusinessException.class,
                () -> service.viewMine(USER, "2026-10")).code());
    }

    @Test
    void anotherUserOrAnotherMonthCannotBeDisplayedAsThisReport() {
        for (MonthlyOrderReport wrong : new MonthlyOrderReport[]{
                report(99, MONTH, 0, 0, 0, 0, "0.00"),
                report(42, MONTH.plusMonths(1), 0, 0, 0, 0, "0.00")}) {
            assertEquals("MONTHLY_REPORT_DATA_INVALID",
                    assertThrows(MallDataAccessException.class, () -> service(wrong).viewMine(USER, "2026-10")).code());
        }
    }

    @Test
    void inconsistentStatusCountsOrAmountsAreRejected() {
        for (MonthlyOrderReport wrong : new MonthlyOrderReport[]{
                report(42, MONTH, 1, 1, 0, 1, "0.00"),
                report(42, MONTH, 1, 0, 0, 1, "100.00"),
                report(42, MONTH, 1, 0, 1, 0, "0.00"),
                report(42, MONTH, 1, 0, 1, 0, "-1.00"),
                report(42, MONTH, -1, 0, 0, 0, "0.00"),
                report(42, MONTH, Long.MAX_VALUE, Long.MAX_VALUE, 1, 0, "100.00")}) {
            assertEquals("MONTHLY_REPORT_DATA_INVALID",
                    assertThrows(MallDataAccessException.class, () -> service(wrong).viewMine(USER, "2026-10")).code());
        }
    }

    @Test
    void unavailableReportIsNotConvertedToAnEmptyMonth() {
        MonthlyOrderService service = new MonthlyOrderService(current -> ACCOUNT, (owner, month) -> {
            throw new MallDataAccessException("MONTHLY_REPORT_QUERY_FAILED", "Fixture failure");
        });
        assertEquals("MONTHLY_REPORT_QUERY_FAILED",
                assertThrows(MallDataAccessException.class, () -> service.viewMine(USER, "2026-10")).code());
    }

    private static MonthlyOrderService service(MonthlyOrderReport report) {
        return new MonthlyOrderService(current -> ACCOUNT, (owner, month) -> report);
    }

    private static MonthlyOrderReport report(long owner, LocalDate month, long total, long pending, long completed,
                                              long cancelled, String amount) {
        return new MonthlyOrderReport(owner, month, total, pending, completed, cancelled, new BigDecimal(amount));
    }
}
