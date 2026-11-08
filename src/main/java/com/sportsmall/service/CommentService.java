package com.sportsmall.service;

import com.sportsmall.dao.ItemDao;
import com.sportsmall.dao.mongo.CommentDao;
import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.CommentView;
import com.sportsmall.model.ItemView;
import com.sportsmall.model.UserAccount;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 已登录用户评论在售商品；先核对MySQL引用，再写MongoDB，未承诺跨库原子事务。 */
public final class CommentService {
    private static final int RECENT_LIMIT = 20;
    private final AccountReader accounts;
    private final ItemListReader itemList;
    private final ItemReader items;
    private final CommentWriter writer;
    private final CommentReader reader;

    public CommentService(UserAccountService accounts, ItemDao items, CommentDao comments) {
        this(Objects.requireNonNull(accounts)::viewMyAccount, Objects.requireNonNull(items)::findActive,
                items::findActiveById, Objects.requireNonNull(comments)::insert, comments::findLatestByItemId);
    }

    CommentService(AccountReader accounts, ItemListReader itemList, ItemReader items,
                   CommentWriter writer, CommentReader reader) {
        this.accounts = Objects.requireNonNull(accounts);
        this.itemList = Objects.requireNonNull(itemList);
        this.items = Objects.requireNonNull(items);
        this.writer = Objects.requireNonNull(writer);
        this.reader = Objects.requireNonNull(reader);
    }

    public List<ItemView> availableItemsFor(AuthenticatedUser currentUser) {
        accounts.read(currentUser);
        return itemList.find();
    }

    public CommentView post(AuthenticatedUser currentUser, long itemId, String content, int rating, String tagsInput) {
        requireItemId(itemId);
        String text = content == null ? "" : content.strip();
        if (text.isEmpty() || text.codePointCount(0, text.length()) > 2000) {
            throw new MallBusinessException("INVALID_COMMENT_CONTENT", "评论文字不能为空，且不能超过2000个字符。");
        }
        if (rating < 1 || rating > 5) {
            throw new MallBusinessException("INVALID_COMMENT_RATING", "评分请填写1至5的整数。");
        }
        List<String> tags = normalizeTags(tagsInput);
        UserAccount account = accounts.read(currentUser);
        requireActiveItem(itemId);
        return writer.insert(account.userId(), itemId, text, rating, tags);
    }

    public List<CommentView> latestFor(AuthenticatedUser currentUser, long itemId) {
        requireItemId(itemId);
        accounts.read(currentUser);
        requireActiveItem(itemId);
        return reader.find(itemId, RECENT_LIMIT);
    }

    private void requireActiveItem(long itemId) {
        if (items.find(itemId).isEmpty()) {
            throw new MallBusinessException("COMMENT_ITEM_NOT_AVAILABLE", "该商品不存在或已不在售，请重新选择商品。");
        }
    }

    private static void requireItemId(long itemId) {
        if (itemId < 1) {
            throw new MallBusinessException("INVALID_COMMENT_ITEM", "请选择列表中的商品编号。");
        }
    }

    static List<String> normalizeTags(String input) {
        List<String> tags = input == null || input.isBlank() ? List.of()
                : Arrays.stream(input.split("[,，]"))
                        .map(String::strip).filter(tag -> !tag.isEmpty()).distinct().toList();
        if (tags.size() > 5 || tags.stream().anyMatch(tag -> tag.codePointCount(0, tag.length()) > 20)) {
            throw new MallBusinessException("INVALID_COMMENT_TAGS", "标签最多5个，每个最多20个字符，用逗号分隔。");
        }
        return tags;
    }

    @FunctionalInterface
    interface AccountReader { UserAccount read(AuthenticatedUser currentUser); }
    @FunctionalInterface
    interface ItemListReader { List<ItemView> find(); }
    @FunctionalInterface
    interface ItemReader { Optional<ItemView> find(long itemId); }
    @FunctionalInterface
    interface CommentWriter { CommentView insert(long userId, long itemId, String content, int rating, List<String> tags); }
    @FunctionalInterface
    interface CommentReader { List<CommentView> find(long itemId, int limit); }
}
