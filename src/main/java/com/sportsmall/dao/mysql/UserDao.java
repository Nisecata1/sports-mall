package com.sportsmall.dao.mysql;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.RegisteredUser;
import com.sportsmall.model.LoginAccount;
import com.sportsmall.model.UserAccount;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Objects;
import java.util.Optional;

/** 读写商城账号；密码校验在业务层执行，普通注册固定为 USER。 */
public final class UserDao {
    private static final String INSERT_USER = """
            INSERT INTO users (username, password_hash, email, phone, role, status)
            VALUES (?, ?, ?, ?, 'USER', 1)
            """;

    private final DataSource dataSource;

    private static final String FIND_LOGIN_ACCOUNT = """
            SELECT user_id, username, password_hash, role, status
            FROM users
            WHERE username = ?
            """;

    private static final String FIND_ACCOUNT_BY_ID = """
            SELECT user_id, username, email, phone, role, status
            FROM users
            WHERE user_id = ?
            """;

    private static final String UPDATE_ACTIVE_PHONE = """
            UPDATE users SET phone = ? WHERE user_id = ? AND status = 1
            """;

    public UserDao(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    public Optional<LoginAccount> findForLogin(String username) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_LOGIN_ACCOUNT)) {
            statement.setString(1, username);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new LoginAccount(rows.getLong("user_id"), rows.getString("username"),
                        rows.getString("password_hash"), rows.getString("role"), rows.getInt("status")));
            }
        } catch (SQLException exception) {
            throw new MallDataAccessException("USER_QUERY_FAILED", "无法读取商城账号，请检查MySQL连接与users表。");
        }
    }

    public Optional<UserAccount> findAccountById(long userId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_ACCOUNT_BY_ID)) {
            statement.setLong(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new UserAccount(rows.getLong("user_id"), rows.getString("username"),
                        rows.getString("email"), rows.getString("phone"),
                        rows.getString("role"), rows.getInt("status")));
            }
        } catch (SQLException exception) {
            throw new MallDataAccessException("USER_QUERY_FAILED", "无法读取商城账号资料，请检查MySQL连接与users表。");
        }
    }

    public boolean updateActivePhone(long userId, String phone) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPDATE_ACTIVE_PHONE)) {
            if (phone == null) {
                statement.setNull(1, Types.VARCHAR);
            } else {
                statement.setString(1, phone);
            }
            statement.setLong(2, userId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new MallDataAccessException("USER_UPDATE_FAILED",
                    "联系方式未确认保存，请重新查看账号资料并检查MySQL连接。");
        }
    }

    public RegisteredUser create(String username, String passwordHash, String email, String phone) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_USER, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, username);
            statement.setString(2, passwordHash);
            statement.setString(3, email);
            if (phone == null) {
                statement.setNull(4, Types.VARCHAR);
            } else {
                statement.setString(4, phone);
            }

            if (statement.executeUpdate() != 1) {
                throw new SQLException("Unexpected number of inserted users");
            }
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Generated user ID is unavailable");
                }
                long userId = keys.getLong(1);
                if (userId < 1) {
                    throw new SQLException("Generated user ID is invalid");
                }
                return new RegisteredUser(userId, username, email);
            }
        } catch (SQLException exception) {
            if (exception.getErrorCode() == 1062) {
                throw new MallBusinessException("ACCOUNT_ALREADY_EXISTS", "该用户名或邮箱已被使用，请换一个。");
            }
            throw new MallDataAccessException("USER_SAVE_FAILED", "用户注册未完成，请检查数据库连接与users表。");
        }
    }
}
