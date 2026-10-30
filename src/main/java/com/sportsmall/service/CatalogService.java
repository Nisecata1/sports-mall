package com.sportsmall.service;

import com.mongodb.MongoException;
import com.sportsmall.dao.ItemDao;
import com.sportsmall.dao.mongo.ItemDetailDao;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.ItemDetail;
import com.sportsmall.model.ItemView;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 按同一商品编号组合两库数据；数据库不会自动完成这个关联。 */
public final class CatalogService {
    private final ItemDao itemDao;
    private final ItemDetailDao detailDao;

    public CatalogService(ItemDao itemDao, ItemDetailDao detailDao) {
        this.itemDao = itemDao;
        this.detailDao = detailDao;
    }

    public List<CatalogEntry> findAll() {
        List<CatalogEntry> entries = new ArrayList<>();
        for (ItemView item : itemDao.findAll()) {
            try {
                entries.add(new CatalogEntry(item, detailDao.findByItemId(item.itemId())));
            } catch (MongoException exception) {
                throw MallDataAccessException.fromMongoFailure(exception);
            } catch (IllegalArgumentException exception) {
                throw new MallDataAccessException("MONGO_DATA_INVALID",
                        "商品" + item.itemId() + "的详情字段类型不符合设计，请核对 item_details 中的编号、说明、图片数组和语言。");
            }
        }
        return List.copyOf(entries);
    }

    public record CatalogEntry(ItemView item, Optional<ItemDetail> detail) {
    }
}
