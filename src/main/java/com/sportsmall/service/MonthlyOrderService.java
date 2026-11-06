package com.sportsmall.service;

import com.sportsmall.dao.mysql.OrderDao;
import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.MonthlyOrderReport;
import com.sportsmall.model.UserAccount;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

/** 当前账号决定报表归属，月份和存储过程返回值在后端校验。 */
public final class MonthlyOrderService {
    private final AccountReader accounts;
    private final ReportReader reports;

    public MonthlyOrderService(UserAccountService accounts, OrderDao orders) {
        this(Objects.requireNonNull(accounts)::viewMyAccount, Objects.requireNonNull(orders)::monthlyReport);
    }

    MonthlyOrderService(AccountReader accounts, ReportReader reports) {
        this.accounts = Objects.requireNonNull(accounts);
        this.reports = Objects.requireNonNull(reports);
    }

    public MonthlyOrderReport viewMine(AuthenticatedUser currentUser, String monthText) {
        LocalDate monthStart = parseMonth(monthText);
        UserAccount account = accounts.read(currentUser);
        MonthlyOrderReport report = reports.read(account.userId(), monthStart);
        if (report == null || report.userId() != account.userId() || !monthStart.equals(report.monthStart())
                || report.orderCount() < 0 || report.pendingCount() < 0 || report.completedCount() < 0
                || report.cancelledCount() < 0 || report.completedAmount() == null || report.completedAmount().signum() < 0) {
            throw invalidReport();
        }
        long total;
        try {
            total = Math.addExact(Math.addExact(report.pendingCount(), report.completedCount()), report.cancelledCount());
        } catch (ArithmeticException failure) {
            throw invalidReport();
        }
        if (total != report.orderCount() || (report.completedCount() == 0) != (report.completedAmount().signum() == 0)) {
            throw invalidReport();
        }
        return report;
    }

    private static LocalDate parseMonth(String text) {
        if (text == null || !text.matches("[1-9][0-9]{3}-(0[1-9]|1[0-2])")) {
            throw new MallBusinessException("INVALID_REPORT_MONTH", "月份请填写YYYY-MM，范围为1000-01到9999-12。");
        }
        return YearMonth.parse(text).atDay(1);
    }

    private static MallDataAccessException invalidReport() {
        return new MallDataAccessException("MONTHLY_REPORT_DATA_INVALID", "月报的用户、月份、状态数量或已完成金额不一致，请检查存储过程定义。");
    }

    @FunctionalInterface
    interface AccountReader {
        UserAccount read(AuthenticatedUser currentUser);
    }

    @FunctionalInterface
    interface ReportReader {
        MonthlyOrderReport read(long userId, LocalDate monthStart);
    }
}
