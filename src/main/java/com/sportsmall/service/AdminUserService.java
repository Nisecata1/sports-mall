package com.sportsmall.service;

import com.sportsmall.dao.mysql.UserDao;
import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.model.AdminUserSummary;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.UserAccount;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntFunction;

/** 每次查询先读取账号当前角色和状态；登录时的角色快照不授予管理权限。 */
public final class AdminUserService {
    public static final int LIST_LIMIT = 100;
    private final Function<AuthenticatedUser, UserAccount> accounts;
    private final IntFunction<List<AdminUserSummary>> users;

    public AdminUserService(UserAccountService accounts, UserDao users) {
        this(Objects.requireNonNull(accounts)::viewMyAccount, Objects.requireNonNull(users)::findSummaries);
    }

    AdminUserService(Function<AuthenticatedUser, UserAccount> accounts,
                     IntFunction<List<AdminUserSummary>> users) {
        this.accounts = Objects.requireNonNull(accounts);
        this.users = Objects.requireNonNull(users);
    }

    public List<AdminUserSummary> listUsers(AuthenticatedUser currentUser) {
        UserAccount account = accounts.apply(currentUser);
        if (!"ADMIN".equals(account.role())) {
            throw new MallBusinessException("ADMIN_REQUIRED", "此操作仅限管理员账号。");
        }
        return List.copyOf(users.apply(LIST_LIMIT));
    }
}
