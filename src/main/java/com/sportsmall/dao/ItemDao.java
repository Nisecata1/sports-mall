package com.sportsmall.dao;

import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.ItemView;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.sql.Types;

/** 只负责从已有表中读取商品，所有查询入口集中在这里。 */
public final class ItemDao {
    private static final String FIND_ALL = """
            SELECT items.item_id, items.title, items.price, items.category_id,
                   categories.name AS category_name
            FROM items
            JOIN categories ON items.category_id = categories.category_id
            ORDER BY items.item_id
            """;
    private static final String FIND_ACTIVE = """
            SELECT items.item_id, items.title, items.price, items.category_id,
                   categories.name AS category_name
            FROM items JOIN categories ON items.category_id = categories.category_id
            WHERE items.status = 1 AND (? IS NULL OR items.item_id = ?)
            ORDER BY items.item_id
            """;

    private final DataSource dataSource;

    public ItemDao(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    public List<ItemView> findAll() {
        List<ItemView> items = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_ALL);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                items.add(new ItemView(
                        results.getLong("item_id"),
                        results.getString("title"),
                        results.getBigDecimal("price"),
                        results.getLong("category_id"),
                        results.getString("category_name")));
            }
            return List.copyOf(items);
        } catch (SQLException exception) {
            throw MallDataAccessException.fromDatabaseFailure(exception);
        }
    }

    public List<ItemView> findActive() {
        return queryActive(null);
    }

    public Optional<ItemView> findActiveById(long itemId) {
        return queryActive(itemId).stream().findFirst();
    }

    private List<ItemView> queryActive(Long itemId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_ACTIVE)) {
            for (int index = 1; index <= 2; index++) {
                if (itemId == null) {
                    statement.setNull(index, Types.BIGINT);
                } else {
                    statement.setLong(index, itemId);
                }
            }
            try (ResultSet results = statement.executeQuery()) {
                List<ItemView> items = new ArrayList<>();
                while (results.next()) {
                    items.add(new ItemView(results.getLong("item_id"), results.getString("title"),
                            results.getBigDecimal("price"), results.getLong("category_id"),
                            results.getString("category_name")));
                }
                return List.copyOf(items);
            }
        } catch (SQLException exception) {
            throw new MallDataAccessException("COMMENT_ITEMS_QUERY_FAILED", "无法读取评论商品，请检查MySQL连接与商品表。");
        }
    }
}
