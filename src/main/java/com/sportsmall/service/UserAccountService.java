package com.sportsmall.service;

import com.sportsmall.dao.mysql.UserDao;
import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.UserAccount;

import java.util.Objects;
import java.util.Optional;

/** 当前用户只能操作自身账号：目标编号来自登录结果，不接受界面指定其他用户编号。 */
public final class UserAccountService {
    private final AccountReader reader;
    private final PhoneWriter writer;

    public UserAccountService(UserDao users) {
        this(Objects.requireNonNull(users)::findAccountById, users::updateActivePhone);
    }

    UserAccountService(AccountReader reader, PhoneWriter writer) {
        this.reader = Objects.requireNonNull(reader);
        this.writer = Objects.requireNonNull(writer);
    }

    public UserAccount viewMyAccount(AuthenticatedUser currentUser) {
        return readActiveAccount(authenticatedId(currentUser));
    }

    public PhoneChange changeMyPhone(AuthenticatedUser currentUser, String input) {
        long userId = authenticatedId(currentUser);
        String phone = input == null || input.isBlank() ? null : input.strip();
        if (phone != null && phone.codePointCount(0, phone.length()) > 20) {
            throw new MallBusinessException("INVALID_PHONE", "联系电话不能超过20个字符。");
        }
        UserAccount before = readActiveAccount(userId);
        if (Objects.equals(before.phone(), phone)) {
            return new PhoneChange(before, false);
        }
        if (!writer.update(userId, phone)) {
            throw new MallBusinessException("ACCOUNT_UPDATE_CONFLICT", "账号状态已变化或修改未确认，请重新查看资料或登录。");
        }
        UserAccount after = readActiveAccount(userId);
        if (!Objects.equals(after.phone(), phone)) {
            throw new MallDataAccessException("ACCOUNT_UPDATE_UNCONFIRMED", "联系方式与本次修改不一致，请重新查看账号资料。");
        }
        return new PhoneChange(after, true);
    }

    private static long authenticatedId(AuthenticatedUser currentUser) {
        if (currentUser == null || currentUser.userId() < 1) {
            throw new MallBusinessException("LOGIN_REQUIRED", "请先登录商城账号。");
        }
        return currentUser.userId();
    }

    private UserAccount readActiveAccount(long userId) {
        UserAccount account = reader.find(userId).orElseThrow(() ->
                new MallBusinessException("ACCOUNT_NOT_FOUND", "当前商城账号已不存在，请重新登录。"));
        if (account.userId() != userId) {
            throw new MallDataAccessException("USER_DATA_INVALID", "返回的账号编号与当前身份不一致。");
        }
        if (account.status() != 1) {
            throw new MallBusinessException("ACCOUNT_DISABLED", "当前商城账号已停用，请重新登录或联系管理员。");
        }
        return account;
    }

    public record PhoneChange(UserAccount account, boolean changed) {
    }

    @FunctionalInterface
    interface AccountReader {
        Optional<UserAccount> find(long userId);
    }

    @FunctionalInterface
    interface PhoneWriter {
        boolean update(long userId, String phone);
    }
}
