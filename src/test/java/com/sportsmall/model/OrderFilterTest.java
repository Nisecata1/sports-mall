package com.sportsmall.model;

import com.sportsmall.exception.MallBusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class OrderFilterTest {
    @Test
    void oneDayIncludesTheWholeLeapDayByExcludingNextMidnight() {
        OrderFilter filter = new OrderFilter(2, LocalDate.of(2024, 2, 29), LocalDate.of(2024, 2, 29));
        assertEquals(LocalDateTime.of(2024, 2, 29, 0, 0), filter.startInclusive());
        assertEquals(LocalDateTime.of(2024, 3, 1, 0, 0), filter.endExclusive());
    }

    @Test
    void reversedDatesAreRejectedBeforeQuerying() {
        MallBusinessException failure = assertThrows(MallBusinessException.class,
                () -> new OrderFilter(null, LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 1)));
        assertEquals("INVALID_ORDER_FILTER", failure.code());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 3})
    void unsupportedOrderStatusCannotBecomeAQuery(Integer status) {
        assertThrows(MallBusinessException.class, () -> new OrderFilter(status, null, null));
    }

    @Test
    void lastDatabaseDateDoesNotOverflowTheDatetimeRange() {
        OrderFilter filter = new OrderFilter(null, null, LocalDate.of(9999, 12, 31));
        assertNull(filter.startInclusive());
        assertNull(filter.endExclusive());
        assertEquals(LocalDate.of(9999, 12, 31), filter.endDate());
    }

    @ParameterizedTest
    @ValueSource(ints = {999, 10000})
    void unsupportedDatabaseYearIsRejected(int year) {
        assertThrows(MallBusinessException.class,
                () -> new OrderFilter(null, LocalDate.of(year, 1, 1), null));
    }
}
