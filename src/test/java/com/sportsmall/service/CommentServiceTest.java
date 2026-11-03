package com.sportsmall.service;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.CommentView;
import com.sportsmall.model.ItemView;
import com.sportsmall.model.UserAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CommentServiceTest {
    private static final AuthenticatedUser CURRENT = new AuthenticatedUser(42L, "mall_test", "USER");
    private static final UserAccount ACCOUNT = new UserAccount(42L, "mall_test", "mall@example.test", null, "USER", 1);
    private static final ItemView ITEM = new ItemView(7L, "synthetic item", new BigDecimal("100.00"), 1L, "test category");

    @Test
    void postUsesLoggedInOwnerAndNormalizesTextAndTagsAsAnArray() {
        CommentService service = service(current -> {
            assertSame(CURRENT, current);
            return ACCOUNT;
        }, id -> {
            assertEquals(7L, id);
            return Optional.of(ITEM);
        }, (owner, itemId, content, rating, tags) -> {
            assertEquals(42L, owner);
            assertEquals(7L, itemId);
            assertEquals("课程演示评论", content);
            assertEquals(5, rating);
            assertEquals(List.of("训练", "篮球"), tags);
            return comment(owner, itemId, content, rating, tags);
        }, (itemId, limit) -> fail("发表评论不需要查询列表"));
        CommentView saved = service.post(CURRENT, 7L, " 课程演示评论 ", 5, "训练， 篮球,训练,, ");
        assertEquals(42L, saved.userId());
    }

    @Test
    void tagsCanBeEmptyWithoutLosingTheComment() {
        CommentService service = service(current -> ACCOUNT, id -> Optional.of(ITEM),
                (owner, item, content, rating, tags) -> {
                    assertTrue(tags.isEmpty());
                    return comment(owner, item, content, rating, tags);
                }, (item, limit) -> fail("不应查询列表"));
        assertTrue(service.post(CURRENT, 7L, "演示", 1, " ").tags().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 6})
    void invalidRatingNeverWrites(int rating) {
        assertBusinessCode("INVALID_COMMENT_RATING",
                () -> noDatabaseService().post(CURRENT, 7L, "演示", rating, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void blankContentNeverWrites(String content) {
        assertBusinessCode("INVALID_COMMENT_CONTENT",
                () -> noDatabaseService().post(CURRENT, 7L, content, 5, null));
    }

    @Test
    void overlongContentNeverWrites() {
        assertBusinessCode("INVALID_COMMENT_CONTENT",
                () -> noDatabaseService().post(CURRENT, 7L, "文".repeat(2001), 5, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"一,二,三,四,五,六", "超过二十个字符的标签不能写入评论文档的标签数组字段"})
    void oversizedTagsNeverWrite(String tags) {
        assertBusinessCode("INVALID_COMMENT_TAGS",
                () -> noDatabaseService().post(CURRENT, 7L, "演示", 5, tags));
    }

    @Test
    void missingOrDelistedProductCannotCreateOrReadComments() {
        CommentService service = service(current -> ACCOUNT, id -> Optional.empty(),
                (owner, item, content, rating, tags) -> fail("无商品不能写评论"),
                (item, limit) -> fail("无商品不能读评论"));
        assertBusinessCode("COMMENT_ITEM_NOT_AVAILABLE", () -> service.post(CURRENT, 7L, "演示", 5, null));
        assertBusinessCode("COMMENT_ITEM_NOT_AVAILABLE", () -> service.latestFor(CURRENT, 7L));
    }

    @Test
    void disabledAccountCannotReachCommentWrite() {
        CommentService service = service(current -> {
            throw new MallBusinessException("ACCOUNT_DISABLED", "synthetic disabled account");
        }, id -> fail("不能读取商品"), (owner, item, content, rating, tags) -> fail("不能写评论"),
                (item, limit) -> fail("不能读评论"));
        assertBusinessCode("ACCOUNT_DISABLED", () -> service.post(CURRENT, 7L, "演示", 5, null));
    }

    @Test
    void unknownWriteResultIsNotRetriedOrReportedAsSuccess() {
        AtomicInteger attempts = new AtomicInteger();
        CommentService service = service(current -> ACCOUNT, id -> Optional.of(ITEM),
                (owner, item, content, rating, tags) -> {
                    attempts.incrementAndGet();
                    throw new MallDataAccessException("COMMENT_SAVE_UNCONFIRMED", "synthetic uncertain result");
                }, (item, limit) -> fail("不自动改做查询"));
        MallDataAccessException failure = assertThrows(MallDataAccessException.class,
                () -> service.post(CURRENT, 7L, "演示", 5, null));
        assertEquals("COMMENT_SAVE_UNCONFIRMED", failure.code());
        assertEquals(1, attempts.get());
    }

    @Test
    void latestCommentsUseSelectedItemAndBoundTheResult() {
        CommentView anotherUser = comment(88L, 7L, "公开商品评论", 4, List.of());
        CommentService service = service(current -> ACCOUNT, id -> Optional.of(ITEM),
                (owner, item, content, rating, tags) -> fail("查看不能写入"), (item, limit) -> {
                    assertEquals(7L, item);
                    assertEquals(20, limit);
                    return List.of(anotherUser);
                });
        assertEquals(List.of(anotherUser), service.latestFor(CURRENT, 7L));
    }

    @Test
    void queryFailureIsNotTurnedIntoNoComments() {
        CommentService service = service(current -> ACCOUNT, id -> Optional.of(ITEM),
                (owner, item, content, rating, tags) -> fail("查看不能写入"), (item, limit) -> {
                    throw new MallDataAccessException("COMMENT_QUERY_FAILED", "synthetic query failure");
                });
        MallDataAccessException failure = assertThrows(MallDataAccessException.class, () -> service.latestFor(CURRENT, 7L));
        assertEquals("COMMENT_QUERY_FAILED", failure.code());
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void invalidItemIdNeverReachesDatabase(long itemId) {
        assertBusinessCode("INVALID_COMMENT_ITEM", () -> noDatabaseService().post(CURRENT, itemId, "演示", 5, null));
        assertBusinessCode("INVALID_COMMENT_ITEM", () -> noDatabaseService().latestFor(CURRENT, itemId));
    }

    private static CommentService noDatabaseService() {
        return service(current -> fail("无效输入不能访问账号"), id -> fail("不能访问商品"),
                (owner, item, content, rating, tags) -> fail("不能保存评论"), (item, limit) -> fail("不能读取评论"));
    }

    private static CommentService service(CommentService.AccountReader accounts, CommentService.ItemReader items,
                                          CommentService.CommentWriter writer, CommentService.CommentReader reader) {
        return new CommentService(accounts, List::of, items, writer, reader);
    }

    private static void assertBusinessCode(String code, Runnable action) {
        assertEquals(code, assertThrows(MallBusinessException.class, action::run).code());
    }

    private static CommentView comment(long owner, long itemId, String content, int rating, List<String> tags) {
        return new CommentView("000000000000000000000001", owner, itemId, content, rating, tags, Instant.parse("2026-11-03T03:00:00Z"));
    }
}
