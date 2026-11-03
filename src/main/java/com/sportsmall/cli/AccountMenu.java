package com.sportsmall.cli;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.AccountOverview;
import com.sportsmall.model.OrderFilter;
import com.sportsmall.model.UserAccount;
import com.sportsmall.model.OrderableItem;
import com.sportsmall.model.OrderView;
import com.sportsmall.service.UserAccountService;
import com.sportsmall.service.OrderService;
import com.sportsmall.service.CommentService;
import com.sportsmall.service.StatisticsService;
import com.sportsmall.service.BrowsingService;
import com.sportsmall.service.AccountOverviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Console;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/** 登录后的本机交互菜单；当前身份一直由已通过登录的结果传入。 */
public final class AccountMenu {
    private static final Logger LOG = LoggerFactory.getLogger(AccountMenu.class);

    private AccountMenu() {
    }

    public static void run(Console console, AuthenticatedUser currentUser,
                           UserAccountService accounts, OrderService orders, CommentService comments,
                           StatisticsService statistics, BrowsingService browsing, AccountOverviewService overviews) {
        while (true) {
            console.printf("%n当前用户：%s（编号%d）%n", currentUser.username(), currentUser.userId());
            console.printf("1. 查看我的账号资料%n2. 修改我的联系电话%n3. 创建订单%n4. 查看我的订单%n5. 取消我的待处理订单%n6. 发表评论%n7. 查看商品最新评论%n8. 查看商品评论统计%n9. 浏览商品详情%n10. 查看近7天商品浏览排行%n11. 查看我的近7天活动报告%n12. 查看我的资料与订单概览%n0. 退出登录%n");
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
                    case "3" -> createOrder(console, currentUser, orders);
                    case "4" -> queryOrders(console, currentUser, orders);
                    case "5" -> cancelOrder(console, currentUser, orders);
                    case "6" -> CommentMenu.post(console, currentUser, comments);
                    case "7" -> CommentMenu.showLatest(console, currentUser, comments);
                    case "8" -> CommentMenu.showStatistics(console, currentUser, comments, statistics);
                    case "9" -> BrowsingMenu.browse(console, currentUser, browsing);
                    case "10" -> BrowsingMenu.showHot(currentUser, statistics);
                    case "11" -> BrowsingMenu.showMyActivity(currentUser, statistics);
                    case "12" -> showOverview(overviews.viewMine(currentUser));
                    default -> System.out.println("请选择0至12中的一个菜单编号。");
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

    private static void showOverview(AccountOverview overview) {
        System.out.printf("我的资料与订单概览：%s（编号%d）%n真实姓名：%s%n地址：%s%n",
                overview.username(), overview.userId(), overview.realName() == null ? "未填写" : overview.realName(),
                overview.address() == null ? "未填写" : overview.address());
        System.out.printf("总订单数：%d%n待处理：%d%n已完成：%d%n已取消：%d%n已完成订单金额：%s元%n",
                overview.orderCount(), overview.pendingCount(), overview.completedCount(), overview.cancelledCount(),
                overview.completedAmount().setScale(2).toPlainString());
    }

    private static void showAccount(UserAccount account) {
        System.out.printf("用户编号：%d%n用户名：%s%n邮箱：%s%n联系电话：%s%n角色：%s%n状态：%s%n",
                account.userId(), account.username(), account.email(),
                account.phone() == null ? "未填写" : account.phone(),
                account.role(), account.status() == 1 ? "正常" : "停用");
    }

    private static void createOrder(Console console, AuthenticatedUser currentUser, OrderService orders) {
        List<OrderableItem> items = orders.availableFor(currentUser);
        if (items.isEmpty()) {
            System.out.println("暂无可下单商品。");
            return;
        }
        System.out.println("商品编号 | 商品名称 | 单价（元）");
        for (OrderableItem item : items) {
            System.out.printf("%d | %s | %s%n", item.itemId(), item.title(), item.price().setScale(2).toPlainString());
        }
        String input = console.readLine("要购买的商品编号（留空返回）：");
        if (input == null || input.isBlank()) {
            return;
        }
        long itemId;
        try {
            itemId = Long.parseLong(input.strip());
        } catch (NumberFormatException exception) {
            throw new MallBusinessException("INVALID_ORDER_ITEM", "商品编号应为列表中的整数。");
        }
        OrderableItem selected = items.stream().filter(item -> item.itemId() == itemId).findFirst()
                .orElseThrow(() -> new MallBusinessException("ITEM_NOT_AVAILABLE", "请从上方列表选择商品。"));
        console.printf("本次购买：%s，金额%s元。%n", selected.title(), selected.price().setScale(2).toPlainString());
        String confirmation = console.readLine("输入y确认下单，其他输入返回：");
        if (confirmation == null || !"y".equalsIgnoreCase(confirmation.strip())) {
            System.out.println("本次未下单。");
            return;
        }
        OrderView order = orders.createFor(currentUser, selected.itemId(), selected.price());
        System.out.println("订单创建成功。");
        showOrders(List.of(order));
        LOG.info("商城订单创建成功，订单编号={}，用户编号={}", order.orderId(), currentUser.userId());
    }

    private static void queryOrders(Console console, AuthenticatedUser currentUser, OrderService orders) {
        console.printf("按订单当前状态、创建日期筛选；各项留空表示不限。%n");
        String statusInput = console.readLine("订单状态（0待处理 / 1已完成 / 2已取消）：");
        if (statusInput == null) { return; }
        String startInput = console.readLine("开始创建日期（YYYY-MM-DD，留空不限）：");
        if (startInput == null) { return; }
        String endInput = console.readLine("结束创建日期（YYYY-MM-DD，包含当天，留空不限）：");
        if (endInput == null) { return; }

        Integer status = null;
        if (!statusInput.isBlank()) {
            try {
                status = Integer.valueOf(statusInput.strip());
            } catch (NumberFormatException exception) {
                throw new MallBusinessException("INVALID_ORDER_FILTER", "订单状态请选择0、1、2，留空表示全部。");
            }
        }
        OrderFilter filter = new OrderFilter(status, parseDate(startInput), parseDate(endInput));
        console.printf("查询条件：状态=%s，创建日期=%s至%s（包含首尾日期）。%n",
                status == null ? "全部" : statusName(status),
                filter.startDate() == null ? "不限" : filter.startDate(),
                filter.endDate() == null ? "不限" : filter.endDate());
        List<OrderView> matches = orders.findMine(currentUser, filter);
        if (matches.isEmpty()) {
            System.out.println("没有符合这些条件的订单。");
        } else {
            showOrders(matches);
            System.out.printf("共%d条符合条件的本人订单。%n", matches.size());
        }
    }

    private static LocalDate parseDate(String input) {
        if (input.isBlank()) { return null; }
        try {
            return LocalDate.parse(input.strip(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException exception) {
            throw new MallBusinessException("INVALID_ORDER_FILTER", "创建日期请使用YYYY-MM-DD格式，并填写有效日期。");
        }
    }

    private static void cancelOrder(Console console, AuthenticatedUser currentUser, OrderService orders) {
        List<OrderView> pending = orders.findMine(currentUser, new OrderFilter(0, null, null));
        if (pending.isEmpty()) {
            System.out.println("你没有可取消的待处理订单。");
            return;
        }
        showOrders(pending);
        String input = console.readLine("要取消的订单编号（留空返回）：");
        if (input == null || input.isBlank()) {
            return;
        }
        long orderId;
        try {
            orderId = Long.parseLong(input.strip());
        } catch (NumberFormatException exception) {
            throw new MallBusinessException("INVALID_ORDER_ID", "订单编号应为列表中的整数。");
        }
        if (pending.stream().noneMatch(order -> order.orderId() == orderId)) {
            throw new MallBusinessException("ORDER_NOT_CANCELLABLE", "请从上方的本人待处理订单中选择。");
        }
        String confirmation = console.readLine("取消订单%d？输入y确认，其他输入返回：", orderId);
        if (confirmation == null || !"y".equalsIgnoreCase(confirmation.strip())) {
            System.out.println("本次未取消订单。");
            return;
        }
        OrderView cancelled = orders.cancelMine(currentUser, orderId);
        System.out.println("订单已取消。");
        showOrders(List.of(cancelled));
        LOG.info("商城订单已取消，订单编号={}，用户编号={}", cancelled.orderId(), currentUser.userId());
    }

    private static void showOrders(List<OrderView> orders) {
        if (orders.isEmpty()) {
            System.out.println("你还没有订单。");
            return;
        }
        System.out.println("订单编号 | 用户编号 | 商品编号 | 商品名称 | 金额（元） | 状态 | 创建时间");
        for (OrderView order : orders) {
            String status = statusName(order.status());
            System.out.printf("%d | %d | %d | %s | %s | %s | %s%n", order.orderId(), order.userId(),
                    order.itemId(), order.itemTitle(), order.amount().setScale(2).toPlainString(), status,
                    order.createdAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        }
    }

    private static String statusName(int status) {
        return switch (status) {
            case 0 -> "待处理";
            case 1 -> "已完成";
            case 2 -> "已取消";
            default -> "未知状态";
        };
    }
}
