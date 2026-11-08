package com.sportsmall.service;

import com.mongodb.MongoException;
import com.sportsmall.dao.mongo.ItemDetailDao;
import com.sportsmall.dao.mysql.AdminItemDao;
import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.*;

import java.net.URI;
import java.util.*;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;

/** 后端详情业务：先核对MySQL身份和商品，只有MongoDB文档发生写入，不承诺跨库原子事务。 */
public final class AdminItemDetailService {
    private final Function<AuthenticatedUser, UserAccount> accounts;
    private final LongFunction<Optional<AdminItemView>> items;
    private final LongFunction<Optional<ItemDetail>> details;
    private final Function<ItemDetail, ItemDetail> writer;
    private final LongPredicate deleter;

    public AdminItemDetailService(UserAccountService accounts, AdminItemDao items, ItemDetailDao details) {
        this(Objects.requireNonNull(accounts)::viewMyAccount, Objects.requireNonNull(items)::findById,
                Objects.requireNonNull(details)::findByItemId, details::save, details::deleteByItemId);
    }

    AdminItemDetailService(Function<AuthenticatedUser, UserAccount> accounts,
                           LongFunction<Optional<AdminItemView>> items, LongFunction<Optional<ItemDetail>> details,
                           Function<ItemDetail, ItemDetail> writer, LongPredicate deleter) {
        this.accounts = Objects.requireNonNull(accounts); this.items = Objects.requireNonNull(items);
        this.details = Objects.requireNonNull(details); this.writer = Objects.requireNonNull(writer);
        this.deleter = Objects.requireNonNull(deleter);
    }

    public Optional<ItemDetail> viewFor(AuthenticatedUser user, long itemId) {
        requireItem(user, itemId);
        try { return details.apply(itemId); }
        catch (MongoException | IllegalArgumentException failure) {
            throw new MallDataAccessException("ITEM_DETAIL_QUERY_FAILED", "商品详情读取失败，请检查MongoDB连接及详情字段类型。");
        }
    }

    public ItemDetail saveFor(AuthenticatedUser user, long itemId, String inputDescription, List<String> inputImages, String inputLanguage) {
        requireItem(user, itemId);
        String description = inputDescription == null ? "" : inputDescription.strip();
        if (description.isEmpty() || description.codePointCount(0, description.length()) > 5000) {
            throw new MallBusinessException("INVALID_DETAIL_DESCRIPTION", "商品说明须为1至5000个字符。");
        }
        String language = inputLanguage == null ? "" : inputLanguage.strip();
        if (language.length() > 35 || !language.matches("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*")) {
            throw new MallBusinessException("INVALID_DETAIL_LANGUAGE", "语言请填写zh-CN、en等标记，最长35个字符。");
        }
        if (inputImages == null || inputImages.size() > 10) {
            throw new MallBusinessException("INVALID_DETAIL_IMAGES", "图片地址列表必填，可为空列表，最多10项。");
        }
        List<String> images = new ArrayList<>();
        for (String input : inputImages) {
            String image = input == null ? "" : input.strip();
            try {
                URI uri = URI.create(image);
                if (image.length() > 2048 || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
            } catch (IllegalArgumentException failure) {
                throw new MallBusinessException("INVALID_DETAIL_IMAGES", "每张图片请填写有效的HTTP或HTTPS地址，最长2048个字符。");
            }
            images.add(image);
        }
        return writer.apply(new ItemDetail(itemId, description, images, language));
    }

    public boolean deleteFor(AuthenticatedUser user, long itemId) {
        requireItem(user, itemId);
        return deleter.test(itemId);
    }

    private void requireItem(AuthenticatedUser user, long itemId) {
        UserAccount account = accounts.apply(user);
        if (!"ADMIN".equals(account.role())) throw new MallBusinessException("ADMIN_REQUIRED", "此操作仅限管理员账号。");
        if (itemId < 1) throw new MallBusinessException("INVALID_ITEM_ID", "商品编号须为正整数。");
        if (items.apply(itemId).isEmpty()) throw new MallBusinessException("ITEM_NOT_FOUND", "该商品已不存在，请刷新商品管理列表。");
    }
}
