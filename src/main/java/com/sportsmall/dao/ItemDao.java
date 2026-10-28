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

/** 只负责从已有表中读取商品，所有查询入口集中在这里。 */
public final class ItemDao {
    private static final String FIND_ALL = """
            SELECT items.item_id, items.title, items.price, items.category_id,
                   categories.name AS category_name
            FROM items
            JOIN categories ON items.category_id = categories.category_id
            ORDER BY items.item_id
            """;

    private final DataSource dataSource;

    public ItemDao(DataSource dataSource) {
        this.dataSource = dataSource;
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
}
