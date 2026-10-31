package com.sportsmall.cli;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.UserAccount;
import com.sportsmall.model.OrderableItem;
import com.sportsmall.model.OrderView;
import com.sportsmall.service.UserAccountService;
import com.sportsmall.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Console;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 登录后的本机交互菜单；当前身份一直由已通过登录的结果传入。 */
public final class AccountMenu {
    private static final Logger LOG = LoggerFactory.getLogger(AccountMenu.class);

    private AccountMenu() {
    }

    public static void run(Console console, AuthenticatedUser currentUser,
                           UserAccountService accounts, OrderService orders) {
        while (true) {
            console.printf("%n当前用户：%s（编号%d）%n", currentUser.username(), currentUser.userId());
            console.printf("1. 查看我的账号资料%n2. 修改我的联系电话%n3. 创建订单%n4. 查看我的订单%n0. 退出登录%n");
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
                    case "4" -> showOrders(orders.findMine(currentUser));
                    default -> System.out.println("请选择0、1、2、3或4。");
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

    private static void showOrders(List<OrderView> orders) {
        if (orders.isEmpty()) {
            System.out.println("你还没有订单。");
            return;
        }
        System.out.println("订单编号 | 用户编号 | 商品编号 | 商品名称 | 金额（元） | 状态 | 创建时间");
        for (OrderView order : orders) {
            String status = switch (order.status()) {
                case 0 -> "待处理";
                case 1 -> "已完成";
                case 2 -> "已取消";
                default -> "未知状态";
            };
            System.out.printf("%d | %d | %d | %s | %s | %s | %s%n", order.orderId(), order.userId(),
                    order.itemId(), order.itemTitle(), order.amount().setScale(2).toPlainString(), status,
                    order.createdAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        }
    }
}
