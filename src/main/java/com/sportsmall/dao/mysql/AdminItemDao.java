package com.sportsmall.dao.mysql;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.AdminItemView;
import com.sportsmall.model.ItemFields;
import com.sportsmall.util.JdbcTransactions;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 管理员基础商品读写；上下架保留商品及订单，MongoDB详情由独立操作维护。 */
public final class AdminItemDao {
    private static final String SELECT = """
            SELECT i.item_id, i.title, i.category_id, c.name AS category_name, i.price, i.status
            FROM items i JOIN categories c ON c.category_id = i.category_id
            """;
    private final DataSource dataSource;

    public AdminItemDao(DataSource dataSource) { this.dataSource = Objects.requireNonNull(dataSource); }

    public List<AdminItemView> findAll(int limit) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT + " ORDER BY i.item_id LIMIT ?")) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<AdminItemView> result = new ArrayList<>();
                while (rows.next()) result.add(readRow(rows));
                return List.copyOf(result);
            }
        } catch (SQLException failure) {
            throw new MallDataAccessException("ADMIN_ITEM_QUERY_FAILED", "商品管理列表读取失败，请检查MySQL连接和商品分类记录。");
        }
    }

    public AdminItemView create(long actorId, ItemFields fields) {
        try {
            return JdbcTransactions.execute(dataSource, connection -> {
                requireAdministrator(connection, actorId);
                requireCategory(connection, fields.categoryId());
                long id;
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO items(title, category_id, price, status) VALUES(?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    bindFields(statement, fields);
                    if (statement.executeUpdate() != 1) throw new SQLException("Unexpected item insert count");
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next() || (id = keys.getLong(1)) < 1) throw new SQLException("Generated item ID unavailable");
                    }
                }
                return readBack(connection, id, fields);
            });
        } catch (SQLException failure) {
            throw new MallDataAccessException("ADMIN_ITEM_SAVE_UNCONFIRMED",
                    "商品新增结果未确认，请先刷新商品管理或查询items表，再决定是否重新保存。");
        }
    }

    public AdminItemView update(long actorId, long id, ItemFields fields) {
        try {
            return JdbcTransactions.execute(dataSource, connection -> {
                requireAdministrator(connection, actorId);
                // 先锁分类再锁商品，与分类维护保持一致的锁顺序。
                requireCategory(connection, fields.categoryId());
                try (PreparedStatement statement = connection.prepareStatement("SELECT item_id FROM items WHERE item_id = ? FOR UPDATE")) {
                    statement.setLong(1, id);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) throw new MallBusinessException("ITEM_NOT_FOUND", "该商品已不存在，请刷新商品管理列表。");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE items SET title = ?, category_id = ?, price = ?, status = ? WHERE item_id = ?")) {
                    bindFields(statement, fields);
                    statement.setLong(5, id);
                    statement.executeUpdate(); // 原值保存允许成功，以事务内读回为准。
                }
                return readBack(connection, id, fields);
            });
        } catch (SQLException failure) {
            throw new MallDataAccessException("ADMIN_ITEM_UPDATE_UNCONFIRMED",
                    "商品修改结果未确认，请先刷新商品管理或查询items表，再决定是否重新保存。");
        }
    }

    private static void bindFields(PreparedStatement statement, ItemFields fields) throws SQLException {
        statement.setString(1, fields.title());
        statement.setLong(2, fields.categoryId());
        statement.setBigDecimal(3, fields.price());
        statement.setInt(4, fields.status());
    }

    private static AdminItemView readBack(Connection connection, long id, ItemFields fields) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT + " WHERE i.item_id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Saved item missing");
                AdminItemView saved = readRow(rows);
                if (!fields.title().equals(saved.title()) || fields.categoryId() != saved.categoryId()
                        || fields.price().compareTo(saved.price()) != 0 || fields.status() != saved.status()) {
                    throw new SQLException("Saved item differs from submitted fields");
                }
                return saved;
            }
        }
    }

    private static AdminItemView readRow(ResultSet rows) throws SQLException {
        int status = rows.getInt("status");
        if (status != 0 && status != 1) throw new SQLException("Unsupported item status");
        return new AdminItemView(rows.getLong("item_id"), rows.getString("title"), rows.getLong("category_id"),
                rows.getString("category_name"), rows.getBigDecimal("price"), status);
    }

    private static void requireCategory(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT category_id FROM categories WHERE category_id = ? FOR SHARE")) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new MallBusinessException("ITEM_CATEGORY_NOT_FOUND", "所选商品分类已不存在，请刷新后重新选择。");
            }
        }
    }

    private static void requireAdministrator(Connection connection, long actorId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT role, status FROM users WHERE user_id = ? FOR SHARE")) {
            statement.setLong(1, actorId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new MallBusinessException("ACCOUNT_NOT_FOUND", "当前商城账号已不存在。");
                if (rows.getInt("status") != 1) throw new MallBusinessException("ACCOUNT_DISABLED", "当前商城账号已停用。");
                if (!"ADMIN".equals(rows.getString("role"))) throw new MallBusinessException("ADMIN_REQUIRED", "此操作仅限管理员账号。");
            }
        }
    }
}
