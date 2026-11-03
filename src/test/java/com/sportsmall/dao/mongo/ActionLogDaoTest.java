package com.sportsmall.dao.mongo;

import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.HotItemStatistics;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.Date;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ActionLogDaoTest {
    @Test
    void viewDocumentUsesTypedIdsAndDurationWithNestedCliSource() {
        Instant time = Instant.parse("2026-11-03T05:00:00Z");
        Document doc = ActionLogDao.viewDocument(new ObjectId(), 42, 7, 12, time);
        assertInstanceOf(Long.class, doc.get("user_id"));
        assertInstanceOf(Long.class, doc.get("item_id"));
        assertInstanceOf(Long.class, doc.get("duration_seconds"));
        assertEquals("VIEW", doc.getString("action_type"));
        assertEquals(new Document("client_type", "CLI").append("ip", "127.0.0.1"), doc.get("client_info"));
        assertEquals(Date.from(time), doc.getDate("created_at"));
    }

    @Test
    void immediateReturnCanHaveZeroWholeSeconds() {
        assertEquals(0L, ActionLogDao.viewDocument(new ObjectId(), 42, 7, 0, Instant.now()).getLong("duration_seconds"));
    }

    @ParameterizedTest
    @MethodSource("invalidViews")
    void invalidViewFieldsCannotBecomeDocuments(long user, long item, long duration) {
        assertThrows(IllegalArgumentException.class, () -> ActionLogDao.viewDocument(new ObjectId(), user, item, duration, Instant.now()));
    }

    private static Stream<Arguments> invalidViews() {
        return Stream.of(Arguments.of(0L, 7L, 1L), Arguments.of(42L, 0L, 1L), Arguments.of(42L, 7L, -1L));
    }

    @Test
    void serverCountersMapToRankingWithZeroDurationAllowed() {
        assertEquals(new HotItemStatistics(7, 3, 0), ActionLogDao.mapHotItem(validRanking()));
    }

    @ParameterizedTest
    @MethodSource("invalidRankingFields")
    void malformedRankingCannotMasqueradeAsValidMetrics(String field, Object value) {
        Document doc = validRanking(); doc.put(field, value);
        assertEquals("HOT_RANKING_DATA_INVALID", assertThrows(MallDataAccessException.class, () -> ActionLogDao.mapHotItem(doc)).code());
    }

    private static Stream<Arguments> invalidRankingFields() {
        return Stream.of(Arguments.of("_id", 0L), Arguments.of("view_count", 0), Arguments.of("duration_seconds", -1L),
                Arguments.of("duration_seconds", "3"), Arguments.of("invalid_count", 1), Arguments.of("view_count", 1.5));
    }
    private static Document validRanking() {
        return new Document("_id", 7L).append("view_count", 3).append("duration_seconds", 0L).append("invalid_count", 0);
    }
}
