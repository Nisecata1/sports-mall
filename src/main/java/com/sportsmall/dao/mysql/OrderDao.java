package com.sportsmall.dao.mysql;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.OrderFilter;
import com.sportsmall.model.OrderableItem;
import com.sportsmall.model.OrderView;
import com.sportsmall.util.JdbcTransactions;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** MySQL订单读写；创建时所有判断、取价和插入使用同一个事务连接。 */
public final class OrderDao {
    private static final String AVAILABLE_ITEMS = """
            SELECT item_id, title, price FROM items WHERE status = 1 ORDER BY item_id
            """;
    private static final String LOCK_USER = """
            SELECT status FROM users WHERE user_id = ? FOR SHARE
            """;
    private static final String LOCK_ITEM = """
            SELECT item_id, title, price, status FROM items WHERE item_id = ? FOR SHARE
            """;
    private static final String INSERT_ORDER = """
            INSERT INTO orders (user_id, item_id, amount, status) VALUES (?, ?, ?, 0)
            """;
    private static final String FIND_CREATED = """
            SELECT o.order_id, o.user_id, o.item_id, i.title AS item_title,
                   o.amount, o.status, o.created_at
            FROM orders o JOIN items i ON o.item_id = i.item_id
            WHERE o.order_id = ? AND o.user_id = ?
            """;
    private static final String FIND_MINE = """
            SELECT o.order_id, o.user_id, o.item_id, i.title AS item_title,
                   o.amount, o.status, o.created_at
            FROM orders o JOIN items i ON o.item_id = i.item_id
            WHERE o.user_id = ?
              AND (? IS NULL OR o.status = ?)
              AND (? IS NULL OR o.created_at >= ?)
              AND (? IS NULL OR o.created_at < ?)
            ORDER BY o.created_at DESC, o.order_id DESC
            """;

    private final DataSource dataSource;

    public OrderDao(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    public List<OrderableItem> findAvailableItems() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(AVAILABLE_ITEMS);
             ResultSet rows = statement.executeQuery()) {
            List<OrderableItem> items = new ArrayList<>();
            while (rows.next()) {
                items.add(new OrderableItem(rows.getLong("item_id"), rows.getString("title"), rows.getBigDecimal("price")));
            }
            return List.copyOf(items);
        } catch (SQLException exception) {
            throw new MallDataAccessException("ORDER_ITEMS_QUERY_FAILED", "无法读取可下单商品，请检查MySQL连接与items表。");
        }
    }

    public OrderView create(long userId, long itemId, BigDecimal expectedPrice) {
        try {
            return JdbcTransactions.execute(dataSource, connection -> {
                requireActiveUser(connection, userId);
                BigDecimal price = lockedItemPrice(connection, itemId, expectedPrice);
                long orderId;
                try (PreparedStatement statement = connection.prepareStatement(INSERT_ORDER, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setLong(1, userId);
                    statement.setLong(2, itemId);
                    // 只使用事务内从items读到的单价，不把界面报价作为入库金额。
                    statement.setBigDecimal(3, price);
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Unexpected inserted order count");
                    }
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next() || (orderId = keys.getLong(1)) < 1) {
                            throw new SQLException("Generated order ID is unavailable");
                        }
                    }
                }
                return readOne(connection, orderId, userId);
            });
        } catch (SQLException exception) {
            if ("45000".equals(exception.getSQLState())) {
                throw new MallBusinessException("ORDER_RULE_REJECTED", "数据库规则拒绝了本次下单，请核对商品、账号和金额。");
            }
            // 提交/连接异常时，不能断言服务器必然没有完成提交；先让用户查订单。
            throw new MallDataAccessException("ORDER_SAVE_UNCONFIRMED",
                    "下单结果未确认，请先查看我的订单，再决定是否重新下单，并检查MySQL连接。");
        }
    }

    public OrderView cancel(long userId, long orderId) {
        try {
            return JdbcTransactions.execute(dataSource, connection -> {
                requireActiveUser(connection, userId);
                try (CallableStatement statement = connection.prepareCall("{call sp_cancel_order(?, ?)}")) {
                    statement.setLong(1, userId);
                    statement.setLong(2, orderId);
                    statement.executeUpdate();
                }
                return readOne(connection, orderId, userId);
            });
        } catch (SQLException exception) {
            if (exception.getErrorCode() == 1305) {
                throw new MallDataAccessException("ORDER_ROUTINE_NOT_FOUND",
                        "尚未找到sp_cancel_order，请先在sports_mall安装mysql_procedures.sql中的存储过程。");
            }
            if ("45000".equals(exception.getSQLState())) {
                throw new MallBusinessException("ORDER_NOT_CANCELLABLE", "该订单不存在、不属于当前用户或已不处于待处理状态。");
            }
            throw new MallDataAccessException("ORDER_CANCEL_UNCONFIRMED",
                    "取消结果未确认，请先查看我的订单并检查MySQL连接。");
        }
    }

    public List<OrderView> findByUserId(long userId) {
        return findByUserId(userId, OrderFilter.all());
    }

    public List<OrderView> findByUserId(long userId, OrderFilter filter) {
        Objects.requireNonNull(filter);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_MINE)) {
            statement.setLong(1, userId);
            for (int index = 2; index <= 3; index++) {
                if (filter.status() == null) {
                    statement.setNull(index, Types.TINYINT);
                } else {
                    statement.setInt(index, filter.status());
                }
            }
            Timestamp start = filter.startInclusive() == null ? null : Timestamp.valueOf(filter.startInclusive());
            Timestamp end = filter.endExclusive() == null ? null : Timestamp.valueOf(filter.endExclusive());
            statement.setTimestamp(4, start);
            statement.setTimestamp(5, start);
            statement.setTimestamp(6, end);
            statement.setTimestamp(7, end);
            try (ResultSet rows = statement.executeQuery()) {
                List<OrderView> orders = new ArrayList<>();
                while (rows.next()) {
                    orders.add(mapOrder(rows));
                }
                return List.copyOf(orders);
            }
        } catch (SQLException exception) {
            throw new MallDataAccessException("ORDER_QUERY_FAILED", "无法读取我的订单，请检查MySQL连接与订单相关表。");
        }
    }

    private static OrderView readOne(Connection connection, long orderId, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_CREATED)) {
            statement.setLong(1, orderId);
            statement.setLong(2, userId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Order could not be read back for its owner");
                }
                return mapOrder(rows);
            }
        }
    }

    private static void requireActiveUser(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_USER)) {
            statement.setLong(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new MallBusinessException("ACCOUNT_NOT_FOUND", "当前商城账号已不存在，请重新登录。");
                }
                if (rows.getInt("status") != 1) {
                    throw new MallBusinessException("ACCOUNT_DISABLED", "当前商城账号已停用，不能创建订单。");
                }
            }
        }
    }

    private static BigDecimal lockedItemPrice(Connection connection, long itemId, BigDecimal expectedPrice) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_ITEM)) {
            statement.setLong(1, itemId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getInt("status") != 1) {
                    throw new MallBusinessException("ITEM_NOT_AVAILABLE", "该商品不存在或当前不可下单，请重新选择。");
                }
                BigDecimal price = rows.getBigDecimal("price");
                if (price == null || price.signum() <= 0) {
                    throw new MallDataAccessException("ITEM_DATA_INVALID", "商品单价不符合要求，无法创建订单。");
                }
                if (price.compareTo(expectedPrice) != 0) {
                    throw new MallBusinessException("ITEM_PRICE_CHANGED", "商品价格已变化，请重新选择商品并确认新价格。");
                }
                return price;
            }
        }
    }

    private static OrderView mapOrder(ResultSet rows) throws SQLException {
        return new OrderView(rows.getLong("order_id"), rows.getLong("user_id"), rows.getLong("item_id"),
                rows.getString("item_title"), rows.getBigDecimal("amount"), rows.getInt("status"),
                rows.getTimestamp("created_at").toLocalDateTime());
    }
}
