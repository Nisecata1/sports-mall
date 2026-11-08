package com.sportsmall.integration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.sportsmall.api.*;
import com.sportsmall.client.*;
import com.sportsmall.model.OrderFilter;
import org.bson.Document;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import static com.mongodb.client.model.Filters.eq;

/** 仅在父探针核实的临时双库中验证详情链路；不连接课程主库。 */
final class ItemDetailIntegrationChecks {
    private static int checks;

    static int run(URI address, DataSource sql, MongoDatabase mongo) throws Exception {
        checks = 0;
        var admin = new MallApiClient(address);
        denied(() -> admin.adminItemDetail(1), 401, "anonymous detail read denied");
        denied(() -> admin.saveItemDetail(1, "说明", List.of(), "en"), 401, "anonymous detail write denied");
        denied(() -> admin.deleteItemDetail(1), 401, "anonymous detail deletion denied");
        var actor = admin.login("integration_admin", "synthetic-integration-password".toCharArray());
        var ordinary = new MallApiClient(address);
        ordinary.login("integration_owner", "synthetic-integration-password".toCharArray());
        var item = admin.createItem("详情隔离演示商品", 1, new BigDecimal("25.00"), 0);
        long id = item.itemId();
        var details = mongo.getCollection("item_details");
        denied(() -> ordinary.adminItemDetail(id), 403, "ordinary user cannot read management detail endpoint");
        denied(() -> ordinary.saveItemDetail(id, "说明", List.of(), "en"), 403, "ordinary detail modification denied");
        denied(() -> ordinary.deleteItemDetail(id), 403, "ordinary detail deletion denied");
        check(admin.adminItemDetail(id).isEmpty() && details.countDocuments(eq("item_id", id)) == 0,
                "missing detail GET returns absence without writing a document");
        denied(() -> admin.adminItemDetail(9999999), 400, "detail read requires SQL product");
        denied(() -> admin.saveItemDetail(9999999, "说明", List.of(), "en"), 400, "detail cannot be created for absent SQL product");
        denied(() -> admin.deleteItemDetail(9999999), 400, "detail deletion requires SQL product");

        var saved = admin.saveItemDetail(id, " 详情原文 ", List.of("https://example.test/ball.jpg"), " zh-CN ");
        var document = details.find(eq("item_id", id)).first();
        check(saved.itemId() == id && saved.description().equals("详情原文"), "inactive product detail is created and normalized");
        check(document != null && document.get("item_id") instanceof Long && document.getList("images", String.class).size() == 1
                && document.get("metadata", Document.class).getString("language").equals("zh-CN"), "HTTP fields map to BSON long array and nested metadata");
        var originalId = document.getObjectId("_id");
        details.updateOne(eq("item_id", id), new Document("$set", new Document("metadata.source", "fixture").append("extra", "keep")));
        admin.saveItemDetail(id, "更新后的说明", List.of(), "en");
        var changed = details.find(eq("item_id", id)).first();
        check(details.countDocuments(eq("item_id", id)) == 1 && changed.getObjectId("_id").equals(originalId),
                "resaving updates one detail and preserves MongoDB identity");
        check(changed.get("metadata", Document.class).getString("source").equals("fixture") && changed.getString("extra").equals("keep"),
                "managed-field update preserves extra metadata and document fields");
        check(changed.getList("images", String.class).isEmpty() && admin.adminItemDetail(id).orElseThrow().language().equals("en"),
                "empty image list clears images and GET reads updated detail");
        check(sqlItem(sql, id).equals(List.of(item.title(), "25.00", "0")), "detail saves preserve MySQL name price and status");
        denied(() -> admin.saveItemDetail(id, " ", List.of(), "en"), 400, "blank description rejected through HTTP");
        denied(() -> admin.saveItemDetail(id, "字".repeat(5001), List.of(), "en"), 400, "overlong description rejected through HTTP");
        denied(() -> admin.saveItemDetail(id, "说明", List.of("/relative"), "en"), 400, "relative URL gives validation error instead of server failure");
        denied(() -> admin.saveItemDetail(id, "说明", List.of(), "zh_CN"), 400, "invalid language rejected through HTTP");
        check(details.find(eq("item_id", id)).first().equals(changed), "invalid detail requests preserve BSON document");

        var response = HttpIntegrationProbe.raw(address, "POST", "/api/auth/login", null,
                "{\"username\":\"integration_admin\",\"password\":\"synthetic-integration-password\"}", "application/json");
        String token = ApiJson.GSON.fromJson(response.body(), ApiDtos.LoginResponse.class).token();
        String path = "/api/admin/items/" + id + "/detail";
        String body = "{\"description\":\"raw\",\"images\":[],\"language\":\"en\"}";
        check(HttpIntegrationProbe.raw(address,"PUT",path,token,body.replace("\"en\"}","\"en\",\"item_id\":1}"),"application/json").statusCode() == 400,
                "detail target cannot be overridden in body");
        for (String invalidImages : List.of("null", "{}", "[1]", "[null]", "\"url\"")) {
            check(HttpIntegrationProbe.raw(address,"PUT",path,token,body.replace("[]",invalidImages),"application/json").statusCode() == 400,
                    "invalid JSON image type rejected: " + invalidImages);
        }
        check(HttpIntegrationProbe.raw(address,"GET",path + "?userId=1",token,null,null).statusCode() == 400, "detail identity query injection denied");
        check(HttpIntegrationProbe.raw(address,"POST",path,token,body,"application/json").statusCode() == 405, "detail route checks HTTP method");
        check(HttpIntegrationProbe.raw(address,"PUT","/api/admin/items/99999999999999999999999999/detail",token,body,"application/json").statusCode() == 400,
                "detail route rejects overflowing target ID");

        details.dropIndex("uq_item_details_item_id");
        try {
            denied(() -> admin.saveItemDetail(id,"must not save",List.of(),"en"),503,"missing unique index blocks detail save");
            denied(() -> admin.deleteItemDetail(id),503,"missing unique index blocks detail delete");
            check(details.find(eq("item_id", id)).first().equals(changed), "index precondition failures preserve detail");
        } finally { details.createIndex(Indexes.ascending("item_id"), new IndexOptions().name("uq_item_details_item_id").unique(true)); }
        details.updateOne(eq("item_id", id), new Document("$set", new Document("metadata", "wrong-type")));
        try {
            var beforeFailure = details.find(eq("item_id", id)).first();
            denied(() -> admin.saveItemDetail(id,"must not partially save",List.of(),"en"),503,"MongoDB path conflict is reported safely");
            check(details.find(eq("item_id", id)).first().equals(beforeFailure), "failed single-document update makes no partial field changes");
        } finally { details.updateOne(eq("item_id",id),new Document("$set",new Document("metadata",changed.get("metadata")))); }

        admin.updateItem(id,item.title(),1,item.price(),1);
        var browse = ordinary.openItem(id);
        check(browse.detail().orElseThrow().description().equals("更新后的说明"), "ordinary browse reads administrator-saved detail");
        ordinary.finishBrowse(browse.browseId());
        var order = ordinary.createOrder(id,item.price());
        ordinary.postComment(id,"详情删除保留评论",4,"");
        long commentCount = mongo.getCollection("comments").countDocuments(eq("item_id",id));
        long actionCount = mongo.getCollection("action_logs").countDocuments(eq("item_id",id));
        check(admin.deleteItemDetail(id), "detail deletion returns deleted status");
        check(admin.adminItemDetail(id).isEmpty() && details.countDocuments(eq("item_id",id)) == 0, "detail deletion is visible in GET and direct MongoDB query");
        check(sqlItem(sql,id).equals(List.of(item.title(),"25.00","1")) && ordinary.orders(OrderFilter.all()).stream().anyMatch(row -> row.orderId() == order.orderId()),
                "detail deletion preserves product and historical order");
        check(mongo.getCollection("comments").countDocuments(eq("item_id",id)) == commentCount && mongo.getCollection("action_logs").countDocuments(eq("item_id",id)) == actionCount,
                "detail deletion preserves comments and browsing history");
        check(!admin.deleteItemDetail(id), "repeated detail deletion accurately reports absence");
        var without = ordinary.openItem(id);
        check(without.detail().isEmpty(), "product remains browsable without detail");
        ordinary.finishBrowse(without.browseId());
        admin.saveItemDetail(id,"重新创建",List.of(),"zh-CN");
        check(details.countDocuments(eq("item_id",id)) == 1 && !details.find(eq("item_id",id)).first().getObjectId("_id").equals(originalId),
                "recreating deleted detail generates new document identity for same product");
        setAccount(sql,actor.userId(),"USER",1);
        try {
            denied(() -> admin.adminItemDetail(id),403,"revoked admin cannot read detail management");
            denied(() -> admin.saveItemDetail(id,"denied",List.of(),"en"),403,"revoked admin cannot save detail");
            denied(() -> admin.deleteItemDetail(id),403,"revoked admin cannot delete detail");
        } finally { setAccount(sql,actor.userId(),"ADMIN",1); }
        setAccount(sql,actor.userId(),"ADMIN",0);
        try { denied(() -> admin.saveItemDetail(id,"denied",List.of(),"en"),400,"disabled administrator cannot save detail"); }
        finally { setAccount(sql,actor.userId(),"ADMIN",1); }
        admin.logout(); ordinary.logout();
        HttpIntegrationProbe.raw(address,"POST","/api/auth/logout",token,"{}","application/json");
        return checks;
    }

    private static List<String> sqlItem(DataSource sql, long id) throws Exception {
        try(var connection=sql.getConnection();var statement=connection.prepareStatement("SELECT title,price,status FROM items WHERE item_id=?")) {
            statement.setLong(1,id);try(var rows=statement.executeQuery()) {
                if(!rows.next()) throw new AssertionError("Product missing");
                return List.of(rows.getString(1),rows.getBigDecimal(2).toPlainString(),rows.getString(3));
            }
        }
    }
    private static void setAccount(DataSource sql,long id,String role,int status) throws Exception {
        try(var connection=sql.getConnection();var statement=connection.prepareStatement("UPDATE users SET role=?,status=? WHERE user_id=?")) {
            statement.setString(1,role);statement.setInt(2,status);statement.setLong(3,id);statement.executeUpdate();
        }
    }
    private static void denied(Runnable action,int status,String label) {
        try { action.run();throw new AssertionError(label); }
        catch(ApiException failure) { check(failure.status()==status,label+" ["+failure.code()+"]"); }
    }
    private static void check(boolean ok,String label) {
        if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);
    }
}
