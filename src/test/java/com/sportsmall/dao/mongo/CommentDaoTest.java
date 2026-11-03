package com.sportsmall.dao.mongo;

import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.CommentView;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CommentDaoTest {
    private static final ObjectId ID = new ObjectId("000000000000000000000001");
    private static final Instant TIME = Instant.parse("2026-11-03T03:00:00.123Z");

    @Test
    void commentDocumentUsesRealBsonTypesAndPreservesChineseAndArray() {
        List<String> tags = new ArrayList<>(List.of("训练", "篮球"));
        Document document = CommentDao.commentDocument(ID, 42L, 7L, "课程演示评论", 5, tags, TIME);
        tags.clear();
        assertInstanceOf(Long.class, document.get("user_id"));
        assertInstanceOf(Long.class, document.get("item_id"));
        assertInstanceOf(Integer.class, document.get("rating"));
        assertInstanceOf(Date.class, document.get("created_at"));
        assertEquals(List.of("训练", "篮球"), document.get("tags"));
        CommentView mapped = CommentDao.mapDocument(document);
        assertEquals(ID.toHexString(), mapped.commentId());
        assertEquals("课程演示评论", mapped.content());
        assertEquals(TIME, mapped.createdAt());
        assertThrows(UnsupportedOperationException.class, () -> mapped.tags().add("extra"));
    }

    @Test
    void emptyTagsRemainAnEmptyArray() {
        CommentView mapped = CommentDao.mapDocument(CommentDao.commentDocument(ID, 42L, 7L, "演示", 1, List.of(), TIME));
        assertTrue(mapped.tags().isEmpty());
    }

    @ParameterizedTest
    @MethodSource("invalidFields")
    void badStoredTypesDoNotMasqueradeAsValidComments(String field, Object value) {
        Document document = CommentDao.commentDocument(ID, 42L, 7L, "演示", 5, List.of(), TIME);
        document.put(field, value);
        MallDataAccessException failure = assertThrows(MallDataAccessException.class, () -> CommentDao.mapDocument(document));
        assertEquals("COMMENT_DATA_INVALID", failure.code());
    }

    private static Stream<Arguments> invalidFields() {
        return Stream.of(
                Arguments.of("_id", ID.toHexString()),
                Arguments.of("user_id", "42"),
                Arguments.of("item_id", 7),
                Arguments.of("rating", 4.5),
                Arguments.of("rating", 6),
                Arguments.of("tags", "篮球"),
                Arguments.of("tags", List.of(1)),
                Arguments.of("created_at", TIME.toString())
        );
    }
}
