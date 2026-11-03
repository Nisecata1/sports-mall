package com.sportsmall.dao.mongo;

import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.UserActivityStatistics;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.Date;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class UserActivityDaoTest {
    private static final Instant LAST = Instant.parse("2026-11-03T06:00:00Z");

    @Test
    void repeatedViewsAreDistinctFromUniqueProductsAndDates() {
        UserActivityStatistics result = ActionLogDao.mapActivity(validReport(), 42);
        assertEquals(new UserActivityStatistics(42, 3, 2, 2, 15, LAST), result);
    }

    @Test
    void rollingSevenDayWindowMayTouchEightCalendarDates() {
        Document report = validReport().append("view_count", 8).append("active_day_count", 8);
        assertEquals(8, ActionLogDao.mapActivity(report, 42).activeDayCount());
    }

    @ParameterizedTest
    @MethodSource("invalidFields")
    void badOrInconsistentReportIsNotShownAsValidMetrics(String field, Object value) {
        Document report = validReport(); report.put(field, value);
        assertEquals("USER_ACTIVITY_DATA_INVALID", assertThrows(MallDataAccessException.class,
                () -> ActionLogDao.mapActivity(report, 42)).code());
    }

    private static Stream<Arguments> invalidFields() {
        return Stream.of(Arguments.of("_id", 99L), Arguments.of("view_count", 0), Arguments.of("distinct_item_count", 4),
                Arguments.of("active_day_count", 4L), Arguments.of("duration_seconds", -1L),
                Arguments.of("invalid_count", 1), Arguments.of("last_viewed_at", LAST.toString()), Arguments.of("view_count", "3"));
    }
    private static Document validReport() {
        return new Document("_id", 42L).append("view_count", 3).append("distinct_item_count", 2L)
                .append("active_day_count", 2).append("duration_seconds", 15L).append("invalid_count", 0)
                .append("last_viewed_at", Date.from(LAST));
    }
}
