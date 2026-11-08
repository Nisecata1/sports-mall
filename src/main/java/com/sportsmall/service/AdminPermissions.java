package com.sportsmall.service;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.model.UserAccount;

/** 调用方先读取当前启用账号，再应用此管理员规则；不依据登录角色快照授权。 */
final class AdminPermissions {
    private AdminPermissions() {}
    static void require(UserAccount account) {
        if(!"ADMIN".equals(account.role()))
            throw new MallBusinessException("ADMIN_REQUIRED","此操作仅限管理员账号。");
    }
}
