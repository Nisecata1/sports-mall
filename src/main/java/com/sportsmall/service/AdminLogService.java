package com.sportsmall.service;

import com.sportsmall.dao.mongo.SystemLogDao;
import com.sportsmall.dao.mysql.LogImportDao;
import com.sportsmall.model.*;
import com.sportsmall.exception.MallDataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

/** 管理员查询和导入。暂存事务先提交，再幂等同步；失败保留PENDING供手动重试。 */
public final class AdminLogService {
    private static final Logger LOG = LoggerFactory.getLogger(AdminLogService.class);
    private final UserAccountService accounts;
    private final SystemLogDao logs;
    private final LogImportDao imports;
    public AdminLogService(UserAccountService accounts, SystemLogDao logs, LogImportDao imports) {
        this.accounts = accounts; this.logs = logs; this.imports = imports;
    }
    public List<SystemLogView> find(AuthenticatedUser user, SystemLogFilter filter) {
        AdminPermissions.require(accounts.viewMyAccount(user));
        return logs.find(filter);
    }
    public LogImportResult importFile(AuthenticatedUser user, String text) {
        var actor = accounts.viewMyAccount(user);
        AdminPermissions.require(actor);
        var entries = LogImportParser.parse(text);
        String batchId = LogImportParser.batchId(actor.userId(), entries);
        imports.stage(actor.userId(), batchId, entries);
        var pending = imports.pending(actor.userId(), batchId);
        if (!pending.isEmpty()) {
            try {
                logs.importRows(actor.userId(), batchId, pending);
                imports.markSynced(actor.userId(), batchId, pending);
            } catch (MallDataAccessException failure) {
                LOG.warn("日志导入待重试，批次={}，代码={}；不会撤销已提交的暂存数据", batchId, failure.code());
            }
        }
        return imports.summary(actor.userId(), batchId);
    }
}
