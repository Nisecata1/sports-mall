package com.sportsmall;

import com.zaxxer.hikari.HikariDataSource;
import com.sportsmall.config.DatabaseConfig;
import com.sportsmall.dao.ItemDao;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.ItemView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** 本轮入口：连接你已经创建的商城数据库，并显示实际查询结果。 */
public final class Main {
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) {
        // 统一 Windows 终端和重定向输出的中文编码。
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        int exitCode = run();
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public static int run() {
        try {
            DatabaseConfig config = DatabaseConfig.load();
            try (HikariDataSource dataSource = config.openDataSource()) {
                showConnection(dataSource);
                List<ItemView> items = new ItemDao(dataSource).findAll();
                if (items.isEmpty()) {
                    System.out.println("查询结果为空，没有可显示的商品。");
                } else {
                    System.out.println("商品编号 | 商品名称 | 单价（元） | 分类编号 | 分类名称");
                    for (ItemView item : items) {
                        System.out.printf("%d | %s | %s | %d | %s%n",
                                item.itemId(), item.title(), item.price().setScale(2).toPlainString(),
                                item.categoryId(), item.categoryName());
                    }
                }
                System.out.printf("共读取 %d 条商品。%n", items.size());
                LOG.info("商品查询完成，结果数量={}", items.size());
            }
            return 0;
        } catch (MallDataAccessException exception) {
            // 不输出原始异常内容或连接属性，避免将密码写进终端和日志。
            LOG.error("商品查询未完成，错误代码={}", exception.code());
            System.err.printf("[%s] %s%n", exception.code(), exception.getMessage());
            return 1;
        } catch (RuntimeException exception) {
            LOG.error("程序未完成，错误类型={}", exception.getClass().getName());
            System.err.println("[UNEXPECTED_ERROR] 程序未完成。请保留错误类型并检查 JDK 和项目依赖是否配置正确。");
            return 1;
        }
    }

    private static void showConnection(HikariDataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            System.out.printf("连接成功：%s %s / %s%n",
                    connection.getMetaData().getDatabaseProductName(),
                    connection.getMetaData().getDatabaseProductVersion(),
                    connection.getCatalog());
        } catch (SQLException exception) {
            throw MallDataAccessException.fromDatabaseFailure(exception);
        }
    }
}
