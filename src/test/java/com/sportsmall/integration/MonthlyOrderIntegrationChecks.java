package com.sportsmall.integration;

import com.sportsmall.client.ApiException;
import com.sportsmall.client.MallApiClient;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;

/** 由双库HTTP探针在已验证的临时实例中调用。 */
final class MonthlyOrderIntegrationChecks {
    private static int checks;

    static int run(URI address, DataSource sql) throws Exception {
        checks = 0;
        MallApiClient owner = new MallApiClient(address);
        expectHttpFailure(() -> owner.monthlyOrders("2024-02"), 401, "monthly report requires login");
        owner.login("integration_owner", "synthetic-integration-password".toCharArray());
        seedOrder(sql, 1, "2023-12-31 23:59:59", 1);
        seedOrder(sql, 1, "2024-01-01 00:00:00", 1);
        seedOrder(sql, 1, "2024-01-31 23:59:59", 1);
        seedOrder(sql, 1, "2024-02-01 00:00:00", 1);
        seedOrder(sql, 1, "2024-02-15 12:00:00", 0);
        seedOrder(sql, 1, "2024-02-29 23:59:59", 2);
        seedOrder(sql, 1, "2024-03-01 00:00:00", 1);
        seedOrder(sql, 2, "2024-02-02 00:00:00", 1);
        seedOrder(sql, 1, "1000-01-01 00:00:00", 1);
        seedOrder(sql, 1, "9999-12-31 23:59:59", 1);

        try (Connection connection = sql.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE items SET price = 135.00 WHERE item_id = 1");
        }
        var february = owner.monthlyOrders("2024-02");
        check(february.userId() == 1 && february.monthStart().equals(LocalDate.of(2024, 2, 1)),
                "monthly report retains authenticated user and requested month");
        check(february.orderCount() == 3 && february.pendingCount() == 1
                        && february.completedCount() == 1 && february.cancelledCount() == 1,
                "leap-month first and last seconds included, adjacent months excluded");
        check(february.completedAmount().compareTo(new BigDecimal("100.00")) == 0,
                "monthly completed amount excludes pending and cancelled and keeps historical price");
        var january = owner.monthlyOrders("2024-01");
        check(january.orderCount() == 2 && january.completedAmount().compareTo(new BigDecimal("200.00")) == 0,
                "year boundary and adjacent-month separation");
        var empty = owner.monthlyOrders("2024-04");
        check(empty.orderCount() == 0 && empty.completedAmount().compareTo(BigDecimal.ZERO) == 0,
                "empty month returns real zero summary");
        check(owner.monthlyOrders("1000-01").orderCount() == 1, "minimum MySQL month supported");
        check(owner.monthlyOrders("9999-12").orderCount() == 1, "maximum MySQL month supported without overflowing date");
        MallApiClient other = new MallApiClient(address);
        other.login("integration_other", "synthetic-integration-password".toCharArray());
        var otherFebruary = other.monthlyOrders("2024-02");
        check(otherFebruary.userId() == 2 && otherFebruary.orderCount() == 1,
                "another login only receives its own monthly orders");
        expectHttpFailure(() -> owner.monthlyOrders("2024-13"), 400, "invalid calendar month rejected");
        expectHttpFailure(() -> owner.monthlyOrders("2024-02&user_id=2"), 400, "monthly query cannot inject another identity");

        expectSqlRejection(sql, null, Date.valueOf("2024-02-01"), "procedure rejects null owner");
        expectSqlRejection(sql, 0L, Date.valueOf("2024-02-01"), "procedure rejects invalid owner");
        expectSqlRejection(sql, 999L, Date.valueOf("2024-02-01"), "procedure rejects missing account");
        expectSqlRejection(sql, 3L, Date.valueOf("2024-02-01"), "procedure rejects disabled account");
        expectSqlRejection(sql, 1L, null, "procedure rejects missing month");
        expectSqlRejection(sql, 1L, Date.valueOf("2024-02-15"), "procedure rejects non-first day");
        try (Connection connection = sql.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM information_schema.ROUTINES
                     WHERE ROUTINE_SCHEMA = 'sports_mall' AND SECURITY_TYPE = 'INVOKER'
                       AND ROUTINE_NAME IN ('sp_cancel_order', 'sp_monthly_order_report')
                     """);
             var rows = statement.executeQuery()) {
            check(rows.next() && rows.getInt(1) == 2, "both course procedures installed with invoker security");
        }
        try (Connection connection = sql.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP PROCEDURE sp_monthly_order_report");
        }
        try {
            owner.monthlyOrders("2024-02");
            throw new AssertionError("Missing monthly procedure unexpectedly succeeded");
        } catch (ApiException failure) {
            check(failure.status() == 503 && failure.code().equals("MONTHLY_REPORT_ROUTINE_NOT_FOUND"),
                    "missing procedure is reported without fake empty success");
        }
        other.logout();
        owner.logout();
        return checks;
    }

    private static void seedOrder(DataSource sql, long owner, String createdAt, int targetStatus) throws SQLException {
        try (Connection connection = sql.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO orders(user_id,item_id,amount,status,created_at) VALUES(?,1,100.00,0,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, owner);
            statement.setString(2, createdAt);
            statement.executeUpdate();
            try (var keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Fixture order ID missing");
                if (targetStatus != 0) {
                    try (PreparedStatement update = connection.prepareStatement("UPDATE orders SET status = ? WHERE order_id = ?")) {
                        update.setInt(1, targetStatus);
                        update.setLong(2, keys.getLong(1));
                        update.executeUpdate();
                    }
                }
            }
        }
    }

    private static void expectSqlRejection(DataSource sql, Long owner, Date month, String label) throws SQLException {
        try (Connection connection = sql.getConnection();
             var statement = connection.prepareCall("{call sp_monthly_order_report(?,?)}")) {
            if (owner == null) statement.setNull(1, Types.BIGINT);
            else statement.setLong(1, owner);
            statement.setDate(2, month);
            try {
                statement.execute();
                throw new AssertionError("Expected SQL rejection");
            } catch (SQLException failure) {
                check("45000".equals(failure.getSQLState()), label);
            }
        }
    }

    private static void expectHttpFailure(Runnable action, int status, String label) {
        try {
            action.run();
            throw new AssertionError("Expected HTTP rejection");
        } catch (ApiException failure) {
            check(failure.status() == status, label);
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
        System.out.println("PASS " + label);
    }
}
