package com.sportsmall.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.sportsmall.exception.MallDataAccessException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** 地址和用户名来自项目配置；密码只从本次运行的终端环境读取。 */
public final class DatabaseConfig {
    private final String url;
    private final String user;
    private final String password;
    private final int poolSize;

    private DatabaseConfig(String url, String user, String password, int poolSize) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.poolSize = poolSize;
    }

    public static DatabaseConfig load() {
        Properties properties = new Properties();
        try (InputStream input = DatabaseConfig.class.getResourceAsStream("/application.properties")) {
            if (input == null) {
                throw new MallDataAccessException("CONFIG_NOT_FOUND",
                        "找不到 application.properties。请从包含 pom.xml 的项目根目录重新构建。");
            }
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new MallDataAccessException("CONFIG_READ_FAILED",
                    "无法读取 application.properties，请检查该配置文件。");
        }

        String url = requiredProperty(properties, "db.url");
        String user = requiredProperty(properties, "db.user");
        String password = System.getenv("MALL_DB_PASSWORD");
        if (password == null || password.isEmpty()) {
            throw new MallDataAccessException("PASSWORD_NOT_SET",
                    "尚未设置数据库密码。请在当前 PowerShell 执行：$env:MALL_DB_PASSWORD = Read-Host 'MySQL 密码' -MaskInput");
        }

        int poolSize;
        try {
            poolSize = Integer.parseInt(properties.getProperty("db.poolSize", "2").trim());
        } catch (NumberFormatException exception) {
            throw new MallDataAccessException("INVALID_POOL_SIZE",
                    "application.properties 中的 db.poolSize 必须是大于 0 的整数。");
        }
        if (poolSize < 1) {
            throw new MallDataAccessException("INVALID_POOL_SIZE",
                    "application.properties 中的 db.poolSize 必须是大于 0 的整数。");
        }
        return new DatabaseConfig(url, user, password, poolSize);
    }

    public HikariDataSource openDataSource() {
        HikariConfig poolConfig = new HikariConfig();
        poolConfig.setJdbcUrl(url);
        poolConfig.setUsername(user);
        poolConfig.setPassword(password);
        poolConfig.setMaximumPoolSize(poolSize);
        poolConfig.setMinimumIdle(0);
        poolConfig.setConnectionTimeout(5_000);
        poolConfig.setValidationTimeout(3_000);
        poolConfig.setInitializationFailTimeout(5_000);
        poolConfig.setPoolName("sports-mall-read-pool");
        try {
            return new HikariDataSource(poolConfig);
        } catch (RuntimeException exception) {
            throw MallDataAccessException.fromDatabaseFailure(exception);
        }
    }

    private static String requiredProperty(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new MallDataAccessException("CONFIG_VALUE_MISSING",
                    "application.properties 缺少配置项：" + key);
        }
        return value.trim();
    }
}
