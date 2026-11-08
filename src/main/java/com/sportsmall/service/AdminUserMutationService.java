package com.sportsmall.service;

import com.sportsmall.dao.mysql.AdminUserMutationDao;
import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.model.*;
import java.util.*;
import java.util.function.Function;

public final class AdminUserMutationService {
    private final Function<AuthenticatedUser,UserAccount> accounts;
    private final Writer writer;
    public AdminUserMutationService(UserAccountService accounts,AdminUserMutationDao dao) {
        this(accounts::viewMyAccount,dao::update);
    }
    AdminUserMutationService(Function<AuthenticatedUser,UserAccount> accounts,Writer writer) {
        this.accounts=Objects.requireNonNull(accounts);this.writer=Objects.requireNonNull(writer);
    }
    public AdminUserSummary update(AuthenticatedUser current,long target,String role,int status) {
        var actor=accounts.apply(current);AdminPermissions.require(actor);
        if(target<1 || !Set.of("USER","ADMIN").contains(role==null ? "" : role) || (status!=0 && status!=1))
            throw new MallBusinessException("INVALID_ADMIN_USER","请选择有效账号、角色和启停状态。");
        return writer.update(actor.userId(),target,role,status);
    }
    @FunctionalInterface interface Writer { AdminUserSummary update(long actor,long target,String role,int status); }
}
