package com.sportsmall.dao.mongo;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.sportsmall.model.ItemDetail;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.mongodb.client.model.Filters.eq;

/** 只从 MongoDB 读取详情，将 BSON 字段转换成 Java 业务对象。 */
public final class ItemDetailDao {
    private final MongoCollection<Document> collection;

    public ItemDetailDao(MongoDatabase database) {
        collection = Objects.requireNonNull(database).getCollection("item_details");
    }

    public Optional<ItemDetail> findByItemId(long itemId) {
        if (itemId < 1) {
            throw new IllegalArgumentException("商品编号必须大于0");
        }
        Document document = collection.find(eq("item_id", itemId)).first();
        return document == null ? Optional.empty() : Optional.of(mapDocument(document));
    }

    static ItemDetail mapDocument(Document document) {
        if (!(document.get("item_id") instanceof Long itemId)
                || !(document.get("description") instanceof String description)
                || !(document.get("images") instanceof List<?> rawImages)
                || !(document.get("metadata") instanceof Document metadata)
                || !(metadata.get("language") instanceof String language)) {
            throw new IllegalArgumentException("商品详情的 BSON 字段缺失或类型错误");
        }
        List<String> images = new ArrayList<>();
        for (Object image : rawImages) {
            if (!(image instanceof String imageText)) {
                throw new IllegalArgumentException("商品详情的图片列表必须只包含字符串");
            }
            images.add(imageText);
        }
        return new ItemDetail(itemId, description, images, language);
    }
}
