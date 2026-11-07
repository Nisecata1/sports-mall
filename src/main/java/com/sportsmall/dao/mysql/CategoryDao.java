package com.sportsmall.dao.mysql;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.CategoryView;
import com.sportsmall.util.JdbcTransactions;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 分类写入与读回共用事务，保留父分类和管理员账号在本次写入期间的有效性。 */
public final class CategoryDao {
    private static final String FIND_ALL =
            "SELECT category_id, name, parent_id FROM categories ORDER BY category_id";
    private static final String INSERT =
            "INSERT INTO categories(name, parent_id) VALUES(?, ?)";
    private final DataSource dataSource;

    public CategoryDao(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    public List<CategoryView> findAll() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_ALL);
             ResultSet rows = statement.executeQuery()) {
            List<CategoryView> result = new ArrayList<>();
            while (rows.next()) result.add(readRow(rows));
            return List.copyOf(result);
        } catch (SQLException failure) {
            throw new MallDataAccessException("CATEGORY_QUERY_FAILED", "分类查询未完成，请检查MySQL连接与categories表。");
        }
    }

    public CategoryView create(long actorId, String name, Long parentId) {
        try {
            return JdbcTransactions.execute(dataSource, connection -> {
                requireAdministrator(connection, actorId);
                if (parentId != null) requireParent(connection, parentId);
                long id;
                try (PreparedStatement statement = connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, name);
                    if (parentId == null) statement.setNull(2, Types.BIGINT);
                    else statement.setLong(2, parentId);
                    if (statement.executeUpdate() != 1) throw new SQLException("Unexpected category insert count");
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next() || (id = keys.getLong(1)) < 1) {
                            throw new SQLException("Generated category ID unavailable");
                        }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT category_id, name, parent_id FROM categories WHERE category_id = ?")) {
                    statement.setLong(1, id);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) throw new SQLException("Inserted category missing");
                        CategoryView saved = readRow(rows);
                        if (!name.equals(saved.name()) || !Objects.equals(parentId, saved.parentId())) {
                            throw new SQLException("Inserted category does not match submitted fields");
                        }
                        return saved;
                    }
                }
            });
        } catch (SQLException failure) {
            throw new MallDataAccessException("CATEGORY_SAVE_UNCONFIRMED",
                    "分类保存结果未确认，请先刷新分类树或查询categories表，再决定是否重新保存。");
        }
    }

    private static CategoryView readRow(ResultSet rows) throws SQLException {
        long parent = rows.getLong("parent_id");
        Long parentId = rows.wasNull() ? null : parent;
        return new CategoryView(rows.getLong("category_id"), rows.getString("name"), parentId);
    }

    private static void requireParent(Connection connection, long parentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT category_id FROM categories WHERE category_id = ? FOR SHARE")) {
            statement.setLong(1, parentId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new MallBusinessException("CATEGORY_PARENT_NOT_FOUND", "所选上级分类已不存在，请刷新分类树后重新选择。");
                }
            }
        }
    }

    private static void requireAdministrator(Connection connection, long actorId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT role, status FROM users WHERE user_id = ? FOR SHARE")) {
            statement.setLong(1, actorId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new MallBusinessException("ACCOUNT_NOT_FOUND", "当前商城账号已不存在。");
                if (rows.getInt("status") != 1) {
                    throw new MallBusinessException("ACCOUNT_DISABLED", "当前商城账号已停用。");
                }
                if (!"ADMIN".equals(rows.getString("role"))) {
                    throw new MallBusinessException("ADMIN_REQUIRED", "此操作仅限管理员账号。");
                }
            }
        }
    }
}
