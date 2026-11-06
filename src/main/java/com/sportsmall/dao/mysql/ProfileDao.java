package com.sportsmall.dao.mysql;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.UserProfile;
import com.sportsmall.util.JdbcTransactions;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/** 唯一user_id负责重复保存，用户行锁使同一账号的档案写操作按顺序完成。 */
public final class ProfileDao {
    private static final String FIND_MINE = """
            SELECT profile_id, user_id, real_name, id_card, address, notes
            FROM profiles WHERE user_id = ?
            """;
    private static final String LOCK_ACCOUNT = "SELECT status FROM users WHERE user_id = ? FOR UPDATE";
    private static final String SAVE_MINE = """
            INSERT INTO profiles(user_id, real_name, id_card, address, notes)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE real_name = ?, id_card = ?, address = ?, notes = ?
            """;
    private final DataSource dataSource;

    public ProfileDao(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    public Optional<UserProfile> findByUserId(long userId) {
        try (Connection connection = dataSource.getConnection()) {
            return read(connection, userId);
        } catch (SQLException failure) {
            throw new MallDataAccessException("PROFILE_QUERY_FAILED", "本人档案查询未完成，请检查数据库连接和profiles表。");
        }
    }

    public UserProfile save(long userId, String realName, String idCard, String address, String notes) {
        try {
            return JdbcTransactions.execute(dataSource, connection -> {
                requireActiveAccount(connection, userId);
                try (PreparedStatement statement = connection.prepareStatement(SAVE_MINE)) {
                    statement.setLong(1, userId);
                    statement.setString(2, realName);
                    statement.setString(3, idCard);
                    statement.setString(4, address);
                    statement.setString(5, notes);
                    statement.setString(6, realName);
                    statement.setString(7, idCard);
                    statement.setString(8, address);
                    statement.setString(9, notes);
                    statement.executeUpdate();
                }
                UserProfile saved = read(connection, userId).orElseThrow(() ->
                        new MallDataAccessException("PROFILE_SAVE_UNCONFIRMED", "保存后未找到本人档案，请重新查询档案。"));
                if (!Objects.equals(saved.realName(), realName) || !Objects.equals(saved.idCard(), idCard)
                        || !Objects.equals(saved.address(), address) || !Objects.equals(saved.notes(), notes)) {
                    throw new MallDataAccessException("PROFILE_SAVE_UNCONFIRMED", "保存后的档案与本次提交不一致，请重新查询。");
                }
                return saved;
            });
        } catch (SQLException failure) {
            throw new MallDataAccessException("PROFILE_SAVE_UNCONFIRMED", "档案保存结果未确认，请先查看本人档案，再决定是否重新保存。");
        }
    }

    public boolean deleteByUserId(long userId) {
        try {
            return JdbcTransactions.execute(dataSource, connection -> {
                requireActiveAccount(connection, userId);
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM profiles WHERE user_id = ?")) {
                    statement.setLong(1, userId);
                    int changed = statement.executeUpdate();
                    if (changed > 1) {
                        throw new MallDataAccessException("PROFILE_DATA_INVALID", "同一用户出现多份档案，请检查唯一约束。");
                    }
                    return changed == 1;
                }
            });
        } catch (SQLException failure) {
            throw new MallDataAccessException("PROFILE_DELETE_UNCONFIRMED", "档案删除结果未确认，请先重新查看本人档案。");
        }
    }

    private static Optional<UserProfile> read(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_MINE)) {
            statement.setLong(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                UserProfile profile = new UserProfile(rows.getLong("user_id"), rows.getLong("profile_id"),
                        rows.getString("real_name"), rows.getString("id_card"), rows.getString("address"), rows.getString("notes"));
                if (rows.next()) {
                    throw new MallDataAccessException("PROFILE_DATA_INVALID", "同一用户出现多份档案，请检查唯一约束。");
                }
                return Optional.of(profile);
            }
        }
    }

    private static void requireActiveAccount(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_ACCOUNT)) {
            statement.setLong(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new MallBusinessException("ACCOUNT_NOT_FOUND", "当前商城账号已不存在，请重新登录。");
                if (rows.getInt("status") != 1) {
                    throw new MallBusinessException("ACCOUNT_DISABLED", "当前商城账号已停用，请重新登录或联系管理员。");
                }
            }
        }
    }
}
