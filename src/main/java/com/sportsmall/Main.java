package com.sportsmall;

import com.zaxxer.hikari.HikariDataSource;
import com.sportsmall.cli.RegisterCommand;
import com.sportsmall.cli.LoginCommand;
import com.sportsmall.desktop.MallDesktop;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.sportsmall.config.DatabaseConfig;
import com.sportsmall.config.MongoConfig;
import com.sportsmall.dao.ItemDao;
import com.sportsmall.dao.mongo.ItemDetailDao;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.ItemDetail;
import com.sportsmall.service.CatalogService;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** 商城入口：读取 MySQL 基础信息，按商品编号组合 MongoDB 详情。 */
public final class Main {
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) {
        // 统一 Windows 终端和重定向输出的中文编码。
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        int exitCode;
        if (args.length == 0) {
            exitCode = run();
        } else if (args.length == 1 && "register".equals(args[0])) {
            exitCode = RegisterCommand.run();
        } else if (args.length == 1 && "login".equals(args[0])) {
            exitCode = LoginCommand.run();
        } else if (args.length == 1 && "gui".equals(args[0])) {
            MallDesktop.open();
            exitCode = 0;
        } else {
            System.out.println("运行商城：java -jar .\\target\\sports-mall-0.1.0.jar");
            System.out.println("注册账号：java -jar .\\target\\sports-mall-0.1.0.jar register");
            System.out.println("登录账号：java -jar .\\target\\sports-mall-0.1.0.jar login");
            System.out.println("窗口界面：java -jar .\\target\\sports-mall-0.1.0.jar gui");
            exitCode = args.length == 1 && ("help".equals(args[0]) || "--help".equals(args[0])) ? 0 : 1;
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public static int run() {
        try {
            DatabaseConfig config = DatabaseConfig.load();
            MongoConfig mongoConfig = MongoConfig.load();
            try (HikariDataSource dataSource = config.openDataSource();
                 MongoClient mongoClient = mongoConfig.openClient()) {
                showConnection(dataSource);
                MongoDatabase mongoDatabase = mongoClient.getDatabase(mongoConfig.database());
                mongoDatabase.runCommand(new Document("ping", 1));
                System.out.printf("连接成功：MongoDB / %s%n", mongoDatabase.getName());
                CatalogService catalog = new CatalogService(new ItemDao(dataSource), new ItemDetailDao(mongoDatabase));
                List<CatalogService.CatalogEntry> items = catalog.findAll();
                if (items.isEmpty()) {
                    System.out.println("查询结果为空，没有可显示的商品。");
                } else {
                    System.out.println("商品编号 | 商品名称 | 单价（元） | 分类编号 | 分类名称");
                    for (CatalogService.CatalogEntry entry : items) {
                        var item = entry.item();
                        System.out.printf("%d | %s | %s | %d | %s%n",
                                item.itemId(), item.title(), item.price().setScale(2).toPlainString(),
                                item.categoryId(), item.categoryName());
                        if (entry.detail().isPresent()) {
                            showDetail(entry.detail().get());
                        } else {
                            System.out.println("  商品详情：尚未填写。");
                        }
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
        } catch (MongoException exception) {
            MallDataAccessException failure = MallDataAccessException.fromMongoFailure(exception);
            LOG.error("商品查询未完成，错误代码={}", failure.code());
            System.err.printf("[%s] %s%n", failure.code(), failure.getMessage());
            return 1;
        } catch (RuntimeException exception) {
            LOG.error("程序未完成，错误类型={}", exception.getClass().getName());
            System.err.println("[UNEXPECTED_ERROR] 程序未完成。请保留错误类型并检查 JDK 和项目依赖是否配置正确。");
            return 1;
        }
    }

    private static void showDetail(ItemDetail detail) {
        System.out.printf("  商品说明：%s%n", detail.description());
        System.out.printf("  内容语言：%s%n", detail.language());
        System.out.printf("  商品图片：%s%n", detail.images().isEmpty() ? "暂无图片" : String.join("，", detail.images()));
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
