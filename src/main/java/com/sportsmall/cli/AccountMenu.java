package com.sportsmall.cli;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.UserAccount;
import com.sportsmall.service.UserAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Console;

/** 登录后的本机交互菜单；当前身份一直由已通过登录的结果传入。 */
public final class AccountMenu {
    private static final Logger LOG = LoggerFactory.getLogger(AccountMenu.class);

    private AccountMenu() {
    }

    public static void run(Console console, AuthenticatedUser currentUser, UserAccountService accounts) {
        while (true) {
            console.printf("%n当前用户：%s（编号%d）%n", currentUser.username(), currentUser.userId());
            console.printf("1. 查看我的账号资料%n2. 修改我的联系电话%n0. 退出登录%n");
            String option = console.readLine("请选择：");
            if (option == null || "0".equals(option.strip())) {
                System.out.println("已退出登录，返回PowerShell。");
                return;
            }
            try {
                switch (option.strip()) {
                    case "1" -> showAccount(accounts.viewMyAccount(currentUser));
                    case "2" -> {
                        String phone = console.readLine("新的联系电话（最多20个字符，留空清除）：");
                        if (phone == null) {
                            System.out.println("已退出登录，返回PowerShell。");
                            return;
                        }
                        var result = accounts.changeMyPhone(currentUser, phone);
                        System.out.println(result.changed() ? "联系电话已保存。" : "联系电话与现有记录相同，无需修改。");
                        showAccount(result.account());
                        LOG.info("账号联系方式处理完成，用户编号={}，实际修改={}", currentUser.userId(), result.changed());
                    }
                    default -> System.out.println("请选择0、1或2。");
                }
            } catch (MallBusinessException exception) {
                LOG.warn("账号操作未完成，错误代码={}", exception.code());
                System.err.printf("[%s] %s%n", exception.code(), exception.getMessage());
                if ("ACCOUNT_DISABLED".equals(exception.code()) || "ACCOUNT_NOT_FOUND".equals(exception.code())
                        || "LOGIN_REQUIRED".equals(exception.code())) {
                    return;
                }
            } catch (MallDataAccessException exception) {
                LOG.error("账号操作未完成，错误代码={}", exception.code());
                System.err.printf("[%s] %s%n", exception.code(), exception.getMessage());
            }
        }
    }

    private static void showAccount(UserAccount account) {
        System.out.printf("用户编号：%d%n用户名：%s%n邮箱：%s%n联系电话：%s%n角色：%s%n状态：%s%n",
                account.userId(), account.username(), account.email(),
                account.phone() == null ? "未填写" : account.phone(),
                account.role(), account.status() == 1 ? "正常" : "停用");
    }
}
