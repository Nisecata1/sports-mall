package com.sportsmall.integration;

import com.sportsmall.api.*;
import com.sportsmall.client.*;
import com.sportsmall.model.*;
import com.mongodb.client.MongoDatabase;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.util.*;
import static com.mongodb.client.model.Filters.eq;

/** 商品筛选分页的接口、SQL读回和既有MongoDB详情链路；使用已核验的临时数据库。 */
final class CatalogIntegrationChecks {
    private static int checks;
    static int run(URI address,DataSource sql,MongoDatabase mongo) throws Exception {
        checks=0;var client=new MallApiClient(address);
        rejected(() -> client.searchItems(ItemFilter.defaults()),401,"catalog search requires login");
        rejected(client::catalogCategories,401,"catalog categories require login");
        client.login("integration_owner","synthetic-integration-password".toCharArray());
        var admin=new MallApiClient(address);admin.login("integration_admin","synthetic-integration-password".toCharArray());
        var root=admin.createCategory("分页演示根分类",null);
        var child=admin.createCategory("分页演示子分类",root.categoryId());
        var low=admin.createItem("分页演示-甲",root.categoryId(),new BigDecimal("10.00"),1);
        var tie1=admin.createItem("分页演示-乙",root.categoryId(),new BigDecimal("20.00"),1);
        var tie2=admin.createItem("分页演示-丙",root.categoryId(),new BigDecimal("20.00"),1);
        var high=admin.createItem("分页演示-丁",root.categoryId(),new BigDecimal("40.00"),1);
        var sub=admin.createItem("分页演示-子",child.categoryId(),new BigDecimal("15.00"),1);
        var inactive=admin.createItem("分页演示-下架",root.categoryId(),new BigDecimal("5.00"),0);
        var literal=admin.createItem("分页演示-100%_! &+",root.categoryId(),new BigDecimal("25.00"),1);
        check(client.catalogCategories().stream().anyMatch(row -> row.categoryId()==child.categoryId() && Objects.equals(row.parentId(),root.categoryId())),
                "ordinary category options include hierarchy IDs without write permission");
        var filter=new ItemFilter("分页演示",null,null,null,"price_asc",1,2);
        var first=client.searchItems(filter);var second=client.searchItems(filter.withPage(2));var third=client.searchItems(filter.withPage(3));
        check(first.total()==6 && first.totalPages()==3 && first.items().size()==2,"COUNT describes full filtered result before LIMIT");
        check(ids(first).equals(List.of(low.itemId(),sub.itemId())),"ascending price selects first page across categories");
        check(ids(second).equals(List.of(tie1.itemId(),tie2.itemId())),"equal prices are ordered by unique item ID");
        check(ids(third).equals(List.of(literal.itemId(),high.itemId())) && !third.hasNext(),"last page returns correct remaining products");
        var all=new ArrayList<Long>();all.addAll(ids(first));all.addAll(ids(second));all.addAll(ids(third));
        check(new HashSet<>(all).size()==6 && !all.contains(inactive.itemId()),"unchanged data pages have no duplicates and exclude inactive products");
        check(ids(client.searchItems(filter.withPage(2))).equals(ids(second)),"repeated page requests preserve deterministic tie order");
        check(ids(client.searchItems(new ItemFilter("分页演示",null,null,null,"price_desc",1,2))).equals(List.of(high.itemId(),literal.itemId())),
                "price descending sort is global rather than current-page sorting");
        check(ids(client.searchItems(new ItemFilter("分页演示",null,null,null,"price_desc",2,2))).equals(List.of(tie1.itemId(),tie2.itemId())),
                "descending price also uses ascending ID for equal prices");
        var combined=new ItemFilter("分页演示",root.categoryId(),new BigDecimal("20"),new BigDecimal("25"),"id",1,2);
        var combinedResult=client.searchItems(combined);
        check(combinedResult.total()==3 && ids(combinedResult).equals(List.of(tie1.itemId(),tie2.itemId())),"keyword category and inclusive price bounds combine before pagination");
        check(ids(client.searchItems(combined.withPage(2))).equals(List.of(literal.itemId())),"combined filter keeps conditions on next page");
        check(ids(client.searchItems(new ItemFilter("丙",root.categoryId(),new BigDecimal("20"),new BigDecimal("20"),"id",1,10))).equals(List.of(tie2.itemId())),
                "equal price bounds and name substring combine correctly");
        var exact=client.searchItems(new ItemFilter("分页演示",root.categoryId(),null,null,"id",1,100));
        check(exact.total()==5 && !ids(exact).contains(sub.itemId()),"parent category means direct membership not implicit subtree");
        var zero=client.searchItems(new ItemFilter("分页演示",99999999L,null,null,"id",1,10));
        check(zero.total()==0 && zero.items().isEmpty() && zero.totalPages()==0 && !zero.hasNext(),"no matches return empty page and zero total");
        var past=client.searchItems(filter.withPage(999));
        check(past.page()==999 && past.total()==6 && past.items().isEmpty(),"out-of-range page keeps accurate count without silently returning another page");
        check(client.searchItems(new ItemFilter("分页演示",null,null,null,"id",Integer.MAX_VALUE,100)).items().isEmpty(),"large page offset uses long without integer overflow");
        check(client.searchItems(new ItemFilter("分页演示",null,null,null,"id",1,1)).items().size()==1,"one-item pages work for small teaching datasets");
        check(ids(client.searchItems(new ItemFilter("%_! &+",null,null,null,"id",1,10))).equals(List.of(literal.itemId())),
                "URL encoding and LIKE escaping preserve wildcard and plus characters as literal keyword");
        check(client.searchItems(new ItemFilter("' OR 1=1 --",null,null,null,"id",1,10)).total()==0,"SQL-like keyword remains data");
        check(client.searchItems(ItemFilter.defaults()).pageSize()==10,"default search is bounded to ten records");
        try(var connection=sql.getConnection();var statement=connection.prepareStatement(
                "SELECT item_id FROM items WHERE status=1 AND category_id=? AND price>=? AND price<=? ORDER BY item_id")) {
            statement.setLong(1,root.categoryId());statement.setBigDecimal(2,new BigDecimal("20"));statement.setBigDecimal(3,new BigDecimal("25"));
            List<Long> direct=new ArrayList<>();try(var rows=statement.executeQuery()){while(rows.next())direct.add(rows.getLong(1));}
            var viaApi=new ArrayList<>(ids(combinedResult));viaApi.addAll(ids(client.searchItems(combined.withPage(2))));
            check(viaApi.equals(direct),"combined HTTP pages match independent direct SQL query");
        }
        admin.saveItemDetail(tie2.itemId(),"分页后选中商品的详情",List.of(),"zh-CN");
        var selected=client.searchItems(new ItemFilter("丙",null,null,null,"id",1,1)).items().get(0);
        var opened=client.openItem(selected.itemId());
        check(opened.detail().orElseThrow().description().equals("分页后选中商品的详情"),"filtered product opens matching MongoDB detail by ID");
        client.finishBrowse(opened.browseId());
        var order=client.createOrder(selected.itemId(),selected.price());
        check(order.itemId()==tie2.itemId() && order.amount().compareTo(new BigDecimal("20.00"))==0,"filtered row creates order with correct product and quoted price");
        var before=mongo.getCollection("item_details").find(eq("item_id",tie2.itemId())).first();
        client.searchItems(filter);
        check(mongo.getCollection("item_details").find(eq("item_id",tie2.itemId())).first().equals(before),"catalog search does not modify MongoDB details");

        var login=HttpIntegrationProbe.raw(address,"POST","/api/auth/login",null,"{\"username\":\"integration_owner\",\"password\":\"synthetic-integration-password\"}","application/json");
        String token=ApiJson.GSON.fromJson(login.body(),ApiDtos.LoginResponse.class).token();
        for(String query:List.of("page=0","page=-1","page=1.5","page=2147483648","pageSize=0","pageSize=101","pageSize=1.5",
                "categoryId=0","categoryId=1.5","categoryId=9999999999999999999999999999","minPrice=-1","minPrice=0.001",
                "maxPrice=100000000","minPrice=30&maxPrice=10","sort=unknown","userId=2","status=0","page=1&page=2")) {
            check(HttpIntegrationProbe.raw(address,"GET","/api/items/search?"+query,token,null,null).statusCode()==400,"invalid catalog query rejected: "+query);
        }
        check(HttpIntegrationProbe.raw(address,"GET","/api/items/search?keyword="+"a".repeat(201),token,null,null).statusCode()==400,"overlong keyword rejected through HTTP");
        check(HttpIntegrationProbe.raw(address,"POST","/api/items/search",token,"{}","application/json").statusCode()==405,"catalog search rejects unsupported method");
        check(HttpIntegrationProbe.raw(address,"GET","/api/categories?role=ADMIN",token,null,null).statusCode()==400,"category options reject identity query");
        rejected(() -> client.createCategory("ordinary denied",null),403,"read-only catalog categories do not grant category write access");
        try(var connection=sql.getConnection();var statement=connection.prepareStatement("UPDATE users SET status=0 WHERE user_id=1")){statement.executeUpdate();}
        try {
            rejected(() -> client.searchItems(filter),400,"disabled current user cannot search");
            rejected(client::catalogCategories,400,"disabled current user cannot read category options");
        } finally {
            try(var connection=sql.getConnection();var statement=connection.prepareStatement("UPDATE users SET status=1 WHERE user_id=1")){statement.executeUpdate();}
        }
        client.logout();admin.logout();HttpIntegrationProbe.raw(address,"POST","/api/auth/logout",token,"{}","application/json");
        return checks;
    }
    private static List<Long> ids(ItemPage page){return page.items().stream().map(ItemView::itemId).toList();}
    private static void rejected(Runnable action,int status,String label) {
        try{action.run();throw new AssertionError(label);}catch(ApiException failure){check(failure.status()==status,label);}
    }
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
}
