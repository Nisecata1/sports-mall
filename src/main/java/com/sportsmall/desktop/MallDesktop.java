package com.sportsmall.desktop;

import com.sportsmall.client.ApiException;
import com.sportsmall.client.MallApiClient;
import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/** 窗口只组织交互并调用HTTP接口；业务与数据库连接位于独立后端进程。 */
public final class MallDesktop extends JFrame {
    private static final Logger LOG = LoggerFactory.getLogger(MallDesktop.class);
    private static final Color GREEN = new Color(18, 109, 100);
    private static final Color BACKGROUND = new Color(246, 248, 247);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final JPanel body = new JPanel(new BorderLayout());
    private final JLabel status = new JLabel("就绪");
    private final Map<Component, Boolean> enabledBeforeTask = new IdentityHashMap<>();
    private JPanel workspace;
    private JTextField username;
    private JPasswordField mallPassword;
    private final MallApiClient api = MallApiClient.load();
    private AuthenticatedUser currentUser;
    private boolean busy;

    public static void main(String[] args) { open(); }

    public static void open() {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("窗口界面需要图形桌面");
        }
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.put("nimbusBase", GREEN);
                UIManager.put("nimbusFocus", new Color(154, 201, 191));
                UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
                UIManager.put("Panel.background", Color.WHITE);
                var keys = UIManager.getDefaults().keys();
                while (keys.hasMoreElements()) {
                    Object key = keys.nextElement();
                    if (UIManager.get(key) instanceof Font) {
                        UIManager.put(key, new javax.swing.plaf.FontUIResource("Microsoft YaHei", Font.PLAIN, 14));
                    }
                }
            } catch (Exception ignored) {
                // 平台外观不可用时使用JDK默认外观，不影响业务操作。
            }
            new MallDesktop().setVisible(true);
        });
    }

    private MallDesktop() {
        super("运动装备商城");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(960, 680));
        setSize(1160, 780);
        setLocationRelativeTo(null);
        body.setBackground(BACKGROUND);
        setContentPane(body);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { closeWindow(); }
        });
        showLogin();
    }

    private void showLogin() {
        currentUser = null;
        body.removeAll();
        JPanel introduction = column();
        introduction.setPreferredSize(new Dimension(390, 0));
        introduction.setBackground(new Color(226, 238, 234));
        introduction.setBorder(new EmptyBorder(72, 40, 40, 36));
        introduction.add(title("运动装备商城", 30));
        introduction.add(Box.createVerticalStrut(18));
        introduction.add(note("商品 · 订单 · 评论 · 数据统计"));
        introduction.add(Box.createVerticalStrut(45));
        introduction.add(title("你的商城工作台", 20));
        introduction.add(Box.createVerticalStrut(14));
        introduction.add(note("浏览运动装备，管理订单与个人资料。"));
        introduction.add(Box.createVerticalStrut(8));
        introduction.add(note("查看评分、热门商品和近7天活动。"));
        introduction.add(Box.createVerticalGlue());
        introduction.add(note("高级数据库技术 / 项目011"));
        body.add(introduction, BorderLayout.WEST);

        JPanel form = column();
        form.setBorder(new EmptyBorder(68, 70, 40, 70));
        form.add(title("登录商城", 25));
        form.add(Box.createVerticalStrut(8));
        form.add(note("使用已注册的商城账号"));
        username = named(new JTextField(22), "商城用户名");
        mallPassword = named(new JPasswordField(22), "商城密码");
        addField(form, "商城用户名", username);
        addField(form, "商城密码", mallPassword);
        form.add(note("商城账号由后端验证。"));
        form.add(Box.createVerticalStrut(20));
        JPanel actions = row();
        JButton login = button("登录", this::login);
        actions.add(login);
        actions.add(button("注册普通账号", this::register));
        form.add(actions);
        form.add(Box.createVerticalGlue());
        form.add(note("商城后端：" + api.address()));
        body.add(form, BorderLayout.CENTER);
        getRootPane().setDefaultButton(login);
        installStatus();
        refreshLayout();
    }

    private void login() {
        String name = username.getText();
        char[] password = mallPassword.getPassword();
        mallPassword.setText("");
        task("正在登录并记录登录日志", () -> {
            try {
                return api.login(name, password);
            } finally {
                Arrays.fill(password, '\0');
            }
        }, user -> {
            currentUser = user;
            LOG.info("桌面商城登录成功，用户编号={}", user.userId());
            showWorkspace();
        });
    }

    private void register() {
        JPanel form = column();
        JTextField name = named(new JTextField(24), "注册用户名");
        JTextField email = named(new JTextField(24), "注册邮箱");
        JTextField phone = named(new JTextField(24), "注册电话");
        JPasswordField password = named(new JPasswordField(24), "注册密码");
        JPasswordField confirmation = named(new JPasswordField(24), "确认注册密码");
        addField(form, "用户名", name); addField(form, "邮箱", email);
        addField(form, "电话（可留空）", phone); addField(form, "商城密码", password);
        addField(form, "再次输入商城密码", confirmation);
        if (JOptionPane.showConfirmDialog(this, form, "注册普通用户", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            password.setText(""); confirmation.setText("");
            return;
        }
        String enteredName = name.getText(), enteredEmail = email.getText(), enteredPhone = phone.getText();
        char[] first = password.getPassword(), second = confirmation.getPassword();
        password.setText(""); confirmation.setText("");
        task("正在注册普通用户", () -> {
            try {
                return api.register(enteredName, enteredEmail, enteredPhone, first, second);
            } finally {
                Arrays.fill(first, '\0'); Arrays.fill(second, '\0');
            }
        }, result -> {
            username.setText(result.username());
            message("注册成功，用户编号：" + result.userId() + "。\n现在可以使用商城密码登录。");
        });
    }

    private void showWorkspace() {
        body.removeAll();
        JPanel header = new JPanel(new BorderLayout(20, 0));
        header.setBackground(Color.WHITE);
        header.setBorder(new EmptyBorder(22, 28, 20, 28));
        header.add(title("运动装备商城", 24), BorderLayout.WEST);
        JPanel account = row();
        account.add(new JLabel("当前用户：" + currentUser.username() + "  /  " + currentUser.role()));
        account.add(button("退出登录", this::logout));
        header.add(account, BorderLayout.EAST);
        body.add(header, BorderLayout.NORTH);
        JPanel navigation = column();
        navigation.setBackground(new Color(236, 243, 240));
        navigation.setBorder(new EmptyBorder(18, 20, 18, 16));
        navigation.setPreferredSize(new Dimension(174, 0));
        navigation.add(button("商品浏览", this::showProducts));
        navigation.add(Box.createVerticalStrut(12));
        navigation.add(button("我的订单", this::showOrders));
        navigation.add(Box.createVerticalStrut(12));
        navigation.add(button("商品评论", this::showComments));
        navigation.add(Box.createVerticalStrut(12));
        navigation.add(button("数据统计", this::showStatistics));
        navigation.add(Box.createVerticalStrut(12));
        navigation.add(button("我的账号", this::showAccount));
        if ("ADMIN".equals(currentUser.role())) {
            navigation.add(Box.createVerticalStrut(12));
            navigation.add(button("用户管理", this::showAdminUsers));
            navigation.add(Box.createVerticalStrut(12));
            navigation.add(button("分类管理", this::showCategories));
            navigation.add(Box.createVerticalStrut(12));
            navigation.add(button("商品管理", this::showAdminItems));
        }
        body.add(navigation, BorderLayout.WEST);
        workspace = new JPanel(new BorderLayout());
        workspace.setBorder(new EmptyBorder(22, 14, 20, 28));
        workspace.setBackground(BACKGROUND);
        body.add(workspace, BorderLayout.CENTER);
        getRootPane().setDefaultButton(null);
        installStatus();
        refreshLayout();
        showProducts();
    }

    private void showProducts() {
        task("正在读取在售商品", () -> api.items(), items -> {
            JPanel page = page("商品浏览", "选择商品查看详情或创建订单");
            JTable table = dataTable("在售商品", new String[]{"编号", "商品名称", "分类", "单价（元）"},
                    items.stream().map(item -> new Object[]{item.itemId(), item.title(), item.categoryName(), money(item.price())}).toList());
            page.add(new JScrollPane(table), BorderLayout.CENTER);
            JPanel actions = row();
            actions.add(button("刷新商品", this::showProducts));
            actions.add(button("查看详情", () -> selected(table, items, item -> browse(item.itemId()))));
            actions.add(button("创建订单", () -> selected(table, items, this::createOrder)));
            page.add(actions, BorderLayout.SOUTH);
            display(page);
        });
    }

    private void browse(long itemId) {
        task("正在读取商品详情", () -> api.openItem(itemId), opened -> {
            StringBuilder text = new StringBuilder(opened.item().title()).append("\n\n分类：")
                    .append(opened.item().categoryName()).append("\n单价：").append(money(opened.item().price())).append("元\n\n");
            opened.detail().ifPresentOrElse(detail -> text.append(detail.description()).append("\n\n内容语言：")
                    .append(detail.language()).append("\n图片：")
                    .append(detail.images().isEmpty() ? "暂无图片" : String.join("\n", detail.images())),
                    () -> text.append("商品详情尚未填写。"));
            JOptionPane.showMessageDialog(this, scrollText(text.toString(), 15, 48),
                    "商品详情（关闭后保存本次浏览）", JOptionPane.PLAIN_MESSAGE);
            task("正在保存本次浏览", () -> api.finishBrowse(opened.browseId()),
                    result -> status.setText("浏览已保存：" + result.durationSeconds() + "秒  /  " + result.actionId()));
        });
    }

    private void createOrder(ItemView item) {
        if (!confirm("购买“" + item.title() + "”，金额" + money(item.price()) + "元？")) return;
        task("正在创建订单，请等待结果", () -> api.createOrder(item.itemId(), item.price()),
                result -> message("订单创建成功。\n订单编号：" + result.orderId() + "\n金额：" + money(result.amount()) + "元"));
    }

    private void showOrders() {
        JPanel page = page("我的订单", "按当前状态和创建日期筛选本人订单");
        JComboBox<String> states = named(new JComboBox<>(new String[]{"全部状态", "待处理", "已完成", "已取消"}), "订单状态");
        JTextField from = named(new JTextField(10), "订单开始日期");
        JTextField through = named(new JTextField(10), "订单结束日期");
        from.setToolTipText("YYYY-MM-DD，留空不限"); through.setToolTipText("YYYY-MM-DD，包含当天，留空不限");
        JPanel filters = row();
        filters.add(states); filters.add(new JLabel("创建日期")); filters.add(from);
        filters.add(new JLabel("至")); filters.add(through);
        JPanel north = (JPanel) page.getComponent(0);
        north.add(filters);
        JTable table = dataTable("本人订单", new String[]{"订单编号", "商品", "金额（元）", "状态", "创建时间"}, List.of());
        page.add(new JScrollPane(table), BorderLayout.CENTER);
        final List<OrderView>[] rows = orderHolder();
        Runnable query = () -> {
            try {
                OrderFilter filter = new OrderFilter(states.getSelectedIndex() == 0 ? null : states.getSelectedIndex() - 1,
                        date(from.getText()), date(through.getText()));
                task("正在查询本人订单", () -> api.orders(filter), found -> {
                    rows[0] = found;
                    fill(table, found.stream().map(order -> new Object[]{order.orderId(), order.itemTitle(), money(order.amount()),
                            orderStatus(order.status()), TIME.format(order.createdAt())}).toList());
                    status.setText("查询完成，共" + found.size() + "条本人订单");
                });
            } catch (RuntimeException failure) { showFailure(failure); }
        };
        filters.add(button("查询", query));
        JPanel actions = row();
        actions.add(button("取消选中的待处理订单", () -> selected(table, rows[0], order -> {
            if (order.status() != 0) { message("只能取消本人待处理订单。"); return; }
            if (!confirm("取消订单" + order.orderId() + "？")) return;
            task("正在取消订单", () -> api.cancelOrder(order.orderId()), result -> {
                message("订单" + result.orderId() + "已取消。");
                query.run();
            });
        })));
        page.add(actions, BorderLayout.SOUTH);
        display(page);
        query.run();
    }

    @SuppressWarnings("unchecked")
    private static List<OrderView>[] orderHolder() { return new List[]{List.of()}; }

    private void showComments() {
        task("正在读取可评论商品", () -> api.items(), items -> {
            JPanel page = page("商品评论", "查看最新评论，或为在售商品写下评分与感受");
            JComboBox<ItemChoice> choice = choices(items, "评论商品");
            JPanel filters = row(); filters.add(new JLabel("商品")); filters.add(choice);
            ((JPanel) page.getComponent(0)).add(filters);
            JTable table = dataTable("最新评论", new String[]{"评论编号", "用户编号", "评分", "评论", "标签", "时间"}, List.of());
            page.add(new JScrollPane(table), BorderLayout.CENTER);
            Runnable query = () -> {
                ItemChoice item = (ItemChoice) choice.getSelectedItem();
                if (item == null) { status.setText("当前没有可评论商品"); return; }
                task("正在读取最新20条评论", () -> api.comments(item.item.itemId()),
                        comments -> fill(table, comments.stream().map(comment -> new Object[]{comment.commentId(),
                                comment.userId(), comment.rating(), comment.content(), String.join("，", comment.tags()),
                                time(comment.createdAt())}).toList()));
            };
            filters.add(button("刷新评论", query));
            filters.add(button("发表评论", () -> {
                ItemChoice item = (ItemChoice) choice.getSelectedItem();
                if (item != null) postComment(item.item, query); else message("当前没有可评论商品。");
            }));
            choice.addActionListener(event -> { if (!busy) query.run(); });
            display(page);
            query.run();
        });
    }

    private void postComment(ItemView item, Runnable refresh) {
        JPanel form = column();
        JSpinner rating = named(new JSpinner(new SpinnerNumberModel(5, 1, 5, 1)), "评论评分");
        JTextArea content = named(new JTextArea(7, 36), "评论文字");
        content.setLineWrap(true); content.setWrapStyleWord(true);
        JTextField tags = named(new JTextField(28), "评论标签");
        addField(form, "评分（1至5）", rating);
        addField(form, "文字（最多2000字符）", new JScrollPane(content));
        addField(form, "标签（可选，逗号分隔）", tags);
        if (JOptionPane.showConfirmDialog(this, form, "评论 " + item.title(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        String text = content.getText(), tagText = tags.getText();
        int score = (Integer) rating.getValue();
        task("正在保存评论", () -> api.postComment(item.itemId(), text, score, tagText),
                saved -> { message("评论已保存。\n评论编号：" + saved.commentId()); refresh.run(); });
    }

    private void showStatistics() {
        JPanel page = page("数据统计", "查询商品、浏览活动与本人月度订单统计");
        JTextArea output = textArea("", 18, 55);
        page.add(new JScrollPane(output), BorderLayout.CENTER);
        JPanel actions = row();
        actions.add(button("近7天热门排行", () -> task("正在统计热门商品", () -> api.hotItems(), report -> {
            StringBuilder result = new StringBuilder("近7天商品浏览排行\n").append(time(report.fromInclusive())).append(" 至 ")
                    .append(time(report.throughInclusive())).append("\n\n");
            int rank = 0;
            for (var row : report.items()) {
                result.append(++rank).append(". ").append(row.item().map(ItemView::title).orElse("当前不可浏览的商品"))
                        .append("（编号").append(row.statistics().itemId()).append("）\n   浏览")
                        .append(row.statistics().viewCount()).append("次，累计").append(row.statistics().durationSeconds()).append("秒\n\n");
            }
            if (report.items().isEmpty()) result.append("此时间段没有浏览记录。");
            output.setText(result.toString()); output.setCaretPosition(0);
        })));
        actions.add(button("我的近7天活动", () -> task("正在统计本人活动", () -> api.myActivity(), report -> {
            var s = report.summary();
            output.setText("我的近7天活动\n" + time(report.fromInclusive()) + " 至 " + time(report.throughInclusive())
                    + "\n\n浏览次数：" + s.viewCount() + "\n不同商品：" + s.distinctItemCount()
                    + "\n累计停留：" + s.durationSeconds() + "秒\n活跃日期：" + s.activeDayCount()
                    + "天（北京时间）\n最近浏览：" + time(s.lastViewedAt()));
            output.setCaretPosition(0);
        })));
        actions.add(button("商品评论统计", () -> task("正在读取商品", () -> api.items(), items -> {
            JComboBox<ItemChoice> choice = choices(items, "统计商品");
            if (items.isEmpty()) { message("当前没有可统计商品。"); return; }
            if (JOptionPane.showConfirmDialog(this, choice, "选择统计商品", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            ItemView item = ((ItemChoice) choice.getSelectedItem()).item;
            task("正在聚合全部评论", () -> api.commentStatistics(item.itemId()), s -> {
                StringBuilder result = new StringBuilder(item.title()).append(" / 全部评论\n\n评论条数：").append(s.commentCount())
                        .append("\n平均评分：").append(s.averageRating() == null ? "暂无" : String.format("%.2f/5", s.averageRating())).append("\n\n");
                for (int n = 0; n < 5; n++) result.append(n+1).append("分：").append(s.ratingCounts().get(n)).append("条\n");
                output.setText(result.toString()); output.setCaretPosition(0);
            });
        })));
        actions.add(button("我的月度订单", () -> showMonthlyOrders(output)));
        ((JPanel) page.getComponent(0)).add(actions);
        output.setText("请选择一种统计。\n\n热门排行、本人活动和评论统计由MongoDB聚合。\n月度订单由MySQL存储过程汇总本人订单。\n近7天为滚动7×24小时，活跃日期按北京时间去重。");
        display(page);
    }

    private void showMonthlyOrders(JTextArea output) {
        String currentMonth = java.time.YearMonth.now(ZONE).toString();
        String month = JOptionPane.showInputDialog(this, "查询月份（YYYY-MM）", currentMonth);
        if (month == null) return;
        task("正在读取本人月度订单报表", () -> api.monthlyOrders(month.strip()), report -> {
            output.setText("我的月度订单报表\n月份 " + report.monthStart().toString().substring(0, 7)
                    + "\n\n订单总数 " + report.orderCount()
                    + "\n待处理 " + report.pendingCount()
                    + "\n已完成 " + report.completedCount()
                    + "\n已取消 " + report.cancelledCount()
                    + "\n已完成金额 " + money(report.completedAmount()) + " 元"
                    + "\n\n按订单创建月份筛选，按查询时的当前状态统计。"
                    + "\n金额合计该月创建且当前已完成的订单历史金额。");
            output.setCaretPosition(0);
        });
    }

    private void showAdminItems() {
        task("正在读取商品管理列表", () -> api.adminItems(), items -> {
            JPanel page = page("商品管理", "维护商品基础信息与说明、图片地址等详情");
            JTable table = dataTable("商品管理列表", new String[]{"编号", "商品名称", "分类编号", "分类", "单价（元）", "状态"},
                    items.stream().map(item -> new Object[]{item.itemId(), item.title(), item.categoryId(),
                            item.categoryName(), money(item.price()), item.status() == 1 ? "上架" : "下架"}).toList());
            page.add(new JScrollPane(table), BorderLayout.CENTER);
            JPanel actions = row();
            actions.add(button("刷新商品列表", this::showAdminItems));
            actions.add(button("新增商品", () -> editAdminItem(null)));
            actions.add(button("编辑／上下架", () -> selected(table, items, this::editAdminItem)));
            actions.add(note("含上下架商品，按编号显示前100项。"));
            JPanel detailActions = row();
            detailActions.add(button("编辑详情", () -> selected(table, items, this::editItemDetail)));
            detailActions.add(button("删除详情", () -> selected(table, items, this::deleteItemDetail)));
            JPanel controls = column(); controls.add(actions); controls.add(detailActions);
            page.add(controls, BorderLayout.SOUTH);
            display(page);
        });
    }

    private void editAdminItem(AdminItemView item) {
        task("正在读取可选分类", () -> api.categories(), categories -> {
            if (categories.isEmpty()) { message("请先在分类管理中新增商品分类。"); return; }
            JTextField title = named(new JTextField(item == null ? "" : item.title(), 28), "管理商品名称");
            JTextField price = named(new JTextField(item == null ? "" : item.price().toPlainString(), 12), "管理商品单价");
            JComboBox<CategoryChoice> category = named(new JComboBox<>(categories.stream()
                    .map(CategoryChoice::new).toArray(CategoryChoice[]::new)), "管理商品分类");
            if (item != null) {
                for (int index = 0; index < category.getItemCount(); index++) {
                    if (category.getItemAt(index).category().categoryId() == item.categoryId()) category.setSelectedIndex(index);
                }
            }
            JComboBox<String> state = named(new JComboBox<>(new String[]{"下架", "上架"}), "管理商品状态");
            state.setSelectedIndex(item == null ? 0 : item.status());
            JPanel form = column();
            form.add(new JLabel(item == null ? "新增商品基础信息" : "编辑商品编号" + item.itemId() + "；已有订单金额保留。"));
            form.add(new JLabel("商品名称（1至200个字符）")); form.add(title);
            form.add(new JLabel("所属分类")); form.add(category);
            form.add(new JLabel("单价（元，大于0，最多两位小数）")); form.add(price);
            form.add(new JLabel("销售状态")); form.add(state);
            if (JOptionPane.showConfirmDialog(this, form, item == null ? "新增商品" : "编辑商品／上下架",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            BigDecimal amount;
            try { amount = new BigDecimal(price.getText().strip()); }
            catch (NumberFormatException failure) { message("单价请输入数字，例如89.90。"); return; }
            String newTitle = title.getText();
            long categoryId = ((CategoryChoice) category.getSelectedItem()).category().categoryId();
            int statusValue = state.getSelectedIndex();
            task("正在保存商品", () -> item == null ? api.createItem(newTitle, categoryId, amount, statusValue)
                    : api.updateItem(item.itemId(), newTitle, categoryId, amount, statusValue), saved -> {
                message("商品已保存：" + saved.title() + "，编号=" + saved.itemId() + "，"
                        + (saved.status() == 1 ? "上架" : "下架") + "。");
                showAdminItems();
            });
        });
    }

    private void editItemDetail(AdminItemView item) {
        task("正在读取商品详情", () -> api.adminItemDetail(item.itemId()), existing -> {
            JTextArea description = named(new JTextArea(existing.map(ItemDetail::description).orElse(""), 7, 42), "商品说明");
            description.setLineWrap(true); description.setWrapStyleWord(true);
            JTextArea images = named(new JTextArea(existing.map(detail -> String.join("\n", detail.images())).orElse(""), 4, 42), "商品图片地址");
            JTextField language = named(new JTextField(existing.map(ItemDetail::language).orElse("zh-CN"), 20), "详情内容语言");
            JPanel form = column();
            form.add(new JLabel(item.title() + "（编号" + item.itemId() + "）"));
            form.add(new JLabel(existing.isPresent() ? "已有详情，保存将更新这份详情。" : "尚无详情，保存将创建一份详情。"));
            form.add(new JLabel("商品说明（1至5000个字符）")); form.add(new JScrollPane(description));
            form.add(new JLabel("图片地址（每行一个HTTP/HTTPS地址，最多10项；留空表示无图片）")); form.add(new JScrollPane(images));
            form.add(new JLabel("内容语言（如zh-CN、en）")); form.add(language);
            if (JOptionPane.showConfirmDialog(this, form, "编辑商品详情", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            String newDescription = description.getText(), newLanguage = language.getText();
            List<String> newImages = images.getText().lines().map(String::strip).filter(value -> !value.isEmpty()).toList();
            task("正在保存商品详情", () -> api.saveItemDetail(item.itemId(), newDescription, newImages, newLanguage), saved -> {
                message("商品详情已保存：编号=" + saved.itemId() + "，图片地址=" + saved.images().size() + "项。");
                showAdminItems();
            });
        });
    }

    private void deleteItemDetail(AdminItemView item) {
        if (JOptionPane.showConfirmDialog(this, "删除“" + item.title() + "”（编号" + item.itemId()
                + "）的说明、图片地址和语言？\n商品基础信息、订单和评论会保留。", "删除商品详情",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        task("正在删除商品详情", () -> api.deleteItemDetail(item.itemId()), deleted -> {
            message(deleted ? "商品详情已删除，商品基础信息保留。" : "该商品当前没有详情，无需删除。");
            showAdminItems();
        });
    }

    private void showCategories() {
        task("正在读取分类树", () -> api.categories(), categories -> {
            JPanel page = page("分类管理", "查看分类层级，新增、编辑和删除分类");
            DefaultMutableTreeNode root = new DefaultMutableTreeNode("全部分类");
            Map<Long, DefaultMutableTreeNode> nodes = new java.util.LinkedHashMap<>();
            for (CategoryView category : categories) {
                nodes.put(category.categoryId(), new DefaultMutableTreeNode(new CategoryChoice(category)));
            }
            for (CategoryView category : categories) {
                DefaultMutableTreeNode parent = category.parentId() == null ? root : nodes.get(category.parentId());
                parent.add(nodes.get(category.categoryId()));
            }
            JTree tree = named(new JTree(root), "分类树");
            tree.setRowHeight(28);
            tree.getSelectionModel().setSelectionMode(javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION);
            for (int row = 0; row < tree.getRowCount(); row++) tree.expandRow(row);
            page.add(new JScrollPane(tree), BorderLayout.CENTER);
            JPanel controls = column();
            JLabel selected = new JLabel("选择一条分类，可新增子分类、编辑名称与上级，或删除空分类。");
            controls.add(selected);
            JPanel actions = row();
            actions.add(button("刷新分类树", this::showCategories));
            actions.add(button("新增根分类", () -> createCategory(null)));
            JButton addChild = button("新增子分类", () -> {
                var node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
                if (node != null && node.getUserObject() instanceof CategoryChoice choice) {
                    createCategory(choice.category());
                }
            });
            addChild.setEnabled(false);
            JButton edit = button("编辑分类", () -> {
                var node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
                if (node != null && node.getUserObject() instanceof CategoryChoice choice) {
                    editCategory(choice.category(), categories);
                }
            });
            JButton delete = button("删除分类", () -> {
                var node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
                if (node != null && node.getUserObject() instanceof CategoryChoice choice) {
                    deleteCategory(choice.category());
                }
            });
            edit.setEnabled(false);
            delete.setEnabled(false);
            tree.addTreeSelectionListener(event -> {
                var node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
                if (node != null && node.getUserObject() instanceof CategoryChoice choice) {
                    CategoryView category = choice.category();
                    selected.setText("当前分类：" + category.name() + "；编号：" + category.categoryId()
                            + "；上级编号：" + (category.parentId() == null ? "无（根分类）" : category.parentId()));
                    addChild.setEnabled(true);
                    edit.setEnabled(true);
                    delete.setEnabled(true);
                } else {
                    selected.setText("全部分类是窗口的树根，数据库中没有这一条记录。");
                    addChild.setEnabled(false);
                    edit.setEnabled(false);
                    delete.setEnabled(false);
                }
            });
            actions.add(addChild);
            actions.add(edit);
            actions.add(delete);
            controls.add(actions);
            page.add(controls, BorderLayout.SOUTH);
            display(page);
        });
    }

    private void createCategory(CategoryView parent) {
        String name = JOptionPane.showInputDialog(this,
                parent == null ? "新根分类名称（1至50个字符）：" : "在“" + parent.name() + "”下面新增子分类，名称：");
        if (name == null) return;
        Long parentId = parent == null ? null : parent.categoryId();
        task("正在保存分类", () -> api.createCategory(name, parentId), saved -> {
            message("分类已保存：" + saved.name() + "，编号=" + saved.categoryId()
                    + "，上级编号=" + (saved.parentId() == null ? "无（根分类）" : saved.parentId()) + "。");
            showCategories();
        });
    }

    private record CategoryChoice(CategoryView category) {
        @Override public String toString() { return category.name() + "（编号" + category.categoryId() + "）"; }
    }

    private void editCategory(CategoryView category, List<CategoryView> categories) {
        JTextField name = named(new JTextField(category.name(), 25), "分类名称");
        JComboBox<CategoryParentChoice> parents = named(new JComboBox<>(), "上级分类");
        parents.addItem(new CategoryParentChoice(null, "无上级（根分类）"));
        for (CategoryView candidate : categories) {
            if (candidate.categoryId() == category.categoryId()) continue;
            var option = new CategoryParentChoice(candidate.categoryId(),
                    candidate.name() + "（编号" + candidate.categoryId() + "）");
            parents.addItem(option);
            if (java.util.Objects.equals(candidate.categoryId(), category.parentId())) parents.setSelectedItem(option);
        }
        JPanel form = column();
        form.add(new JLabel("正在编辑：编号" + category.categoryId() + "；编号保持不变。"));
        form.add(new JLabel("分类名称（1至50个字符）"));
        form.add(name);
        form.add(new JLabel("上级分类（后端会阻止把分类移入自身的下级）"));
        form.add(parents);
        if (JOptionPane.showConfirmDialog(this, form, "编辑分类", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        var parent = (CategoryParentChoice) parents.getSelectedItem();
        String newName = name.getText();
        task("正在修改分类", () -> api.updateCategory(category.categoryId(), newName, parent.id()), saved -> {
            message("分类已更新：" + saved.name() + "，编号=" + saved.categoryId() + "。");
            showCategories();
        });
    }

    private void deleteCategory(CategoryView category) {
        if (JOptionPane.showConfirmDialog(this,
                "删除“" + category.name() + "”（编号" + category.categoryId() + "）？\n"
                        + "只允许删除没有下级分类、没有商品引用的分类。确认后将删除这条分类记录。",
                "删除分类", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        task("正在删除分类", () -> api.deleteCategory(category.categoryId()), deleted -> {
            message(deleted ? "分类已删除。" : "分类未删除，请刷新后检查。");
            showCategories();
        });
    }

    private record CategoryParentChoice(Long id, String label) {
        @Override public String toString() { return label; }
    }

    private void showAdminUsers() {
        task("正在读取用户列表", () -> api.adminUsers(), users -> {
            JPanel page = page("用户管理", "查看账号角色与状态");
            JTable table = dataTable("用户列表", new String[]{"用户编号", "用户名", "角色", "状态"},
                    users.stream().map(user -> new Object[]{user.userId(), user.username(), user.role(),
                            user.status() == 1 ? "正常" : "停用"}).toList());
            page.add(new JScrollPane(table), BorderLayout.CENTER);
            JPanel actions = row();
            actions.add(button("刷新用户列表", this::showAdminUsers));
            actions.add(note("显示前100个账号，按用户编号排序。"));
            page.add(actions, BorderLayout.SOUTH);
            display(page);
        });
    }

    private void showAccount() {
        task("正在读取本人账号与档案", () -> new AccountData(api.account(), api.profile()), data -> {
            UserAccount account = data.account();
            JPanel page = page("我的账号", "管理本人的账号信息与订单概览");
            JTextArea output = textArea("用户编号：" + account.userId() + "\n用户名：" + account.username()
                    + "\n邮箱：" + account.email() + "\n联系电话：" + optional(account.phone()) + "\n角色：" + account.role()
                    + "\n状态：" + (account.status() == 1 ? "正常" : "停用")
                    + "\n\n" + profileText(data.profile()), 15, 40);
            page.add(new JScrollPane(output), BorderLayout.CENTER);
            JPanel actions = row();
            actions.add(button("刷新资料", this::showAccount));
            actions.add(button("修改联系电话", () -> {
                String phone = JOptionPane.showInputDialog(this, "新的电话（最多20字符，留空清除）", account.phone());
                if (phone == null) return;
                task("正在保存联系电话", () -> api.changePhone(phone),
                        changed -> { message(changed.changed() ? "联系电话已保存。" : "联系电话没有变化。"); showAccount(); });
            }));
            actions.add(button("资料与订单概览", () -> task("正在读取两个MySQL视图", () -> api.overview(), s -> {
                output.setText("用户：" + s.username() + "\n姓名：" + optional(s.realName()) + "\n地址：" + optional(s.address())
                        + "\n\n订单总数：" + s.orderCount() + "\n待处理：" + s.pendingCount() + "\n已完成：" + s.completedCount()
                        + "\n已取消：" + s.cancelledCount() + "\n已完成金额：" + money(s.completedAmount()) + "元");
                output.setCaretPosition(0);
            })));
            JPanel profileActions = row();
            profileActions.add(button("编辑我的档案", () -> editProfile(data.profile())));
            JButton deleteProfile = button("删除我的档案", () -> {
                if (!confirm("删除你的档案？姓名、证件号码、地址和备注会被清除。")) return;
                task("正在删除本人档案", () -> api.deleteProfile(), deleted -> {
                    message(deleted ? "档案已删除。" : "当前已没有档案。");
                    showAccount();
                });
            });
            deleteProfile.setEnabled(data.profile().profileId() != null);
            profileActions.add(deleteProfile);
            JPanel controls = column();
            controls.add(actions);
            controls.add(profileActions);
            page.add(controls, BorderLayout.SOUTH);
            display(page);
        });
    }

    private void editProfile(UserProfile profile) {
        JPanel form = column();
        JTextField name = named(new JTextField(Objects.toString(profile.realName(), ""), 28), "档案姓名");
        JTextField card = named(new JTextField(Objects.toString(profile.idCard(), ""), 28), "档案证件号码");
        JTextArea address = named(new JTextArea(Objects.toString(profile.address(), ""), 3, 28), "档案地址");
        JTextArea notes = named(new JTextArea(Objects.toString(profile.notes(), ""), 5, 28), "档案备注");
        address.setLineWrap(true);
        address.setWrapStyleWord(true);
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        form.add(note(profile.profileId() == null ? "填写后保存即可建立个人档案。" : "编辑已保存的个人档案。"));
        addField(form, "姓名（最多50字符，可留空）", name);
        addField(form, "证件号码（最多20字符，可留空）", card);
        addField(form, "地址（最多500字符，可留空）", new JScrollPane(address));
        addField(form, "备注（最多2000字符，可留空）", new JScrollPane(notes));
        if (JOptionPane.showConfirmDialog(this, form, "编辑我的档案",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        String realName = name.getText();
        String idCard = card.getText();
        String location = address.getText();
        String noteText = notes.getText();
        task("正在保存本人档案", () -> api.saveProfile(realName, idCard, location, noteText), saved -> {
            message("档案已保存，档案编号 " + saved.profileId() + "。");
            showAccount();
        });
    }

    private static String profileText(UserProfile profile) {
        if (profile.profileId() == null) return "个人档案尚未建立";
        return "档案编号 " + profile.profileId() + "\n姓名 " + optional(profile.realName())
                + "\n证件号码 " + optional(profile.idCard()) + "\n地址 " + optional(profile.address())
                + "\n备注 " + optional(profile.notes());
    }

    private record AccountData(UserAccount account, UserProfile profile) { }

    private void logout() {
        task("正在退出登录", () -> {
            try { api.logout(); return true; }
            catch (ApiException failure) { return false; }
        }, confirmed -> {
            showLogin();
            if (!confirmed) message("本窗口的登录凭证已清除。后端暂不可达，服务器会话将在到期或后端关闭后失效。");
        });
    }

    private <T> void task(String description, Callable<T> action, Consumer<T> onSuccess) {
        if (busy) return;
        setBusy(true);
        status.setText(description + "…");
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return action.call(); }
            @Override protected void done() {
                setBusy(false);
                try {
                    T result = get();
                    status.setText("完成");
                    onSuccess.accept(result);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt(); showFailure(exception);
                } catch (ExecutionException exception) {
                    showFailure(exception.getCause());
                } catch (RuntimeException exception) {
                    showFailure(exception);
                }
            }
        }.execute();
    }

    private void setBusy(boolean value) {
        busy = value;
        if (value) lock(body);
        else { enabledBeforeTask.forEach(Component::setEnabled); enabledBeforeTask.clear(); }
        setCursor(Cursor.getPredefinedCursor(value ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    private void lock(Component component) {
        if (component instanceof AbstractButton || component instanceof JTextComponent
                || component instanceof JComboBox<?> || component instanceof JSpinner || component instanceof JTable) {
            enabledBeforeTask.put(component, component.isEnabled());
            component.setEnabled(false);
        }
        if (component instanceof Container container) for (Component child : container.getComponents()) lock(child);
    }

    private void closeWindow() {
        if (busy) { message("当前操作正在处理，请等待结果后再关闭窗口。"); return; }
        task("正在关闭商城窗口", () -> {
            try { api.logout(); } catch (ApiException ignored) { /* 后端不可达时也允许关闭窗口。 */ }
            return true;
        }, ignored -> dispose());
    }

    private void showFailure(Throwable failure) {
        String code, text;
        if (failure instanceof MallBusinessException problem) { code = problem.code(); text = problem.getMessage(); }
        else if (failure instanceof ApiException problem) { code = problem.code(); text = problem.getMessage(); }
        else { code = "DESKTOP_OPERATION_FAILED"; text = "操作未完成，请检查商城后端，再查看结果后决定是否重试。"; }
        LOG.warn("桌面操作未完成，错误代码={}，类型={}", code, failure.getClass().getName());
        status.setText("[" + code + "] " + text);
        JOptionPane.showMessageDialog(this, "[" + code + "]\n" + text, "操作未完成", JOptionPane.WARNING_MESSAGE);
        if (failure instanceof ApiException problem && problem.status() == 401) showLogin();
    }

    private void display(JPanel panel) { workspace.removeAll(); workspace.add(panel); refreshLayout(); }
    private void refreshLayout() { body.revalidate(); body.repaint(); }
    private void installStatus() { status.setBorder(new EmptyBorder(11, 28, 12, 28)); body.add(status, BorderLayout.SOUTH); }
    private void message(String text) { JOptionPane.showMessageDialog(this, text, "运动装备商城", JOptionPane.INFORMATION_MESSAGE); }
    private boolean confirm(String text) { return JOptionPane.showConfirmDialog(this, text, "确认操作", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION; }
    private static String optional(String value) { return value == null || value.isBlank() ? "未填写" : value; }
    private static String money(BigDecimal value) { return value.setScale(2).toPlainString(); }
    private static String time(Instant instant) { return instant == null ? "暂无" : TIME.format(instant.atZone(ZONE)); }
    private static String orderStatus(int value) { return switch (value) { case 0 -> "待处理"; case 1 -> "已完成"; case 2 -> "已取消"; default -> "未知"; }; }
    private static LocalDate date(String value) {
        try { return value.isBlank() ? null : LocalDate.parse(value.strip()); }
        catch (DateTimeParseException exception) { throw new MallBusinessException("INVALID_ORDER_DATE", "日期请填写YYYY-MM-DD，或留空不限。"); }
    }
    private static JPanel column() {
        JPanel panel = new JPanel() {
            @Override protected void addImpl(Component component, Object constraints, int index) {
                if (component instanceof JComponent child) child.setAlignmentX(Component.LEFT_ALIGNMENT);
                super.addImpl(component, constraints, index);
            }
        };
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(Color.WHITE);
        return panel;
    }
    private static JPanel row() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 9, 8));
        panel.setBackground(Color.WHITE);
        return panel;
    }
    private static JLabel title(String value, int size) { JLabel label = new JLabel(value); label.setFont(new Font("Microsoft YaHei", Font.BOLD, size)); return label; }
    private static JLabel note(String value) { JLabel label = new JLabel(value); label.setForeground(new Color(86, 103, 104)); label.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12)); return label; }
    private static <T extends JComponent> T named(T component, String name) { component.getAccessibleContext().setAccessibleName(name); return component; }
    private static JButton button(String text, Runnable action) {
        JButton button = new JButton(text); button.addActionListener(event -> action.run());
        button.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        button.setBackground(Color.WHITE); button.setForeground(GREEN); button.setOpaque(true);
        button.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(205, 220, 214)),
                new EmptyBorder(9, 14, 9, 14)));
        button.setAlignmentX(Component.LEFT_ALIGNMENT); button.setMaximumSize(new Dimension(300, 40)); return button;
    }
    private static void addField(JPanel parent, String label, JComponent field) {
        parent.add(Box.createVerticalStrut(16)); parent.add(new JLabel(label)); parent.add(Box.createVerticalStrut(5));
        field.setAlignmentX(Component.LEFT_ALIGNMENT); field.setMaximumSize(new Dimension(560, field instanceof JScrollPane ? 180 : 38)); parent.add(field);
    }
    private static JPanel page(String heading, String description) {
        JPanel page = new JPanel(new BorderLayout(0, 18)); JPanel top = column();
        page.setBackground(Color.WHITE);
        top.add(title(heading, 24)); top.add(Box.createVerticalStrut(8)); top.add(note(description));
        page.add(top, BorderLayout.NORTH); return page;
    }
    private static JTable dataTable(String name, String[] columns, List<Object[]> rows) {
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
            @Override public Class<?> getColumnClass(int column) {
                return getRowCount() == 0 || getValueAt(0, column) == null ? Object.class : getValueAt(0, column).getClass();
            }
        };
        JTable table = named(new JTable(model), name); table.setRowHeight(35); table.setAutoCreateRowSorter(true);
        var renderer = new javax.swing.table.DefaultTableCellRenderer();
        renderer.putClientProperty("html.disable", Boolean.TRUE);
        table.setDefaultRenderer(Object.class, renderer);
        table.setShowVerticalLines(false); table.setGridColor(new Color(224, 232, 228));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setFillsViewportHeight(true);
        fill(table, rows); return table;
    }
    private static void fill(JTable table, List<Object[]> rows) {
        DefaultTableModel model = (DefaultTableModel) table.getModel(); model.setRowCount(0);
        for (Object[] row : rows) model.addRow(row);
    }
    private <T> void selected(JTable table, List<T> rows, Consumer<T> action) {
        int row = table.getSelectedRow();
        if (row < 0) { message("请先在表格中选择一行。"); return; }
        action.accept(rows.get(table.convertRowIndexToModel(row)));
    }
    private static JTextArea textArea(String value, int rows, int columns) {
        JTextArea text = new JTextArea(value, rows, columns); text.setEditable(false); text.setLineWrap(true);
        text.setWrapStyleWord(true); text.setBorder(new EmptyBorder(18, 18, 18, 18)); return text;
    }
    private static JScrollPane scrollText(String value, int rows, int columns) { return new JScrollPane(textArea(value, rows, columns)); }
    private record ItemChoice(ItemView item) { @Override public String toString() { return item.itemId() + " / " + item.title(); } }
    private static JComboBox<ItemChoice> choices(List<ItemView> items, String name) {
        return named(new JComboBox<>(items.stream().map(ItemChoice::new).toArray(ItemChoice[]::new)), name);
    }

}
