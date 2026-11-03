package com.sportsmall.service;

import com.sportsmall.dao.mysql.AccountOverviewDao;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.AccountOverview;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.UserAccount;

import java.util.Objects;
import java.util.Optional;

/** 复用登录账号限定本人概览，不接受界面传入他人编号。 */
public final class AccountOverviewService {
    private final AccountReader accounts;
    private final OverviewReader overviews;

    public AccountOverviewService(UserAccountService accounts, AccountOverviewDao overviews) {
        this(Objects.requireNonNull(accounts)::viewMyAccount, Objects.requireNonNull(overviews)::findByUserId);
    }

    AccountOverviewService(AccountReader accounts, OverviewReader overviews) {
        this.accounts = Objects.requireNonNull(accounts);
        this.overviews = Objects.requireNonNull(overviews);
    }

    public AccountOverview viewMine(AuthenticatedUser currentUser) {
        UserAccount account = accounts.read(currentUser);
        AccountOverview result = overviews.read(account.userId()).orElseThrow(() ->
                new MallDataAccessException("OVERVIEW_NOT_FOUND", "本人概览未找到，请重新登录并核对视图定义。"));
        if (result.userId() != account.userId() || result.orderCount() < 0 || result.pendingCount() < 0
                || result.completedCount() < 0 || result.cancelledCount() < 0 || result.completedAmount() == null
                || result.completedAmount().signum() < 0) {
            throw invalidOverview();
        }
        long total;
        try {
            total = Math.addExact(Math.addExact(result.pendingCount(), result.completedCount()), result.cancelledCount());
        } catch (ArithmeticException exception) {
            throw invalidOverview();
        }
        if (total != result.orderCount() || (result.completedCount() == 0) != (result.completedAmount().signum() == 0)) {
            throw invalidOverview();
        }
        return result;
    }

    private static MallDataAccessException invalidOverview() {
        return new MallDataAccessException("OVERVIEW_DATA_INVALID", "概览的用户编号、状态数量或已完成金额不一致，请核对视图。");
    }

    @FunctionalInterface interface AccountReader { UserAccount read(AuthenticatedUser user); }
    @FunctionalInterface interface OverviewReader { Optional<AccountOverview> read(long userId); }
}
