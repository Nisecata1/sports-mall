package com.sportsmall.cli;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.model.ActionLogView;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.ItemDetail;
import com.sportsmall.model.ItemView;
import com.sportsmall.service.BrowsingService;
import com.sportsmall.service.StatisticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Console;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 浏览详情到按回车结束的时间，作为当前CLI的一次实际停留时间。 */
public final class BrowsingMenu {
    private static final Logger LOG = LoggerFactory.getLogger(BrowsingMenu.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private BrowsingMenu() { }

    public static void browse(Console console, AuthenticatedUser user, BrowsingService browsing) {
        List<ItemView> items = browsing.availableFor(user);
        if (items.isEmpty()) {
            System.out.println("暂无可浏览的商品。");
            return;
        }
        System.out.println("商品编号 | 商品名称 | 单价（元）");
        for (ItemView item : items) {
            System.out.printf("%d | %s | %s%n", item.itemId(), item.title(), item.price().setScale(2).toPlainString());
        }
        String input = console.readLine("要浏览的商品编号（留空返回）：");
        if (input == null || input.isBlank()) { return; }
        long itemId;
        try {
            itemId = Long.parseLong(input.strip());
        } catch (NumberFormatException exception) {
            throw new MallBusinessException("INVALID_BROWSE_ITEM", "商品编号应为列表中的整数。");
        }
        if (items.stream().noneMatch(item -> item.itemId() == itemId)) {
            throw new MallBusinessException("BROWSE_ITEM_NOT_AVAILABLE", "请从上方列表选择商品。");
        }
        BrowsingService.OpenedItem page = browsing.openFor(user, itemId);
        long started = System.nanoTime();
        System.out.printf("商品：%s（编号%d）%n分类：%s%n单价：%s元%n", page.item().title(), itemId,
                page.item().categoryName(), page.item().price().setScale(2).toPlainString());
        if (page.detail().isPresent()) {
            ItemDetail detail = page.detail().get();
            System.out.printf("商品说明：%s%n内容语言：%s%n商品图片：%s%n", detail.description(), detail.language(),
                    detail.images().isEmpty() ? "暂无图片" : String.join("，", detail.images()));
        } else {
            System.out.println("商品说明：尚未填写");
        }
        if (console.readLine("看完后按回车结束本次浏览并返回菜单：") == null) { return; }
        long seconds = TimeUnit.NANOSECONDS.toSeconds(Math.max(0, System.nanoTime() - started));
        ActionLogView saved = browsing.finishFor(user, page, seconds);
        System.out.printf("浏览记录已保存：行为编号=%s，商品编号=%d，本次停留=%d秒。%n",
                saved.actionId(), saved.itemId(), saved.durationSeconds());
        LOG.info("商品浏览记录已保存，行为编号={}，用户编号={}，商品编号={}",
                saved.actionId(), saved.userId(), saved.itemId());
    }

    public static void showHot(AuthenticatedUser user, StatisticsService statistics) {
        StatisticsService.HotReport report = statistics.hotItemsFor(user);
        System.out.printf("近7天商品浏览排行（最多10项）%n统计范围：%s 至 %s%n",
                TIME.format(report.fromInclusive()), TIME.format(report.throughInclusive()));
        System.out.println("排序：浏览次数优先，同次数比较累计停留秒数，再按商品编号。");
        if (report.items().isEmpty()) {
            System.out.println("该时间范围内暂无商品浏览记录。");
            return;
        }
        System.out.println("排名 | 商品编号 | 商品名称 | 浏览次数 | 累计停留（秒）");
        int rank = 0;
        for (StatisticsService.RankedItem item : report.items()) {
            System.out.printf("%d | %d | %s | %d | %d%n", ++rank, item.statistics().itemId(),
                    item.item().map(ItemView::title).orElse("当前不可浏览"),
                    item.statistics().viewCount(), item.statistics().durationSeconds());
        }
    }
}
