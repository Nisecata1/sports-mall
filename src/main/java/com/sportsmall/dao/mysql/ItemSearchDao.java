package com.sportsmall.dao.mysql;

import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.*;
import com.sportsmall.util.JdbcTransactions;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** 先筛选排序再分页；COUNT与本页在同一可重复读快照内执行。SQL文本固定，输入只绑定参数。 */
public final class ItemSearchDao {
    private static final String COUNT = """
            SELECT COUNT(*) FROM items i JOIN categories c ON c.category_id=i.category_id
            WHERE i.status=1 AND (? IS NULL OR i.title LIKE ? ESCAPE '!')
              AND (? IS NULL OR i.category_id=?) AND (? IS NULL OR i.price>=?) AND (? IS NULL OR i.price<=?)
            """;
    private static final String PAGE = """
            SELECT i.item_id,i.title,i.price,i.category_id,c.name AS category_name
            FROM items i JOIN categories c ON c.category_id=i.category_id
            WHERE i.status=1 AND (? IS NULL OR i.title LIKE ? ESCAPE '!')
              AND (? IS NULL OR i.category_id=?) AND (? IS NULL OR i.price>=?) AND (? IS NULL OR i.price<=?)
            ORDER BY CASE WHEN ?='price_asc' THEN i.price END ASC,
                     CASE WHEN ?='price_desc' THEN i.price END DESC, i.item_id ASC
            LIMIT ? OFFSET ?
            """;
    private final DataSource dataSource;
    public ItemSearchDao(DataSource dataSource) { this.dataSource=Objects.requireNonNull(dataSource); }
    public ItemPage search(ItemFilter filter) {
        try {
            return JdbcTransactions.execute(dataSource, connection -> {
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setReadOnly(true);
                long total;
                try (var statement=connection.prepareStatement(COUNT)) {
                    bind(statement,filter);
                    try(var rows=statement.executeQuery()) { rows.next();total=rows.getLong(1); }
                }
                List<ItemView> items=new ArrayList<>();
                try(var statement=connection.prepareStatement(PAGE)) {
                    bind(statement,filter);
                    statement.setString(9,filter.sort());statement.setString(10,filter.sort());
                    statement.setInt(11,filter.pageSize());statement.setLong(12,filter.offset());
                    try(var rows=statement.executeQuery()) {
                        while(rows.next()) items.add(new ItemView(rows.getLong("item_id"),rows.getString("title"),
                                rows.getBigDecimal("price"),rows.getLong("category_id"),rows.getString("category_name")));
                    }
                }
                return new ItemPage(items,total,filter.page(),filter.pageSize());
            });
        } catch(SQLException failure) {
            throw new MallDataAccessException("ITEM_SEARCH_FAILED","商品筛选读取失败，请检查MySQL连接和商品、分类表。");
        }
    }
    private static void bind(PreparedStatement statement,ItemFilter filter) throws SQLException {
        for(int index=1;index<=2;index++) statement.setString(index,filter.keywordPattern());
        for(int index=3;index<=4;index++) {
            if(filter.categoryId()==null)statement.setNull(index,Types.BIGINT);else statement.setLong(index,filter.categoryId());
        }
        for(int index=5;index<=6;index++) statement.setBigDecimal(index,filter.minPrice());
        for(int index=7;index<=8;index++) statement.setBigDecimal(index,filter.maxPrice());
    }
}
