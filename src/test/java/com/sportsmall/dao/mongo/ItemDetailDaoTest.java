package com.sportsmall.dao.mongo;

import com.sportsmall.model.ItemDetail;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemDetailDaoTest {
    @Test
    void readsLongIdAndAcceptsNoImages() {
        ItemDetail detail = ItemDetailDao.mapDocument(validDocument());
        assertEquals(1L, detail.itemId());
        assertEquals("训练用篮球，适合日常运球与投篮练习。", detail.description());
        assertEquals("zh-CN", detail.language());
        assertTrue(detail.images().isEmpty());
    }

    @Test
    void rejectsStringIdInsteadOfSilentlyTreatingItAsNumeric() {
        Document document = validDocument().append("item_id", "1");
        assertThrows(IllegalArgumentException.class, () -> ItemDetailDao.mapDocument(document));
    }

    @Test
    void rejectsNonStringImageValues() {
        Document document = validDocument().append("images", List.of(123));
        assertThrows(IllegalArgumentException.class, () -> ItemDetailDao.mapDocument(document));
    }

    @Test
    void rejectsMissingLanguageInsteadOfFabricatingADefault() {
        Document document = validDocument().append("metadata", new Document());
        assertThrows(IllegalArgumentException.class, () -> ItemDetailDao.mapDocument(document));
    }

    private static Document validDocument() {
        return new Document("item_id", 1L)
                .append("description", "训练用篮球，适合日常运球与投篮练习。")
                .append("images", List.of())
                .append("metadata", new Document("language", "zh-CN"));
    }
}
