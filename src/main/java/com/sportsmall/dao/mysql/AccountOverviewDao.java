package com.sportsmall.dao.mysql;

import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.AccountOverview;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/** 查询两个已安装视图，用户编号始终为PreparedStatement参数。 */
public final class AccountOverviewDao {
    private static final String FIND_MINE = """
            SELECT d.user_id, d.username, d.real_name, d.address,
                   s.order_count, s.pending_count, s.completed_count, s.cancelled_count, s.completed_amount
            FROM v_user_details d
            JOIN v_user_order_summary s ON s.user_id = d.user_id
            WHERE d.user_id = ?
            """;
    private final DataSource dataSource;

    public AccountOverviewDao(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    public Optional<AccountOverview> findByUserId(long userId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_MINE)) {
            statement.setLong(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) { return Optional.empty(); }
                return Optional.of(new AccountOverview(rows.getLong("user_id"), rows.getString("username"),
                        rows.getString("real_name"), rows.getString("address"), rows.getLong("order_count"),
                        rows.getLong("pending_count"), rows.getLong("completed_count"), rows.getLong("cancelled_count"),
                        rows.getBigDecimal("completed_amount")));
            }
        } catch (SQLException exception) {
            if (exception.getErrorCode() == 1146) {
                throw new MallDataAccessException("OVERVIEW_VIEWS_NOT_INSTALLED",
                        "尚未找到概览视图，请先在sports_mall执行mysql_views.sql。");
            }
            throw new MallDataAccessException("OVERVIEW_QUERY_FAILED", "无法读取本人概览，请检查MySQL连接和两个视图的定义。");
        }
    }
}
