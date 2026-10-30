package com.sportsmall.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.sportsmall.exception.MallDataAccessException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** MongoDB 地址来自配置；可用进程环境覆盖含凭据的连接地址。 */
public final class MongoConfig {
    private final ConnectionString connectionString;
    private final String database;
    private final int timeoutMs;

    private MongoConfig(ConnectionString connectionString, String database, int timeoutMs) {
        this.connectionString = connectionString;
        this.database = database;
        this.timeoutMs = timeoutMs;
    }

    public static MongoConfig load() {
        Properties properties = new Properties();
        try (InputStream input = MongoConfig.class.getResourceAsStream("/application.properties")) {
            if (input == null) {
                throw new MallDataAccessException("CONFIG_NOT_FOUND", "找不到 application.properties，请重新构建工程。");
            }
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new MallDataAccessException("CONFIG_READ_FAILED", "无法读取 application.properties，请检查配置文件。");
        }

        String uri = System.getenv("MALL_MONGO_URI");
        if (uri == null || uri.isBlank()) {
            uri = properties.getProperty("mongo.uri", "").trim();
        }
        String database = properties.getProperty("mongo.database", "").trim();
        if (uri.isBlank() || database.isBlank()) {
            throw new MallDataAccessException("MONGO_CONFIG_MISSING", "请在 application.properties 填写 mongo.uri 和 mongo.database。");
        }
        try {
            int timeout = Integer.parseInt(properties.getProperty("mongo.timeoutMs", "5000").trim());
            if (timeout < 1) {
                throw new IllegalArgumentException();
            }
            return new MongoConfig(new ConnectionString(uri), database, timeout);
        } catch (IllegalArgumentException exception) {
            throw new MallDataAccessException("MONGO_CONFIG_INVALID", "请检查 MongoDB 连接地址与大于0的 mongo.timeoutMs。");
        }
    }

    public MongoClient openClient() {
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .applicationName("sports-mall")
                .applyToClusterSettings(builder -> builder.serverSelectionTimeout(timeoutMs, TimeUnit.MILLISECONDS))
                .applyToSocketSettings(builder -> builder
                        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS))
                .build();
        return MongoClients.create(settings);
    }

    public String database() {
        return database;
    }
}
