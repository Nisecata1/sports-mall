package com.sportsmall.dao.mysql;

import com.sportsmall.api.ApiJson;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.*;
import com.sportsmall.util.JdbcTransactions;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** JDBC批处理保存导入暂存记录；MySQL事务不包含随后执行的MongoDB写入。 */
public final class LogImportDao {
    private final DataSource source;
    public LogImportDao(DataSource source) { this.source = Objects.requireNonNull(source); }

    public void stage(long actor, String batchId, List<LogImportEntry> rows) {
        try {
            JdbcTransactions.execute(source, connection -> {
                AdminUserMutationDao.requireActiveAdmin(connection, actor);
                try (var statement = connection.prepareStatement("""
                        INSERT INTO log_import_rows(batch_id,row_no,imported_by,payload)
                        VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE batch_id=log_import_rows.batch_id
                        """)) {
                    int rowNo = 0;
                    for (var row : rows) {
                        statement.setString(1, batchId); statement.setInt(2, ++rowNo);
                        statement.setLong(3, actor); statement.setString(4, ApiJson.GSON.toJson(row));
                        statement.addBatch();
                        if (rowNo % 50 == 0) { checkBatch(statement.executeBatch()); statement.clearBatch(); }
                    }
                    if (rowNo % 50 != 0) checkBatch(statement.executeBatch());
                }
                return null;
            });
        } catch (SQLException failure) { throw unavailable(); }
    }
    public List<LogImportRow> pending(long actor, String batchId) {
        try (var connection = source.getConnection(); var statement = connection.prepareStatement("""
                SELECT row_no,payload FROM log_import_rows
                WHERE batch_id=? AND imported_by=? AND sync_status='PENDING' ORDER BY row_no LIMIT 200
                """)) {
            statement.setString(1, batchId); statement.setLong(2, actor);
            List<LogImportRow> found = new ArrayList<>();
            try (var rows = statement.executeQuery()) {
                while (rows.next()) found.add(new LogImportRow(rows.getInt(1), ApiJson.GSON.fromJson(rows.getString(2), LogImportEntry.class)));
            }
            return List.copyOf(found);
        } catch (SQLException failure) { throw unavailable(); }
    }
    public void markSynced(long actor, String batchId, List<LogImportRow> rows) {
        try {
            JdbcTransactions.execute(source, connection -> {
                try (var statement = connection.prepareStatement("""
                        UPDATE log_import_rows SET sync_status='SYNCED',synced_at=CURRENT_TIMESTAMP
                        WHERE batch_id=? AND row_no=? AND imported_by=? AND sync_status='PENDING'
                        """)) {
                    for (var row : rows) {
                        statement.setString(1, batchId); statement.setInt(2, row.rowNo());
                        statement.setLong(3, actor); statement.addBatch();
                    }
                    checkBatch(statement.executeBatch());
                }
                return null;
            });
        } catch (SQLException failure) { throw unavailable(); }
    }
    public LogImportResult summary(long actor, String batchId) {
        try (var connection = source.getConnection(); var statement = connection.prepareStatement("""
                SELECT COUNT(*),COALESCE(SUM(sync_status='SYNCED'),0) FROM log_import_rows
                WHERE batch_id=? AND imported_by=?
                """)) {
            statement.setString(1, batchId); statement.setLong(2, actor);
            try (var rows = statement.executeQuery()) {
                rows.next(); int total = rows.getInt(1), synced = rows.getInt(2);
                return new LogImportResult(batchId, total, synced, total - synced);
            }
        } catch (SQLException failure) { throw unavailable(); }
    }
    private static void checkBatch(int[] results) throws SQLException {
        for (int value : results) if (value == Statement.EXECUTE_FAILED) throw new SQLException("Batch row failed");
    }
    private static MallDataAccessException unavailable() {
        return new MallDataAccessException("LOG_IMPORT_SQL_UNCONFIRMED",
                "导入暂存结果未确认。请确认已执行mysql_log_import.sql；查看暂存状态后，可重新导入同一文件继续同步。");
    }
}
