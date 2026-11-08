package com.sportsmall.dao.mysql;

import com.sportsmall.exception.*;
import com.sportsmall.model.AdminUserSummary;
import com.sportsmall.util.JdbcTransactions;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** 课程小数据量下按主键锁定用户，串行化角色/启停修改，避免并发移除所有启用管理员。 */
public final class AdminUserMutationDao {
    private final DataSource source;
    public AdminUserMutationDao(DataSource source) { this.source=Objects.requireNonNull(source); }
    public AdminUserSummary update(long actor,long target,String role,int status) {
        try {
            return JdbcTransactions.execute(source, connection -> {
                List<AdminUserSummary> users=new ArrayList<>();
                try(var statement=connection.prepareStatement("SELECT user_id,username,role,status FROM users ORDER BY user_id FOR UPDATE");
                    var rows=statement.executeQuery()) {
                    while(rows.next()) users.add(new AdminUserSummary(rows.getLong(1),rows.getString(2),rows.getString(3),rows.getInt(4)));
                }
                var admin=users.stream().filter(u->u.userId()==actor).findFirst().orElse(null);
                if(admin==null || admin.status()!=1 || !"ADMIN".equals(admin.role())) throw denied();
                var user=users.stream().filter(u->u.userId()==target).findFirst()
                        .orElseThrow(()->new MallBusinessException("ADMIN_USER_NOT_FOUND","目标账号已不存在，请刷新列表。"));
                if(actor==target && (status!=1 || !"ADMIN".equals(role)))
                    throw new MallBusinessException("ADMIN_SELF_CHANGE_DENIED","不能停用或降级当前操作账号，请由其他管理员处理。");
                if("ADMIN".equals(user.role()) && user.status()==1 && (status!=1 || !"ADMIN".equals(role))
                        && users.stream().filter(u->u.status()==1 && "ADMIN".equals(u.role())).count()<=1)
                    throw new MallBusinessException("LAST_ADMIN_REQUIRED","至少保留一个启用的管理员。");
                try(var statement=connection.prepareStatement("UPDATE users SET role=?,status=? WHERE user_id=?")) {
                    statement.setString(1,role);statement.setInt(2,status);statement.setLong(3,target);statement.executeUpdate();
                }
                return new AdminUserSummary(target,user.username(),role,status);
            });
        } catch(SQLException failure) {
            throw new MallDataAccessException("ADMIN_USER_SAVE_UNCONFIRMED","账号维护结果未确认，请刷新用户列表后再决定是否重试。");
        }
    }
    static void requireActiveAdmin(Connection connection,long actor) throws SQLException {
        try(var statement=connection.prepareStatement("SELECT role,status FROM users WHERE user_id=? FOR SHARE")) {
            statement.setLong(1,actor);
            try(var row=statement.executeQuery()) {
                if(!row.next() || row.getInt("status")!=1 || !"ADMIN".equals(row.getString("role"))) throw denied();
            }
        }
    }
    private static MallBusinessException denied() {
        return new MallBusinessException("ADMIN_REQUIRED","当前账号已无管理员权限，请重新登录或联系管理员。");
    }
}
