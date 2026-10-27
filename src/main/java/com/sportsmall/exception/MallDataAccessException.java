package com.sportsmall.exception;

import java.sql.SQLException;

/** 保存可用于排查的错误代码；面向用户的消息不包含连接密码。 */
public final class MallDataAccessException extends RuntimeException {
    private final String code;

    public MallDataAccessException(String code, String message) {
        super(message);
        this.code = code;
    }

    private MallDataAccessException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static MallDataAccessException fromDatabaseFailure(Throwable failure) {
        SQLException sqlException = findSqlException(failure);
        if (sqlException == null) {
            return new MallDataAccessException("CONNECTION_SETUP_FAILED",
                    "连接初始化失败。请检查 application.properties 中的连接地址、用户名，以及 MySQL 服务是否正在运行。",
                    failure);
        }

        int vendorCode = sqlException.getErrorCode();
        String state = sqlException.getSQLState();
        if (vendorCode == 1045) {
            return new MallDataAccessException("LOGIN_FAILED",
                    "MySQL 登录失败。请确认 db.user，并在当前 PowerShell 中重新输入正确的 MALL_DB_PASSWORD。",
                    failure);
        }
        if (vendorCode == 1049) {
            return new MallDataAccessException("DATABASE_NOT_FOUND",
                    "找不到 sports_mall 数据库。请确认连接的是保存商城数据的 MySQL 实例和端口。",
                    failure);
        }
        if (vendorCode == 1146) {
            return new MallDataAccessException("TABLE_NOT_FOUND",
                    "找不到商品表或分类表。请在 sports_mall 数据库中确认 items、categories 两张表已经创建。",
                    failure);
        }
        if (vendorCode == 1044 || vendorCode == 1142) {
            return new MallDataAccessException("PERMISSION_DENIED",
                    "当前 MySQL 账号没有查询商城数据的权限。请检查 db.user 及其授权。",
                    failure);
        }
        if (state != null && state.startsWith("08")) {
            return new MallDataAccessException("CONNECTION_FAILED",
                    "无法连接 MySQL。请确认数据库服务窗口仍在运行，连接地址为 127.0.0.1，端口为 18306。",
                    failure);
        }
        return new MallDataAccessException("SQL_ERROR_" + vendorCode,
                "查询失败。请检查 items、categories 的表结构，并保留屏幕上的错误代码供排查。",
                failure);
    }

    private static SQLException findSqlException(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }
}
