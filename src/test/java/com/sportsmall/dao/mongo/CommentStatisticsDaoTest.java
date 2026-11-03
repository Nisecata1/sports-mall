package com.sportsmall.dao.mongo;

import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.CommentStatistics;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CommentStatisticsDaoTest {
    @Test
    void serverIntegerAndLongCountersMapToOneConsistentSummary() {
        CommentStatistics result = CommentDao.mapStatistics(validSummary(), 7L);
        assertEquals(3, result.commentCount());
        assertEquals(13.0 / 3, result.averageRating());
        assertEquals(List.of(0L, 0L, 0L, 2L, 1L), result.ratingCounts());
    }

    @ParameterizedTest
    @MethodSource("invalidSummaries")
    void invalidOrInconsistentSummaryIsNotDisplayedAsAValidAverage(String field, Object value) {
        Document document = validSummary();
        document.put(field, value);
        assertEquals("COMMENT_STATISTICS_DATA_INVALID", assertThrows(MallDataAccessException.class,
                () -> CommentDao.mapStatistics(document, 7L)).code());
    }

    private static Stream<Arguments> invalidSummaries() {
        return Stream.of(
                Arguments.of("invalid_rating_count", 1),
                Arguments.of("comment_count", 4),
                Arguments.of("average_rating", Double.NaN),
                Arguments.of("average_rating", 4.0),
                Arguments.of("rating_5_count", -1L),
                Arguments.of("_id", 8L),
                Arguments.of("comment_count", "3"),
                Arguments.of("average_rating", (Object) null)
        );
    }

    private static Document validSummary() {
        return new Document("_id", 7L).append("comment_count", 3).append("average_rating", 13.0 / 3)
                .append("invalid_rating_count", 0L)
                .append("rating_1_count", 0).append("rating_2_count", 0L).append("rating_3_count", 0)
                .append("rating_4_count", 2L).append("rating_5_count", 1);
    }
}
