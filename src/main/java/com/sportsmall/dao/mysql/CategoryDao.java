package com.sportsmall.dao.mysql;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.CategoryView;
import com.sportsmall.util.JdbcTransactions;
import com.sportsmall.util.CategoryTrees;

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

    public CategoryView update(long actorId, long id, String name, Long parentId) {
        try {
            return JdbcTransactions.execute(dataSource, connection -> {
                requireAdministrator(connection, actorId);
                // 课程规模分类较少：按主键锁定分类记录，使并发改上级不能各自通过旧树检查。
                List<CategoryView> rows = lockedTree(connection);
                CategoryTrees.validateMove(rows, id, parentId);
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE categories SET name = ?, parent_id = ? WHERE category_id = ?")) {
                    statement.setString(1, name);
                    if (parentId == null) statement.setNull(2, Types.BIGINT);
                    else statement.setLong(2, parentId);
                    statement.setLong(3, id);
                    statement.executeUpdate(); // 原值保存也允许成功；随后以实际读回为准。
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT category_id, name, parent_id FROM categories WHERE category_id = ?")) {
                    statement.setLong(1, id);
                    try (ResultSet saved = statement.executeQuery()) {
                        if (!saved.next()) throw new SQLException("Updated category missing");
                        CategoryView result = readRow(saved);
                        if (!name.equals(result.name()) || !Objects.equals(parentId, result.parentId())) {
                            throw new SQLException("Updated category does not match submitted fields");
                        }
                        return result;
                    }
                }
            });
        } catch (SQLException failure) {
            throw new MallDataAccessException("CATEGORY_UPDATE_UNCONFIRMED",
                    "分类修改结果未确认，请先刷新分类树或查询categories表，再决定是否重新保存。");
        }
    }

    public boolean delete(long actorId, long id) {
        try {
            return JdbcTransactions.execute(dataSource, connection -> {
                requireAdministrator(connection, actorId);
                List<CategoryView> rows = lockedTree(connection);
                CategoryTrees.validate(rows);
                CategoryTrees.requireCategory(rows, id);
                if (rows.stream().anyMatch(row -> Objects.equals(row.parentId(), id))) {
                    throw new MallBusinessException("CATEGORY_HAS_CHILDREN", "该分类仍有下级分类，请先调整下级，不能直接删除。");
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT item_id FROM items WHERE category_id = ? LIMIT 1")) {
                    statement.setLong(1, id);
                    try (ResultSet items = statement.executeQuery()) {
                        if (items.next()) {
                            throw new MallBusinessException("CATEGORY_HAS_ITEMS", "该分类仍有商品引用，请先调整商品分类，不能直接删除。");
                        }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM categories WHERE category_id = ?")) {
                    statement.setLong(1, id);
                    if (statement.executeUpdate() != 1) throw new SQLException("Unexpected category delete count");
                }
                return true;
            });
        } catch (SQLException failure) {
            if (failure.getErrorCode() == 1451) {
                throw new MallBusinessException("CATEGORY_REFERENCED", "该分类仍被其他记录引用，请刷新后检查下级分类和商品。");
            }
            throw new MallDataAccessException("CATEGORY_DELETE_UNCONFIRMED",
                    "分类删除结果未确认，请先刷新分类树或查询categories表，再决定是否重试。");
        }
    }

    private static List<CategoryView> lockedTree(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_ALL + " FOR UPDATE");
             ResultSet rows = statement.executeQuery()) {
            List<CategoryView> result = new ArrayList<>();
            while (rows.next()) result.add(readRow(rows));
            return result;
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
