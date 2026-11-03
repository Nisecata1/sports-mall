package com.sportsmall.cli;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.CommentView;
import com.sportsmall.model.CommentStatistics;
import com.sportsmall.model.ItemView;
import com.sportsmall.service.CommentService;
import com.sportsmall.service.StatisticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Console;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** 评论交互入口；本人编号由登录身份提供，商品从MySQL列表选择。 */
public final class CommentMenu {
    private static final Logger LOG = LoggerFactory.getLogger(CommentMenu.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private CommentMenu() {
    }

    public static void post(Console console, AuthenticatedUser user, CommentService comments) {
        ItemView item = chooseItem(console, user, comments);
        if (item == null) { return; }
        String ratingInput = console.readLine("评分（1至5的整数）：");
        if (ratingInput == null) { return; }
        int rating;
        try {
            rating = Integer.parseInt(ratingInput.strip());
        } catch (NumberFormatException exception) {
            throw new MallBusinessException("INVALID_COMMENT_RATING", "评分请填写1至5的整数。");
        }
        String content = console.readLine("评论文字（最多2000个字符）：");
        if (content == null) { return; }
        String tags = console.readLine("标签（逗号分隔，最多5个，可留空）：");
        if (tags == null) { return; }
        console.printf("将为“%s”发表评论，评分%d分。%n", item.title(), rating);
        String confirmation = console.readLine("输入y保存评论，其他输入返回：");
        if (confirmation == null || !"y".equalsIgnoreCase(confirmation.strip())) {
            System.out.println("本次未保存评论。");
            return;
        }
        CommentView saved = comments.post(user, item.itemId(), content, rating, tags);
        System.out.println("评论已保存。");
        showComment(saved);
        LOG.info("商城评论已保存，评论编号={}，用户编号={}，商品编号={}",
                saved.commentId(), saved.userId(), saved.itemId());
    }

    public static void showLatest(Console console, AuthenticatedUser user, CommentService comments) {
        ItemView item = chooseItem(console, user, comments);
        if (item == null) { return; }
        List<CommentView> latest = comments.latestFor(user, item.itemId());
        System.out.printf("商品：%s；最新评论（最多20条）%n", item.title());
        if (latest.isEmpty()) {
            System.out.println("该商品还没有评论。");
            return;
        }
        for (CommentView comment : latest) {
            showComment(comment);
        }
        System.out.printf("本次显示%d条评论。%n", latest.size());
    }

    public static void showStatistics(Console console, AuthenticatedUser user, CommentService comments,
                                      StatisticsService statistics) {
        ItemView item = chooseItem(console, user, comments);
        if (item == null) { return; }
        CommentStatistics result = statistics.commentStatsFor(user, item.itemId());
        System.out.printf("商品：%s（编号%d）%n统计范围：该商品的全部评论。%n评论条数：%d%n",
                item.title(), item.itemId(), result.commentCount());
        if (result.averageRating() == null) {
            System.out.println("平均评分：暂无评分");
        } else {
            System.out.printf(Locale.ROOT, "平均评分：%.2f/5%n", result.averageRating());
        }
        System.out.println("评分 | 评论条数");
        for (int score = 1; score <= 5; score++) {
            System.out.printf("%d分 | %d%n", score, result.ratingCounts().get(score - 1));
        }
        LOG.info("商城评论聚合完成，商品编号={}，评论条数={}", item.itemId(), result.commentCount());
    }

    private static ItemView chooseItem(Console console, AuthenticatedUser user, CommentService comments) {
        List<ItemView> items = comments.availableItemsFor(user);
        if (items.isEmpty()) {
            System.out.println("暂无可选商品。");
            return null;
        }
        System.out.println("商品编号 | 商品名称 | 分类");
        for (ItemView item : items) {
            System.out.printf("%d | %s | %s%n", item.itemId(), item.title(), item.categoryName());
        }
        String input = console.readLine("商品编号（留空返回）：");
        if (input == null || input.isBlank()) { return null; }
        long itemId;
        try {
            itemId = Long.parseLong(input.strip());
        } catch (NumberFormatException exception) {
            throw new MallBusinessException("INVALID_COMMENT_ITEM", "商品编号应为列表中的整数。");
        }
        return items.stream().filter(item -> item.itemId() == itemId).findFirst()
                .orElseThrow(() -> new MallBusinessException("COMMENT_ITEM_NOT_AVAILABLE", "请从上方列表选择商品。"));
    }

    private static void showComment(CommentView comment) {
        System.out.printf("评论编号：%s%n用户编号：%d | 商品编号：%d | 评分：%d/5%n文字：%s%n标签：%s%n创建时间：%s%n%n",
                comment.commentId(), comment.userId(), comment.itemId(), comment.rating(), comment.content(),
                comment.tags().isEmpty() ? "无" : String.join("，", comment.tags()), TIME.format(comment.createdAt()));
    }
}
