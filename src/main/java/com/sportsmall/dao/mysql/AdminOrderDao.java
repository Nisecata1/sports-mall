package com.sportsmall.dao.mysql;

import com.sportsmall.exception.*;
import com.sportsmall.model.*;
import com.sportsmall.util.JdbcTransactions;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** 管理员只允许待处理订单进入完成或取消终态；金额等历史字段不改写。 */
public final class AdminOrderDao {
    private static final String SELECT = """
            SELECT o.order_id,o.user_id,o.item_id,i.title AS item_title,o.amount,o.status,o.created_at
            FROM orders o JOIN items i ON i.item_id=o.item_id
            """;
    private static final String LIST = SELECT + """
            WHERE (? IS NULL OR o.user_id=?) AND (? IS NULL OR o.status=?)
              AND (? IS NULL OR o.created_at>=?) AND (? IS NULL OR o.created_at<?)
            ORDER BY o.created_at DESC,o.order_id DESC LIMIT 200
            """;
    private final DataSource source;
    public AdminOrderDao(DataSource source) { this.source=Objects.requireNonNull(source); }
    public List<OrderView> find(Long userId,OrderFilter filter) {
        try(var connection=source.getConnection();var statement=connection.prepareStatement(LIST)) {
            for(int n=1;n<=2;n++) { if(userId==null)statement.setNull(n,Types.BIGINT);else statement.setLong(n,userId); }
            for(int n=3;n<=4;n++) { if(filter.status()==null)statement.setNull(n,Types.TINYINT);else statement.setInt(n,filter.status()); }
            var start=filter.startInclusive()==null ? null : Timestamp.valueOf(filter.startInclusive());
            var end=filter.endExclusive()==null ? null : Timestamp.valueOf(filter.endExclusive());
            statement.setTimestamp(5,start);statement.setTimestamp(6,start);statement.setTimestamp(7,end);statement.setTimestamp(8,end);
            List<OrderView> result=new ArrayList<>();
            try(var rows=statement.executeQuery()) { while(rows.next())result.add(map(rows)); }
            return List.copyOf(result);
        } catch(SQLException failure) {
            throw new MallDataAccessException("ADMIN_ORDER_QUERY_FAILED","订单管理查询失败，请检查MySQL连接。");
        }
    }
    public OrderView changeStatus(long actor,long id,int status) {
        try {
            return JdbcTransactions.execute(source,connection->{
                AdminUserMutationDao.requireActiveAdmin(connection,actor);
                try(var statement=connection.prepareStatement("SELECT status FROM orders WHERE order_id=? FOR UPDATE")) {
                    statement.setLong(1,id);
                    try(var row=statement.executeQuery()) {
                        if(!row.next() || row.getInt(1)!=0)
                            throw new MallBusinessException("ADMIN_ORDER_NOT_PENDING","订单已不存在或已结束，请刷新列表。");
                    }
                }
                try(var statement=connection.prepareStatement("UPDATE orders SET status=? WHERE order_id=? AND status=0")) {
                    statement.setInt(1,status);statement.setLong(2,id);
                    if(statement.executeUpdate()!=1)throw new SQLException("Order status write count mismatch");
                }
                try(var statement=connection.prepareStatement(SELECT+" WHERE o.order_id=?")) {
                    statement.setLong(1,id);
                    try(var row=statement.executeQuery()) {
                        if(!row.next())throw new SQLException("Order readback missing");
                        return map(row);
                    }
                }
            });
        } catch(SQLException failure) {
            if("45000".equals(failure.getSQLState()))
                throw new MallBusinessException("ADMIN_ORDER_REJECTED","数据库订单规则拒绝了状态变更，请刷新订单。");
            throw new MallDataAccessException("ADMIN_ORDER_SAVE_UNCONFIRMED","订单处理结果未确认，请刷新订单后再决定是否重试。");
        }
    }
    private static OrderView map(ResultSet row) throws SQLException {
        return new OrderView(row.getLong("order_id"),row.getLong("user_id"),row.getLong("item_id"),
                row.getString("item_title"),row.getBigDecimal("amount"),row.getInt("status"),
                row.getTimestamp("created_at").toLocalDateTime());
    }
}
