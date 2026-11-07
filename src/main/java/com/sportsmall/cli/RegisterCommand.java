package com.sportsmall.cli;

import com.sportsmall.config.DatabaseConfig;
import com.sportsmall.dao.mysql.UserDao;
import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.RegisteredUser;
import com.sportsmall.service.UserRegistrationService;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Console;
import java.util.Arrays;

/** 交互入口只收集输入和显示结果，注册规则交给 Service。 */
public final class RegisterCommand {
    private static final Logger LOG = LoggerFactory.getLogger(RegisterCommand.class);

    private RegisterCommand() {
    }

    public static int run() {
        char[] password = null;
        char[] confirmation = null;
        try {
            Console console = System.console();
            if (console == null) {
                throw new MallBusinessException("INTERACTIVE_CONSOLE_REQUIRED",
                        "请双击 Start-Mall.vbs 使用商城注册；历史命令行入口需要可隐藏密码的交互终端。");
            }
            DatabaseConfig config = DatabaseConfig.load();
            console.printf("商城用户注册：创建普通用户账号。%n");
            String username = readLine(console, "商城用户名：");
            String email = readLine(console, "邮箱：");
            String phone = readLine(console, "电话（可留空）：");
            password = console.readPassword("设置商城密码（输入不回显）：");
            confirmation = console.readPassword("再次输入商城密码：");
            if (password == null || confirmation == null) {
                throw new MallBusinessException("REGISTRATION_CANCELLED", "注册已取消，未创建账号。");
            }

            try (HikariDataSource dataSource = config.openDataSource()) {
                RegisteredUser user = new UserRegistrationService(new UserDao(dataSource))
                        .register(username, email, phone, password, confirmation);
                System.out.printf("注册成功：用户编号=%d，用户名=%s，邮箱=%s，角色=USER%n",
                        user.userId(), user.username(), user.email());
                LOG.info("商城用户注册成功，用户编号={}", user.userId());
            }
            return 0;
        } catch (MallBusinessException exception) {
            LOG.warn("商城用户注册未完成，错误代码={}", exception.code());
            System.err.printf("[%s] %s%n", exception.code(), exception.getMessage());
            return 1;
        } catch (MallDataAccessException exception) {
            LOG.error("商城用户注册未完成，错误代码={}", exception.code());
            System.err.printf("[%s] %s%n", exception.code(), exception.getMessage());
            return 1;
        } catch (RuntimeException exception) {
            LOG.error("商城用户注册未完成，错误类型={}", exception.getClass().getName());
            System.err.println("[REGISTRATION_FAILED] 注册未完成，请保留错误类型并检查工程运行环境。");
            return 1;
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
            if (confirmation != null) {
                Arrays.fill(confirmation, '\0');
            }
        }
    }

    private static String readLine(Console console, String prompt) {
        String value = console.readLine("%s", prompt);
        if (value == null) {
            throw new MallBusinessException("REGISTRATION_CANCELLED", "注册已取消，未创建账号。");
        }
        return value;
    }
}
