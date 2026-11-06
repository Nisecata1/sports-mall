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
        JPanel page = page("数据统计", "三类统计直接读取数据库中的实际记录");
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
        ((JPanel) page.getComponent(0)).add(actions);
        output.setText("请选择一种统计。\n\n统计结果来自 MongoDB 聚合，商品名称关联 MySQL。\n近7天为滚动7×24小时，活跃日期按北京时间去重。");
        display(page);
    }

    private void showAccount() {
        task("正在读取本人账号", () -> api.account(), account -> {
            JPanel page = page("我的账号", "管理本人的账号信息与订单概览");
            JTextArea output = textArea("用户编号：" + account.userId() + "\n用户名：" + account.username()
                    + "\n邮箱：" + account.email() + "\n联系电话：" + optional(account.phone()) + "\n角色：" + account.role()
                    + "\n状态：" + (account.status() == 1 ? "正常" : "停用"), 15, 40);
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
            page.add(actions, BorderLayout.SOUTH);
            display(page);
        });
    }

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
