package com.sportsmall.util;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** 一次借用一个连接执行事务，SQL成功后提交，执行或提交异常时尝试回滚。 */
public final class JdbcTransactions {
    private JdbcTransactions() {
    }

    public static <T> T execute(DataSource dataSource, SqlWork<T> work) throws SQLException {
        Objects.requireNonNull(work);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException | Error failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
            // 连接在这里关闭/归还HikariCP；不切回自动提交来结束未完成事务。
        }
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }
}
