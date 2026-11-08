package com.sportsmall.integration;

import com.mongodb.client.MongoDatabase;
import com.sportsmall.api.*;
import com.sportsmall.client.*;
import com.sportsmall.model.*;
import org.bson.*;
import org.bson.types.ObjectId;
import javax.sql.DataSource;
import java.net.URI;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static com.mongodb.client.model.Filters.eq;

/** 三组维护业务统一验证：仅连接父探针已核验的临时实例。 */
final class MaintenanceIntegrationChecks {
    private static int checks;
    private static final String PASSWORD="synthetic-integration-password";
    static int run(URI base,DataSource sql,MongoDatabase mongo) throws Exception {
        checks=0;
        var anonymous=new MallApiClient(base);
        rejected(anonymous::myComments,401,"anonymous cannot read owned comments");
        rejected(anonymous::adminComments,401,"anonymous cannot read moderation comments");
        rejected(()->anonymous.adminOrders(null,OrderFilter.all()),401,"anonymous cannot read managed orders");
        var admin=new MallApiClient(base);admin.login("integration_admin",PASSWORD.toCharArray());
        var customer=new MallApiClient(base);
        var user=customer.register("maintenance_customer","maintenance@example.test","",PASSWORD.toCharArray(),PASSWORD.toCharArray());
        customer.login(user.username(),PASSWORD.toCharArray());
        var other=new MallApiClient(base);other.login("integration_other",PASSWORD.toCharArray());
        var category=admin.createCategory("统一维护演示",null);
        var item=admin.createItem("统一维护商品",category.categoryId(),new BigDecimal("18.80"),1);
        rejected(customer::adminComments,403,"ordinary role cannot read moderation endpoint");
        rejected(()->customer.adminOrders(null,OrderFilter.all()),403,"ordinary role cannot read managed orders");
        rejected(()->customer.updateUser(user.userId(),"ADMIN",1),403,"ordinary user cannot promote self");

        var comment=customer.postComment(item.itemId(),"待修改评论",5,"训练");
        var collection=mongo.getCollection("comments");
        var indexNames=collection.listIndexes().into(new ArrayList<>()).stream().map(d->d.getString("name")).toList();
        check(indexNames.containsAll(List.of("idx_comments_user_time","idx_comments_time")),"comment indexes exist after repeatable incremental migration");
        var original=collection.find(eq("_id",new ObjectId(comment.commentId()))).first();
        collection.updateOne(eq("_id",new ObjectId(comment.commentId())),new Document("$set",new Document("extra",new Document("preserved",true))));
        check(customer.myComments().stream().anyMatch(c->c.commentId().equals(comment.commentId())),"owned list includes authored comment");
        check(other.myComments().stream().noneMatch(c->c.commentId().equals(comment.commentId())),"owned list excludes another author");
        rejected(()->other.updateComment(comment.commentId(),"越权",1,""),400,"foreign author cannot update document");
        check(!other.deleteComment(comment.commentId(),false),"foreign author cannot delete document");
        check(collection.countDocuments(eq("_id",new ObjectId(comment.commentId())))==1,"foreign write attempts leave document present");
        var changed=customer.updateComment(comment.commentId(),"  修改后的内容  ",3,"训练,训练，实用");
        check(changed.content().equals("修改后的内容") && changed.rating()==3 && changed.tags().equals(List.of("训练","实用")),"owned update normalizes all editable fields");
        var saved=collection.find(eq("_id",new ObjectId(comment.commentId()))).first();
        check(saved.get("_id").equals(original.get("_id")) && saved.get("user_id").equals(original.get("user_id"))
                && saved.get("item_id").equals(original.get("item_id")) && saved.get("created_at").equals(original.get("created_at")),"comment immutable fields retain BSON identity and time");
        check(saved.get("updated_at") instanceof Date && saved.get("rating") instanceof Integer && saved.get("user_id") instanceof Long
                && saved.get("extra",Document.class).getBoolean("preserved"),"partial update preserves extras and expected BSON types");
        check(customer.commentStatistics(item.itemId()).averageRating()==3.0,"aggregation observes modified rating");
        for(int score:List.of(0,6))rejected(()->customer.updateComment(comment.commentId(),"有效内容",score,""),400,"invalid score rejected without write");
        rejected(()->customer.updateComment(comment.commentId()," ",3,""),400,"blank comment rejected");
        rejected(()->customer.updateComment(comment.commentId(),"字".repeat(2001),3,""),400,"overlong comment rejected");
        rejected(()->customer.updateComment(comment.commentId(),"有效内容",3,"a,b,c,d,e,f"),400,"excess tags rejected");
        check(collection.find(eq("_id",new ObjectId(comment.commentId()))).first().getString("content").equals("修改后的内容"),"invalid input leaves saved comment unchanged");
        String customerToken=loginToken(base,user.username());
        String adminToken=loginToken(base,"integration_admin");
        check(raw(base,"PUT","/api/me/comments/"+comment.commentId(),customerToken,
                "{\"content\":\"伪造作者\",\"rating\":5,\"tags\":\"\",\"userId\":2}")==400,"comment route rejects injected owner");
        check(raw(base,"PUT","/api/admin/comments/"+comment.commentId(),adminToken,"{}")==405,"moderator cannot overwrite author's content");
        rejected(()->customer.deleteComment(comment.commentId(),true),403,"ordinary user cannot switch to moderation endpoint");
        admin.updateItem(item.itemId(),item.title(),category.categoryId(),item.price(),0);
        check(customer.myComments().stream().anyMatch(c->c.commentId().equals(comment.commentId())),"owned history remains accessible for inactive product");
        check(customer.updateComment(comment.commentId(),"下架后仍可维护",4,"").rating()==4,"owned comment can be edited after product is inactive");
        check(customer.deleteComment(comment.commentId(),false),"owned deletion succeeds");
        check(!customer.deleteComment(comment.commentId(),false),"repeat deletion reports no removal");
        rejected(()->customer.updateComment(comment.commentId(),"不能复活",5,""),400,"deleted comment is not upserted");
        check(collection.countDocuments(eq("_id",new ObjectId(comment.commentId())))==0,"deleted comment stays absent");
        admin.updateItem(item.itemId(),item.title(),category.categoryId(),item.price(),1);
        var moderated=customer.postComment(item.itemId(),"待审核删除",2,"");
        check(admin.adminComments().stream().anyMatch(c->c.commentId().equals(moderated.commentId())),"administrator moderation list includes other author");
        check(admin.deleteComment(moderated.commentId(),true),"administrator can remove selected comment");
        check(customer.commentStatistics(item.itemId()).commentCount()==0,"aggregation reflects deletions");
        check(raw(base,"DELETE","/api/me/comments/not-an-id",customerToken,"{}")==404,"malformed document ID has no matching route");
        check(raw(base,"GET","/api/me/comments?userId=2",customerToken,null)==400,"owned comments reject identity query");

        var first=customer.createOrder(item.itemId(),item.price());
        var completed=admin.processOrder(first.orderId(),1);
        check(completed.status()==1 && completed.userId()==user.userId() && completed.amount().compareTo(item.price())==0,"administrator completes pending order without changing amount or owner");
        check(customer.orders(new OrderFilter(1,null,null)).stream().anyMatch(o->o.orderId()==first.orderId()),"owner observes completed order through existing query");
        var report=customer.monthlyOrders(first.createdAt().toLocalDate().toString().substring(0,7));
        check(report.completedCount()==1 && report.completedAmount().compareTo(item.price())==0,"completed order contributes historical amount to monthly procedure");
        rejected(()->customer.cancelOrder(first.orderId()),400,"completed order cannot be cancelled by owner");
        rejected(()->admin.processOrder(first.orderId(),2),400,"administrator cannot reverse terminal order");
        var second=customer.createOrder(item.itemId(),item.price());
        check(admin.processOrder(second.orderId(),2).status()==2,"administrator cancels another pending order");
        rejected(()->customer.processOrder(second.orderId(),1),403,"ordinary user cannot call order management write");
        rejected(()->admin.processOrder(second.orderId(),0),400,"management cannot reset order to pending");
        var managed=admin.adminOrders(user.userId(),new OrderFilter(1,first.createdAt().toLocalDate(),first.createdAt().toLocalDate()));
        check(managed.size()==1 && managed.get(0).orderId()==first.orderId(),"admin query combines owner status and inclusive date");
        check(admin.adminOrders(user.userId(),new OrderFilter(0,null,null)).isEmpty(),"status filter excludes terminal orders");
        var rollback=customer.createOrder(item.itemId(),item.price());
        try(var connection=sql.getConnection();var statement=connection.prepareStatement(
                "CREATE TRIGGER maintenance_reject_order BEFORE UPDATE ON orders FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='FIXTURE_REJECTION'")) {
            statement.execute();
        }
        try {
            rejected(()->admin.processOrder(rollback.orderId(),1),400,"database rule rejection rolls back admin order mutation");
            check(customer.orders(new OrderFilter(0,null,null)).stream().anyMatch(o->o.orderId()==rollback.orderId()),"rejected status change leaves order pending");
        } finally {
            try(var connection=sql.getConnection();var statement=connection.prepareStatement("DROP TRIGGER maintenance_reject_order")) {statement.execute();}
        }
        check(raw(base,"PUT","/api/admin/orders/"+second.orderId()+"/status",adminToken,"{\"status\":1,\"amount\":1}")==400,"order write rejects amount override");
        var racing=customer.createOrder(item.itemId(),item.price());
        var executor=Executors.newFixedThreadPool(2);
        try {
            var a=executor.submit(()->attempt(()->admin.processOrder(racing.orderId(),1)));
            var b=executor.submit(()->attempt(()->admin.processOrder(racing.orderId(),2)));
            var codes=List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
            check(codes.contains(200) && codes.contains(400),"concurrent order processing has one winner and one rejected transition");
        } finally {executor.shutdownNow();}
        try(var connection=sql.getConnection();var statement=connection.prepareStatement("SELECT user_id,item_id,amount,status,created_at FROM orders WHERE order_id=?")) {
            statement.setLong(1,racing.orderId());
            try(var row=statement.executeQuery()) {
                check(row.next() && row.getLong(1)==racing.userId() && row.getLong(2)==racing.itemId()
                        && row.getBigDecimal(3).compareTo(racing.amount())==0 && row.getInt(4)!=0
                        && row.getTimestamp(5).toLocalDateTime().equals(racing.createdAt()),"SQL readback retains order identity amount and creation time under concurrent change");
            }
        }

        check(admin.updateUser(user.userId(),"USER",0).status()==0,"administrator disables another account");
        rejected(customer::account,400,"disabled account existing session is rejected");
        rejected(()->customer.updateComment(new ObjectId().toHexString(),"有效",5,""),400,"disabled author cannot mutate Mongo");
        rejected(()->customer.login(user.username(),PASSWORD.toCharArray()),400,"disabled account cannot log in anew");
        check(admin.updateUser(user.userId(),"USER",1).status()==1,"administrator reactivates account");
        check(raw(base,"GET","/api/me",customerToken,null)==200,"existing unexpired session rechecks restored current state");
        customer.login(user.username(),PASSWORD.toCharArray());
        check(admin.updateUser(user.userId(),"ADMIN",1).role().equals("ADMIN"),"administrator promotes another account");
        check(!customer.adminUsers().isEmpty(),"current role promotion works without trusting stale session role");
        admin.updateUser(user.userId(),"USER",1);
        rejected(customer::adminUsers,403,"current role demotion revokes existing session management access");
        long adminId=admin.account().userId();
        rejected(()->admin.updateUser(adminId,"USER",1),400,"current administrator cannot demote self");
        rejected(()->admin.updateUser(adminId,"ADMIN",0),400,"current administrator cannot disable self");
        rejected(()->admin.updateUser(user.userId(),"ROOT",1),400,"unknown account role rejected");
        rejected(()->admin.updateUser(user.userId(),"USER",2),400,"invalid account state rejected");
        rejected(()->admin.updateUser(Long.MAX_VALUE,"USER",1),400,"missing account update rejected");
        check(raw(base,"PUT","/api/admin/users/"+user.userId(),adminToken,"{\"role\":\"USER\",\"status\":1,\"password_hash\":\"injected\"}")==400,"account route rejects password hash injection");
        check(raw(base,"PUT","/api/admin/users/"+user.userId()+"?role=ADMIN",adminToken,"{\"role\":\"USER\",\"status\":1}")==400,"account mutation rejects query identity");
        try(var connection=sql.getConnection();var statement=connection.prepareStatement("SELECT role,status,email,password_hash FROM users WHERE user_id=?")) {
            statement.setLong(1,user.userId());
            try(var row=statement.executeQuery()) {
                check(row.next() && row.getString(1).equals("USER") && row.getInt(2)==1 && row.getString(3).equals("maintenance@example.test")
                        && row.getString(4).startsWith("pbkdf2_sha256$"),"SQL account readback preserves email and password hash");
            }
        }
        try(var connection=sql.getConnection();var statement=connection.prepareStatement(
                "CREATE TRIGGER maintenance_reject_user BEFORE UPDATE ON users FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='FIXTURE_REJECTION'")) {
            statement.execute();
        }
        try {
            rejected(()->admin.updateUser(user.userId(),"USER",0),503,"database failure reports unconfirmed account write");
            check(customer.account().status()==1,"failed account mutation rolls back prior state");
        } finally {
            try(var connection=sql.getConnection();var statement=connection.prepareStatement("DROP TRIGGER maintenance_reject_user")) {statement.execute();}
        }
        var left=new MallApiClient(base);var right=new MallApiClient(base);
        var l=left.register("maintenance_admin_left","maint_left@example.test","",PASSWORD.toCharArray(),PASSWORD.toCharArray());
        var r=right.register("maintenance_admin_right","maint_right@example.test","",PASSWORD.toCharArray(),PASSWORD.toCharArray());
        admin.updateUser(l.userId(),"ADMIN",1);admin.updateUser(r.userId(),"ADMIN",1);
        left.login(l.username(),PASSWORD.toCharArray());right.login(r.username(),PASSWORD.toCharArray());
        executor=Executors.newFixedThreadPool(2);
        try {
            var a=executor.submit(()->attempt(()->left.updateUser(r.userId(),"USER",1)));
            var b=executor.submit(()->attempt(()->right.updateUser(l.userId(),"USER",1)));
            var codes=List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
            check(codes.contains(200) && codes.contains(403),"concurrent cross-demotion rechecks actor role under row locks");
        } finally {executor.shutdownNow();left.logout();right.logout();}
        var commentAfter=customer.postComment(item.itemId(),"再次修改删除竞态",5,"");
        executor=Executors.newFixedThreadPool(2);
        try {
            var edit=executor.submit(()->attempt(()->customer.updateComment(commentAfter.commentId(),"并发修改",4,"")));
            var delete=executor.submit(()->customer.deleteComment(commentAfter.commentId(),false));
            int code=edit.get(10,TimeUnit.SECONDS);
            check(delete.get(10,TimeUnit.SECONDS) && (code==200 || code==400)
                    && collection.countDocuments(eq("_id",new ObjectId(commentAfter.commentId())))==0,"concurrent comment edit and deletion cannot resurrect document");
        } finally {executor.shutdownNow();}
        customer.logout();other.logout();admin.logout();
        return checks;
    }
    private static String loginToken(URI base,String name) throws Exception {
        var response=HttpIntegrationProbe.raw(base,"POST","/api/auth/login",null,
                "{\"username\":\""+name+"\",\"password\":\""+PASSWORD+"\"}","application/json");
        if(response.statusCode()!=200)throw new AssertionError("Fixture login failed");
        return ApiJson.GSON.fromJson(response.body(),ApiDtos.LoginResponse.class).token();
    }
    private static int raw(URI base,String method,String path,String token,String body) throws Exception {
        return HttpIntegrationProbe.raw(base,method,path,token,body,body==null ? null : "application/json").statusCode();
    }
    private static int attempt(Runnable action) {try {action.run();return 200;}catch(ApiException failure){return failure.status();}}
    private static void rejected(Runnable action,int status,String label) {check(attempt(action)==status,label);}
    private static void check(boolean ok,String label) {if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
}
