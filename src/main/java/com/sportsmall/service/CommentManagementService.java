package com.sportsmall.service;

import com.sportsmall.dao.mongo.CommentDao;
import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.model.*;
import java.util.*;
import java.util.function.*;

/** 本人修改/删除；管理员只审核删除，不冒充原作者修改内容。下架商品的历史评论也可维护。 */
public final class CommentManagementService {
    private final Function<AuthenticatedUser, UserAccount> accounts;
    private final LongFunction<List<CommentView>> mine;
    private final Supplier<List<CommentView>> all;
    private final Updater updater;
    private final Deleter deleter;

    public CommentManagementService(UserAccountService accounts, CommentDao comments) {
        this(accounts::viewMyAccount, comments::findByUserId, comments::findForManagement,
                comments::updateOwned, comments::delete);
    }
    CommentManagementService(Function<AuthenticatedUser, UserAccount> accounts,
            LongFunction<List<CommentView>> mine, Supplier<List<CommentView>> all, Updater updater, Deleter deleter) {
        this.accounts=Objects.requireNonNull(accounts); this.mine=Objects.requireNonNull(mine);
        this.all=Objects.requireNonNull(all); this.updater=Objects.requireNonNull(updater); this.deleter=Objects.requireNonNull(deleter);
    }
    public List<CommentView> mine(AuthenticatedUser current) {
        return List.copyOf(mine.apply(accounts.apply(current).userId()));
    }
    public List<CommentView> all(AuthenticatedUser current) {
        AdminPermissions.require(accounts.apply(current)); return List.copyOf(all.get());
    }
    public CommentView updateMine(AuthenticatedUser current,String id,String content,int rating,String tags) {
        var account=accounts.apply(current); requireId(id);
        String text=content==null ? "" : content.strip();
        if(text.isEmpty() || text.codePointCount(0,text.length())>2000)
            throw new MallBusinessException("INVALID_COMMENT_CONTENT","评论文字不能为空，且不能超过2000个字符。");
        if(rating<1 || rating>5) throw new MallBusinessException("INVALID_COMMENT_RATING","评分请填写1至5的整数。");
        return updater.update(account.userId(),id,text,rating,CommentService.normalizeTags(tags));
    }
    public boolean deleteMine(AuthenticatedUser current,String id) {
        var account=accounts.apply(current); requireId(id); return deleter.delete(id,account.userId());
    }
    public boolean deleteAsAdmin(AuthenticatedUser current,String id) {
        AdminPermissions.require(accounts.apply(current)); requireId(id); return deleter.delete(id,null);
    }
    private static void requireId(String id) {
        if(id==null || !id.matches("[0-9a-fA-F]{24}"))
            throw new MallBusinessException("INVALID_COMMENT_ID","评论编号格式不正确，请从列表选择。");
    }
    @FunctionalInterface interface Updater { CommentView update(long owner,String id,String content,int rating,List<String> tags); }
    @FunctionalInterface interface Deleter { boolean delete(String id,Long owner); }
}
