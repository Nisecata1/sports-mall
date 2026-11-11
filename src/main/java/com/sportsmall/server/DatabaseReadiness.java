package com.sportsmall.server;

import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;

/** 只验证现有连接，不写入业务数据；响应不包含地址、凭据或驱动异常。 */
final class DatabaseReadiness {
    private final DataSource sql;
    private final MongoDatabase mongo;

    DatabaseReadiness(DataSource sql, MongoDatabase mongo) {
        this.sql = Objects.requireNonNull(sql);
        this.mongo = Objects.requireNonNull(mongo);
    }

    Report check() {
        boolean mysql = mysqlReady();
        boolean mongodb = mongoReady();
        return new Report(mysql && mongodb ? "UP" : "DOWN",
                Map.of("mysql", mysql ? "UP" : "DOWN", "mongodb", mongodb ? "UP" : "DOWN"));
    }

    private boolean mysqlReady() {
        try (Connection connection = sql.getConnection()) {
            return connection.isValid(2);
        } catch (SQLException | RuntimeException failure) {
            return false;
        }
    }

    private boolean mongoReady() {
        try {
            Object ok = mongo.runCommand(new Document("ping", 1)).get("ok");
            return ok instanceof Number number && number.doubleValue() == 1;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    record Report(String status, Map<String, String> dependencies) {
        int httpStatus() { return status.equals("UP") ? 200 : 503; }
    }
}
