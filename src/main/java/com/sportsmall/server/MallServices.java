package com.sportsmall.server;

import com.mongodb.client.*;
import com.sportsmall.config.*;
import com.sportsmall.dao.ItemDao;
import com.sportsmall.dao.mongo.*;
import com.sportsmall.dao.mysql.*;
import com.sportsmall.service.*;
import com.zaxxer.hikari.HikariDataSource;
import org.bson.Document;

/** 后端拥有连接池与数据库客户端；界面不创建这些资源。 */
public final class MallServices implements AutoCloseable {
    private final HikariDataSource sql;
    private final MongoClient mongo;
    final UserRegistrationService registration;
    final UserLoginService login;
    final UserAccountService accounts;
    final OrderService orders;
    final CommentService comments;
    final StatisticsService statistics;
    final BrowsingService browsing;
    final AccountOverviewService overviews;
    final MonthlyOrderService monthlyOrders;
    final ProfileService profiles;
    final AdminUserService adminUsers;
    final CategoryService categories;

    public MallServices(HikariDataSource sql, MongoClient mongo, MongoDatabase database) {
        this.sql = sql; this.mongo = mongo;
        UserDao users = new UserDao(sql); ItemDao items = new ItemDao(sql);
        CommentDao commentDao = new CommentDao(database); ActionLogDao actions = new ActionLogDao(database);
        registration = new UserRegistrationService(users);
        login = new UserLoginService(users, new SystemLogDao(database));
        accounts = new UserAccountService(users); orders = new OrderService(accounts, new OrderDao(sql));
        comments = new CommentService(accounts, items, commentDao);
        statistics = new StatisticsService(accounts, items, commentDao, actions);
        browsing = BrowsingService.forDesktop(accounts, items, new ItemDetailDao(database), actions);
        overviews = new AccountOverviewService(accounts, new AccountOverviewDao(sql));
        monthlyOrders = new MonthlyOrderService(accounts, new OrderDao(sql));
        profiles = new ProfileService(accounts, new ProfileDao(sql));
        adminUsers = new AdminUserService(accounts, users);
        categories = new CategoryService(accounts, new CategoryDao(sql));
    }
    public static MallServices open() {
        HikariDataSource sql = DatabaseConfig.load().openDataSource();
        try {
            MongoConfig config = MongoConfig.load();
            MongoClient mongo = config.openClient();
            try {
                MongoDatabase database = mongo.getDatabase(config.database());
                database.runCommand(new Document("ping", 1));
                return new MallServices(sql, mongo, database);
            } catch (RuntimeException | Error failure) { mongo.close(); throw failure; }
        } catch (RuntimeException | Error failure) { sql.close(); throw failure; }
    }
    @Override public void close() { try { mongo.close(); } finally { sql.close(); } }
}
