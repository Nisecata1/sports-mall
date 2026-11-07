package com.sportsmall.util;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.CategoryView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CategoryTreesTest {
    private static final List<CategoryView> TREE = List.of(
            new CategoryView(1, "球类", null), new CategoryView(2, "篮球", 1L),
            new CategoryView(3, "训练篮球", 2L), new CategoryView(4, "户外", null));

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3})
    void selfOrAnyDepthDescendantCannotBecomeParent(long parent) {
        assertEquals("CATEGORY_CYCLE", assertThrows(MallBusinessException.class,
                () -> CategoryTrees.validateMove(TREE, 1, parent)).code());
    }

    @Test
    void movingSubtreeToOtherRootOrDetachingToRootIsAllowed() {
        assertDoesNotThrow(() -> CategoryTrees.validateMove(TREE, 2, 4L));
        assertDoesNotThrow(() -> CategoryTrees.validateMove(TREE, 2, null));
        assertDoesNotThrow(() -> CategoryTrees.validateMove(TREE, 2, 1L));
        assertEquals(2L, TREE.get(2).parentId(), "Validation must not rewrite descendants");
    }

    @Test
    void missingTargetAndParentHaveDistinctBusinessErrors() {
        assertEquals("CATEGORY_NOT_FOUND", assertThrows(MallBusinessException.class,
                () -> CategoryTrees.validateMove(TREE, 99, null)).code());
        assertEquals("CATEGORY_PARENT_NOT_FOUND", assertThrows(MallBusinessException.class,
                () -> CategoryTrees.validateMove(TREE, 2, 99L)).code());
    }

    @Test
    void alreadyCorruptGraphCannotBeChangedAsThoughItWereAValidTree() {
        var corrupt = List.of(new CategoryView(1, "a", 2L), new CategoryView(2, "b", 1L));
        assertEquals("CATEGORY_TREE_INVALID", assertThrows(MallDataAccessException.class,
                () -> CategoryTrees.validateMove(corrupt, 1, null)).code());
    }

    @Test
    void deepTreeDoesNotNeedRecursiveJavaCalls() {
        List<CategoryView> deep = new ArrayList<>();
        for (long id = 1; id <= 10000; id++) deep.add(new CategoryView(id, "node", id == 1 ? null : id - 1));
        assertDoesNotThrow(() -> CategoryTrees.validate(deep));
        assertEquals("CATEGORY_CYCLE", assertThrows(MallBusinessException.class,
                () -> CategoryTrees.validateMove(deep, 1, 10000L)).code());
    }
}
