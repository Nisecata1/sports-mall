package com.sportsmall.service;

import com.sportsmall.dao.mysql.AdminOrderDao;
import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.model.*;
import java.util.*;
import java.util.function.*;

public final class AdminOrderService {
    private final Function<AuthenticatedUser,UserAccount> accounts;
    private final BiFunction<Long,OrderFilter,List<OrderView>> reader;
    private final Writer writer;
    public AdminOrderService(UserAccountService accounts,AdminOrderDao dao) { this(accounts::viewMyAccount,dao::find,dao::changeStatus); }
    AdminOrderService(Function<AuthenticatedUser,UserAccount> accounts,BiFunction<Long,OrderFilter,List<OrderView>> reader,Writer writer) {
        this.accounts=Objects.requireNonNull(accounts);this.reader=Objects.requireNonNull(reader);this.writer=Objects.requireNonNull(writer);
    }
    public List<OrderView> find(AuthenticatedUser current,Long userId,OrderFilter filter) {
        AdminPermissions.require(accounts.apply(current));
        if(userId!=null && userId<1)throw new MallBusinessException("INVALID_ORDER_USER","用户编号须为正整数，留空不限。");
        return List.copyOf(reader.apply(userId,Objects.requireNonNull(filter)));
    }
    public OrderView changeStatus(AuthenticatedUser current,long id,int status) {
        var actor=accounts.apply(current);AdminPermissions.require(actor);
        if(id<1 || (status!=1 && status!=2)) throw new MallBusinessException("INVALID_ORDER_STATUS","请选择待处理订单，并将其完成或取消。");
        return writer.update(actor.userId(),id,status);
    }
    @FunctionalInterface interface Writer { OrderView update(long actor,long id,int status); }
}
