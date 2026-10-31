package com.sportsmall.util;

import com.sportsmall.exception.MallBusinessException;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JdbcTransactionsTest {
    @Test
    void commitsOnlyAfterWorkAndAlwaysClosesBorrowedConnection() throws SQLException {
        ConnectionProbe probe = new ConnectionProbe();
        int result = JdbcTransactions.execute(probe.dataSource(), connection -> {
            assertSame(probe.connection, connection);
            probe.events.add("write");
            return 42;
        });
        assertEquals(42, result);
        assertEquals(List.of("auto:false", "write", "commit", "close"), probe.events);
    }

    @Test
    void sqlFailureRollsBackAndKeepsOriginalFailure() {
        ConnectionProbe probe = new ConnectionProbe();
        SQLException original = new SQLException("synthetic insert failure");
        SQLException actual = assertThrows(SQLException.class, () -> JdbcTransactions.execute(probe.dataSource(), connection -> {
            probe.events.add("write");
            throw original;
        }));
        assertSame(original, actual);
        assertEquals(List.of("auto:false", "write", "rollback", "close"), probe.events);
    }

    @Test
    void businessRejectionRollsBackInsteadOfCommitting() {
        ConnectionProbe probe = new ConnectionProbe();
        MallBusinessException original = new MallBusinessException("ITEM_PRICE_CHANGED", "synthetic price change");
        MallBusinessException actual = assertThrows(MallBusinessException.class,
                () -> JdbcTransactions.execute(probe.dataSource(), connection -> { throw original; }));
        assertSame(original, actual);
        assertEquals(List.of("auto:false", "rollback", "close"), probe.events);
    }

    @Test
    void commitFailureDoesNotReturnSuccessAndAttemptsRollback() {
        ConnectionProbe probe = new ConnectionProbe();
        probe.commitFailure = new SQLException("synthetic commit failure");
        SQLException actual = assertThrows(SQLException.class,
                () -> JdbcTransactions.execute(probe.dataSource(), connection -> 42));
        assertSame(probe.commitFailure, actual);
        assertEquals(List.of("auto:false", "commit", "rollback", "close"), probe.events);
    }

    @Test
    void rollbackFailureDoesNotHideOriginalErrorOrLeakConnection() {
        ConnectionProbe probe = new ConnectionProbe();
        probe.rollbackFailure = new SQLException("synthetic rollback failure");
        SQLException original = new SQLException("synthetic write failure");
        SQLException actual = assertThrows(SQLException.class,
                () -> JdbcTransactions.execute(probe.dataSource(), connection -> { throw original; }));
        assertSame(original, actual);
        assertArrayEquals(new Throwable[] {probe.rollbackFailure}, actual.getSuppressed());
        assertEquals(List.of("auto:false", "rollback", "close"), probe.events);
    }

    /** 只模拟JDBC连接的控制接口，测试不连接数据库，不声称验证InnoDB真实行为。 */
    private static final class ConnectionProbe {
        private final List<String> events = new ArrayList<>();
        private SQLException commitFailure;
        private SQLException rollbackFailure;
        private final Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setAutoCommit" -> events.add("auto:" + args[0]);
                        case "commit" -> {
                            events.add("commit");
                            if (commitFailure != null) { throw commitFailure; }
                        }
                        case "rollback" -> {
                            events.add("rollback");
                            if (rollbackFailure != null) { throw rollbackFailure; }
                        }
                        case "close" -> events.add("close");
                        case "toString" -> { return "SyntheticConnection"; }
                        default -> throw new AssertionError("Unexpected JDBC call: " + method.getName());
                    }
                    return null;
                });

        private DataSource dataSource() {
            return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                    (proxy, method, args) -> {
                        if ("getConnection".equals(method.getName())) { return connection; }
                        throw new AssertionError("Unexpected DataSource call: " + method.getName());
                    });
        }
    }
}
