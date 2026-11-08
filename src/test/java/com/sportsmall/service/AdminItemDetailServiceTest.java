package com.sportsmall.service;

import com.mongodb.MongoException;
import com.sportsmall.exception.*;
import com.sportsmall.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AdminItemDetailServiceTest {
    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(2, "admin", "ADMIN");
    private static final UserAccount ACCOUNT = new UserAccount(2, "admin", "a@example.test", null, "ADMIN", 1);
    private static final AdminItemView ITEM = new AdminItemView(9, "下架商品", 1, "分类", BigDecimal.TEN, 0);

    private AdminItemDetailService service() {
        return new AdminItemDetailService(user -> ACCOUNT, id -> Optional.of(ITEM), id -> Optional.empty(), detail -> detail, id -> false);
    }
    @Test void absentDetailIsNotInventedAndInactiveItemCanBeEdited() {
        assertTrue(service().viewFor(ADMIN, 9).isEmpty());
        assertEquals(9, service().saveFor(ADMIN, 9, "说明", List.of(), "zh-CN").itemId());
    }
    @Test void inputIsNormalizedAndKeepsTargetId() {
        var detail = service().saveFor(ADMIN, 9, " 说明\n", List.of(" HTTPS://example.test/a.jpg "), " zh-CN ");
        assertEquals(new ItemDetail(9, "说明", List.of("HTTPS://example.test/a.jpg"), "zh-CN"), detail);
    }
    @Test void unicodeDescriptionLimitCountsCharacters() {
        assertEquals(5000, service().saveFor(ADMIN, 9, "𠮷".repeat(5000), List.of(), "en").description().codePointCount(0, 10000));
        denied("INVALID_DETAIL_DESCRIPTION", () -> service().saveFor(ADMIN, 9, "字".repeat(5001), List.of(), "en"));
    }
    @Test void missingAndBlankDescriptionRejected() {
        for (String value : new String[]{null, "", " \n"})
            denied("INVALID_DETAIL_DESCRIPTION", () -> service().saveFor(ADMIN, 9, value, List.of(), "en"));
    }
    @ParameterizedTest @ValueSource(strings = {"", "a", "zh_CN", "-zh", "zh-", "中文", "en-123456789"})
    void invalidLanguageRejected(String value) {
        denied("INVALID_DETAIL_LANGUAGE", () -> service().saveFor(ADMIN, 9, "说明", List.of(), value));
    }
    @Test void missingAndOverlongLanguageRejected() {
        denied("INVALID_DETAIL_LANGUAGE", () -> service().saveFor(ADMIN, 9, "说明", List.of(), null));
        denied("INVALID_DETAIL_LANGUAGE", () -> service().saveFor(ADMIN, 9, "说明", List.of(), "en" + "-abcdefgh".repeat(4)));
    }
    @ParameterizedTest @ValueSource(strings = {"", "/relative.jpg", "file:///C:/a.jpg", "javascript:alert(1)", "https://", "https://user:password@example.test/a.jpg", "http://a b"})
    void invalidImageUrlCannotReachWriter(String value) {
        denied("INVALID_DETAIL_IMAGES", () -> service().saveFor(ADMIN, 9, "说明", List.of(value), "en"));
    }
    @Test void imageListLimitsAndMissingValuesRejected() {
        denied("INVALID_DETAIL_IMAGES", () -> service().saveFor(ADMIN, 9, "说明", null, "en"));
        denied("INVALID_DETAIL_IMAGES", () -> service().saveFor(ADMIN, 9, "说明", Arrays.asList((String)null), "en"));
        denied("INVALID_DETAIL_IMAGES", () -> service().saveFor(ADMIN, 9, "说明", Collections.nCopies(11, "https://example.test/a"), "en"));
        denied("INVALID_DETAIL_IMAGES", () -> service().saveFor(ADMIN, 9, "说明", List.of("https://example.test/" + "a".repeat(2048)), "en"));
        assertEquals(10, service().saveFor(ADMIN, 9, "说明", Collections.nCopies(10, "https://example.test/a"), "en").images().size());
    }
    @Test void nonexistentOrInvalidItemBlocksAllDetailOperations() {
        var service = new AdminItemDetailService(user -> ACCOUNT, id -> Optional.empty(), id -> fail("No Mongo read"),
                detail -> fail("No Mongo write"), id -> fail("No Mongo delete"));
        denied("ITEM_NOT_FOUND", () -> service.viewFor(ADMIN, 9));
        denied("ITEM_NOT_FOUND", () -> service.saveFor(ADMIN, 9, "说明", List.of(), "en"));
        denied("ITEM_NOT_FOUND", () -> service.deleteFor(ADMIN, 9));
        denied("INVALID_ITEM_ID", () -> service.viewFor(ADMIN, 0));
        denied("INVALID_ITEM_ID", () -> service.deleteFor(ADMIN, -1));
    }
    @Test void currentDatabaseRoleOverridesOldAdminSession() {
        var service = new AdminItemDetailService(user -> new UserAccount(2,"admin","a@example.test",null,"USER",1),
                id -> fail("Not authorized"), id -> fail("Not authorized"), detail -> fail("Not authorized"), id -> fail("Not authorized"));
        denied("ADMIN_REQUIRED", () -> service.viewFor(ADMIN, 9));
        denied("ADMIN_REQUIRED", () -> service.saveFor(ADMIN, 9, "说明", List.of(), "en"));
        denied("ADMIN_REQUIRED", () -> service.deleteFor(ADMIN, 9));
    }
    @Test void deletingAndReadingUseSelectedItemAndReportAbsence() {
        var stored = new AtomicReference<>(new ItemDetail(9,"说明",List.of(),"en"));
        var service = new AdminItemDetailService(user -> ACCOUNT, id -> Optional.of(ITEM), id -> {
            assertEquals(9, id); return Optional.ofNullable(stored.get());
        }, detail -> detail, id -> { assertEquals(9, id); return stored.getAndSet(null) != null; });
        assertEquals(stored.get(), service.viewFor(ADMIN, 9).orElseThrow());
        assertTrue(service.deleteFor(ADMIN, 9));
        assertFalse(service.deleteFor(ADMIN, 9));
        assertTrue(service.viewFor(ADMIN, 9).isEmpty());
    }
    @Test void readFailureUsesSafeMessage() {
        var service = new AdminItemDetailService(user -> ACCOUNT, id -> Optional.of(ITEM), id -> { throw new MongoException("secret"); },
                detail -> detail, id -> false);
        var failure = assertThrows(MallDataAccessException.class, () -> service.viewFor(ADMIN, 9));
        assertEquals("ITEM_DETAIL_QUERY_FAILED", failure.code());
        assertFalse(failure.getMessage().contains("secret"));
    }
    private static void denied(String code, Runnable action) {
        assertEquals(code, assertThrows(MallBusinessException.class, action::run).code());
    }
}
