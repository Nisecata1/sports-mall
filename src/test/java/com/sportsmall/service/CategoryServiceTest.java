package com.sportsmall.service;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.CategoryView;
import com.sportsmall.model.UserAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CategoryServiceTest {
    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(42, "admin", "ADMIN");
    private static final UserAccount ACCOUNT = new UserAccount(42, "admin", "admin@example.test", null, "ADMIN", 1);

    @Test
    void rootAndMultipleLevelsCanBeReadRegardlessOfRowOrder() {
        var rows = List.of(new CategoryView(3, "child", 1L),
                new CategoryView(2, "other root", null), new CategoryView(1, "root", null),
                new CategoryView(4, "grandchild", 3L));
        CategoryService service = new CategoryService(current -> ACCOUNT, () -> rows,
                (actor, name, parent) -> fail("Read-only tree request"));
        assertEquals(rows, service.treeFor(ADMIN));
    }

    @Test
    void emptyDatabaseProducesAnEmptyTree() {
        CategoryService service = new CategoryService(current -> ACCOUNT, List::of,
                (actor, name, parent) -> fail("Read-only tree request"));
        assertTrue(service.treeFor(ADMIN).isEmpty());
    }

    @Test
    void creationUsesVerifiedActorAndPreservesNullRootOrSelectedParent() {
        CategoryService service = new CategoryService(current -> {
            assertSame(ADMIN, current);
            return ACCOUNT;
        }, () -> fail("Creation does not need to read the entire tree"), (actor, name, parent) -> {
            assertEquals(42, actor);
            assertEquals("篮球", name);
            return new CategoryView(9, name, parent);
        });
        assertNull(service.createFor(ADMIN, " 篮球 ", null).parentId());
        assertEquals(1L, service.createFor(ADMIN, "篮球", 1L).parentId());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void blankNamesAreRejectedBeforeWrite(String name) {
        CategoryService service = readOnlyService();
        assertEquals("INVALID_CATEGORY_NAME",
                assertThrows(MallBusinessException.class, () -> service.createFor(ADMIN, name, null)).code());
    }

    @Test
    void nameLimitCountsUnicodeCharactersRatherThanUtf16CodeUnits() {
        CategoryService service = new CategoryService(current -> ACCOUNT, List::of,
                (actor, name, parent) -> new CategoryView(9, name, parent));
        assertEquals("𠮷".repeat(50), service.createFor(ADMIN, "𠮷".repeat(50), null).name());
        assertEquals("INVALID_CATEGORY_NAME",
                assertThrows(MallBusinessException.class,
                        () -> service.createFor(ADMIN, "𠮷".repeat(51), null)).code());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void invalidParentIdsAreRejectedBeforeWrite(long parent) {
        assertEquals("INVALID_CATEGORY_PARENT",
                assertThrows(MallBusinessException.class,
                        () -> readOnlyService().createFor(ADMIN, "child", parent)).code());
    }

    @Test
    void staleAdministratorSessionCannotOverrideCurrentOrdinaryRole() {
        CategoryService service = new CategoryService(current ->
                new UserAccount(42, "admin", "admin@example.test", null, "USER", 1),
                () -> fail("Denied tree read"), (actor, name, parent) -> fail("Denied category write"));
        assertEquals("ADMIN_REQUIRED", assertThrows(MallBusinessException.class, () -> service.treeFor(ADMIN)).code());
        assertEquals("ADMIN_REQUIRED",
                assertThrows(MallBusinessException.class, () -> service.createFor(ADMIN, "child", null)).code());
    }

    @Test
    void accountRejectionIsPropagatedWithoutReadingOrWritingCategories() {
        CategoryService service = new CategoryService(current -> {
            throw new MallBusinessException("ACCOUNT_DISABLED", "Fixture disabled account");
        }, () -> fail("Inactive read"), (actor, name, parent) -> fail("Inactive write"));
        assertEquals("ACCOUNT_DISABLED",
                assertThrows(MallBusinessException.class, () -> service.createFor(ADMIN, "child", null)).code());
    }

    @ParameterizedTest
    @MethodSource("invalidTrees")
    void brokenReferencesAndCyclesCannotBeDisplayedAsValidTrees(List<CategoryView> rows) {
        CategoryService service = new CategoryService(current -> ACCOUNT, () -> rows,
                (actor, name, parent) -> fail("Read-only tree request"));
        assertEquals("CATEGORY_TREE_INVALID",
                assertThrows(MallDataAccessException.class, () -> service.treeFor(ADMIN)).code());
    }

    static Stream<List<CategoryView>> invalidTrees() {
        return Stream.of(
                List.of(new CategoryView(1, "self", 1L)),
                List.of(new CategoryView(1, "a", 2L), new CategoryView(2, "b", 1L)),
                List.of(new CategoryView(1, "orphan", 99L)),
                List.of(new CategoryView(1, "a", null), new CategoryView(1, "duplicate", null)));
    }

    private static CategoryService readOnlyService() {
        return new CategoryService(current -> ACCOUNT, List::of,
                (actor, name, parent) -> fail("Invalid input must not be saved"));
    }

    @Test
    void editUsesCurrentActorAndKeepsTargetIdentity() {
        var service = new CategoryService(current -> ACCOUNT, List::of,
                (actor, name, parent) -> fail("Edit is not an insert"),
                (actor, id, name, parent) -> {
                    assertEquals(42, actor);
                    assertEquals(7, id);
                    assertEquals("篮球装备", name);
                    return new CategoryView(id, name, parent);
                }, (actor, id) -> fail("Edit is not deletion"));
        assertEquals(9L, service.updateFor(ADMIN, 7, " 篮球装备 ", 9L).parentId());
        assertNull(service.updateFor(ADMIN, 7, "篮球装备", null).parentId());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void invalidEditAndDeleteTargetCannotReachDatabase(long id) {
        assertEquals("INVALID_CATEGORY_ID", assertThrows(MallBusinessException.class,
                () -> readOnlyService().updateFor(ADMIN, id, "有效名称", null)).code());
        assertEquals("INVALID_CATEGORY_ID", assertThrows(MallBusinessException.class,
                () -> readOnlyService().deleteFor(ADMIN, id)).code());
    }

    @Test
    void invalidEditFieldsCannotReachWriter() {
        assertEquals("INVALID_CATEGORY_NAME", assertThrows(MallBusinessException.class,
                () -> readOnlyService().updateFor(ADMIN, 7, " ", null)).code());
        assertEquals("INVALID_CATEGORY_PARENT", assertThrows(MallBusinessException.class,
                () -> readOnlyService().updateFor(ADMIN, 7, "名称", -1L)).code());
    }

    @Test
    void deleteUsesCurrentActorAndReportsActualResult() {
        var service = new CategoryService(current -> ACCOUNT, List::of,
                (actor, name, parent) -> fail("Delete is not insert"),
                (actor, id, name, parent) -> fail("Delete is not edit"), (actor, id) -> {
                    assertEquals(42, actor);
                    assertEquals(7, id);
                    return true;
                });
        assertTrue(service.deleteFor(ADMIN, 7));
    }

    @Test
    void staleAdministratorCannotEditOrDelete() {
        var service = new CategoryService(current -> new UserAccount(42, "admin", "a@example.test", null, "USER", 1),
                List::of, (actor, name, parent) -> fail("Denied insert"));
        assertEquals("ADMIN_REQUIRED", assertThrows(MallBusinessException.class,
                () -> service.updateFor(ADMIN, 7, "名称", null)).code());
        assertEquals("ADMIN_REQUIRED", assertThrows(MallBusinessException.class,
                () -> service.deleteFor(ADMIN, 7)).code());
    }
}
