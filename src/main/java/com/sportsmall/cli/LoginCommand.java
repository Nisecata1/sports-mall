package com.sportsmall.cli;

import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.sportsmall.config.DatabaseConfig;
import com.sportsmall.config.MongoConfig;
import com.sportsmall.dao.ItemDao;
import com.sportsmall.dao.mongo.CommentDao;
import com.sportsmall.dao.mongo.SystemLogDao;
import com.sportsmall.dao.mysql.UserDao;
import com.sportsmall.dao.mysql.OrderDao;
import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.service.UserLoginService;
import com.sportsmall.service.UserAccountService;
import com.sportsmall.service.OrderService;
import com.sportsmall.service.CommentService;
import com.sportsmall.service.StatisticsService;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Console;
import java.util.Arrays;

/** 本机命令行登录入口，负责隐藏密码与管理连接资源。 */
public final class LoginCommand {
    private static final Logger LOG = LoggerFactory.getLogger(LoginCommand.class);
    private static final String LOCAL_SOURCE_IP = "127.0.0.1";

    private LoginCommand() {
    }

    public static int run() {
        char[] password = null;
        try {
            Console console = System.console();
            if (console == null) {
                throw new MallBusinessException("INTERACTIVE_CONSOLE_REQUIRED",
                        "登录需要可隐藏密码的交互终端，请在PowerShell中直接运行 java -jar .\\target\\sports-mall-0.1.0.jar login。");
            }
            DatabaseConfig databaseConfig = DatabaseConfig.load();
            MongoConfig mongoConfig = MongoConfig.load();
            console.printf("商城用户登录：使用已注册的商城账号。%n");
            String username = console.readLine("商城用户名：");
            if (username == null) {
                throw cancelled();
            }
            password = console.readPassword("商城密码（输入不回显）：");
            if (password == null) {
                throw cancelled();
            }

            try (HikariDataSource dataSource = databaseConfig.openDataSource();
                 MongoClient mongoClient = mongoConfig.openClient()) {
                UserLoginService service = new UserLoginService(new UserDao(dataSource),
                        new SystemLogDao(mongoClient.getDatabase(mongoConfig.database())));
                // 本机 CLI 的来源记为回环地址；未来网络入口应传入实际请求来源。
                AuthenticatedUser user = service.login(username, password, LOCAL_SOURCE_IP);
                Arrays.fill(password, '\0');
                password = null;
                System.out.printf("登录成功：用户编号=%d，用户名=%s，角色=%s%n",
                        user.userId(), user.username(), user.role());
                System.out.println("本次登录结果已保存到MongoDB的sports_mall.system_logs。");
                LOG.info("商城用户登录成功，用户编号={}，角色={}", user.userId(), user.role());
                UserAccountService accounts = new UserAccountService(new UserDao(dataSource));
                ItemDao items = new ItemDao(dataSource);
                CommentDao commentDao = new CommentDao(mongoClient.getDatabase(mongoConfig.database()));
                CommentService comments = new CommentService(accounts, items, commentDao);
                StatisticsService statistics = new StatisticsService(accounts, items, commentDao);
                AccountMenu.run(console, user, accounts, new OrderService(accounts, new OrderDao(dataSource)), comments, statistics);
            }
            return 0;
        } catch (MallBusinessException exception) {
            LOG.warn("商城用户登录未完成，错误代码={}", exception.code());
            System.err.printf("[%s] %s%n", exception.code(), exception.getMessage());
            return 1;
        } catch (MallDataAccessException exception) {
            LOG.error("商城用户登录未完成，错误代码={}", exception.code());
            System.err.printf("[%s] %s%n", exception.code(), exception.getMessage());
            return 1;
        } catch (MongoException exception) {
            LOG.error("商城登录日志连接未完成，错误类型={}", exception.getClass().getName());
            System.err.println("[LOGIN_MONGO_FAILED] 无法完成MongoDB登录日志操作，请检查MongoDB服务和连接配置。");
            return 1;
        } catch (RuntimeException exception) {
            LOG.error("商城用户登录未完成，错误类型={}", exception.getClass().getName());
            System.err.println("[LOGIN_FAILED] 登录未完成，请保留错误类型并检查工程运行环境。");
            return 1;
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    private static MallBusinessException cancelled() {
        return new MallBusinessException("LOGIN_CANCELLED", "登录已取消。");
    }
}
