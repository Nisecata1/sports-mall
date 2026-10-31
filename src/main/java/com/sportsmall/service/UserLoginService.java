package com.sportsmall.service;

import com.sportsmall.dao.mongo.SystemLogDao;
import com.sportsmall.dao.mysql.UserDao;
import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.LoginAccount;
import com.sportsmall.util.PasswordHasher;

import java.util.Objects;
import java.util.Optional;

/** 先核验 MySQL 账号，再记录 MongoDB 登录结果；两者职责由业务层协调。 */
public final class UserLoginService {
    private final AccountReader reader;
    private final LoginLogWriter logWriter;

    public UserLoginService(UserDao users, SystemLogDao logs) {
        this(Objects.requireNonNull(users)::findForLogin, Objects.requireNonNull(logs)::recordLogin);
    }

    UserLoginService(AccountReader reader, LoginLogWriter logWriter) {
        this.reader = Objects.requireNonNull(reader);
        this.logWriter = Objects.requireNonNull(logWriter);
    }

    public AuthenticatedUser login(String username, char[] password, String sourceIp) {
        String checkedUsername = username == null ? "" : username.strip();
        if (checkedUsername.isEmpty() || checkedUsername.codePointCount(0, checkedUsername.length()) > 50) {
            throw new MallBusinessException("INVALID_USERNAME", "用户名不能为空，且不能超过50个字符。");
        }
        if (password == null || password.length == 0) {
            throw new MallBusinessException("PASSWORD_REQUIRED", "请输入注册时设置的商城密码。");
        }
        Objects.requireNonNull(sourceIp);
        Optional<LoginAccount> found = reader.find(checkedUsername);
        if (found.isEmpty()) {
            logWriter.record(null, false, sourceIp);
            throw invalidCredentials();
        }

        LoginAccount account = found.get();
        if (!PasswordHasher.verify(password, account.passwordHash())) {
            logWriter.record(account.userId(), false, sourceIp);
            throw invalidCredentials();
        }
        if (account.status() != 1) {
            logWriter.record(account.userId(), false, sourceIp);
            throw new MallBusinessException("ACCOUNT_DISABLED", "该商城账号当前不可用，请联系管理员。");
        }
        if (account.userId() < 1 || account.username() == null
                || !("USER".equals(account.role()) || "ADMIN".equals(account.role()))) {
            throw new MallDataAccessException("USER_DATA_INVALID", "商城账号信息不符合要求，请检查users表。");
        }

        // 只有登录日志收到写入确认，入口才报告本次完整流程成功。
        // 这不是跨库事务：本流程只读 MySQL，再向 MongoDB 写入一条日志。
        logWriter.record(account.userId(), true, sourceIp);
        return new AuthenticatedUser(account.userId(), account.username(), account.role());
    }

    private static MallBusinessException invalidCredentials() {
        return new MallBusinessException("INVALID_CREDENTIALS", "商城用户名或密码不正确，请重新登录。");
    }

    @FunctionalInterface
    interface AccountReader {
        Optional<LoginAccount> find(String username);
    }

    @FunctionalInterface
    interface LoginLogWriter {
        void record(Long userId, boolean succeeded, String sourceIp);
    }
}
