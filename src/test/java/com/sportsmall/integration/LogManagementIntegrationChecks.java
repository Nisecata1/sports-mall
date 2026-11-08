package com.sportsmall.integration;

import com.mongodb.client.MongoDatabase;
import com.sportsmall.api.*;
import com.sportsmall.client.*;
import com.sportsmall.model.*;
import org.bson.Document;
import javax.sql.DataSource;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.mongodb.client.model.Filters.*;

/** 真实JDBC批处理、跨库部分失败及幂等重试；只接收已核验的临时数据库。 */
final class LogManagementIntegrationChecks {
    private static int checks;
    private static final String PASSWORD = "synthetic-integration-password";
    static int run(URI base, DataSource sql, MongoDatabase mongo) throws Exception {
        checks = 0;
        var anonymous = new MallApiClient(base);
        rejected(() -> anonymous.systemLogs(SystemLogFilter.all()), 401, "anonymous cannot query logs");
        rejected(() -> anonymous.importLogs("{}"), 401, "anonymous cannot stage logs");
        var ordinary = new MallApiClient(base); ordinary.login("integration_owner", PASSWORD.toCharArray());
        rejected(() -> ordinary.systemLogs(SystemLogFilter.all()), 403, "ordinary role cannot query audit");
        rejected(() -> ordinary.importLogs("{}"), 403, "ordinary role cannot import logs");
        var admin = new MallApiClient(base); var actor = admin.login("integration_admin", PASSWORD.toCharArray());
        var collection = mongo.getCollection("system_logs");
        var day = LocalDate.of(2026,11,8);
        check(admin.systemLogs(new SystemLogFilter(actor.userId(), "LOGIN", "INFO", null, null)).stream()
                .anyMatch(log -> log.userId() == actor.userId()), "administrator queries BSON long login references");
        long before = scalar(sql,"SELECT COUNT(*) FROM log_import_rows");
        rejected(() -> admin.importLogs(row(actor.userId(), "valid-before-bad", "INFO", "2026-11-08T01:00:00Z") + "\n{bad}"),
                400, "bad final line rejects whole file");
        check(scalar(sql,"SELECT COUNT(*) FROM log_import_rows") == before, "invalid file performs no staging writes");
        var invalidIdentity = HttpIntegrationProbe.raw(base,"POST","/api/admin/log-imports",token(base),
                "{\"content\":\"{}\",\"user_id\":1}","application/json");
        check(invalidIdentity.statusCode() == 400,"import actor cannot be self-reported in body");

        String file = row(actor.userId(), "边界外-before", "INFO", "2026-11-07T15:59:59Z") + "\n"
                + row(actor.userId(), "边界内-start", "ERROR", "2026-11-07T16:00:00Z") + "\n"
                + row(actor.userId(), "边界外-after", "INFO", "2026-11-08T16:00:00Z");
        var result = admin.importLogs(file);
        check(result.total()==3 && result.synced()==3 && result.pending()==0,"JDBC staging and Mongo synchronization complete");
        check(scalar(sql,"SELECT COUNT(*) FROM log_import_rows WHERE sync_status='SYNCED'")>=3,"MySQL confirms synchronized rows");
        var bson = collection.find(eq("_id","import:"+result.batchId()+":2")).first();
        check(bson!=null && bson.get("user_id") instanceof Long && bson.get("timestamp") instanceof Date,
                "import writes BSON long and date values");
        var detail = bson.get("action_detail",Document.class);
        check("IMPORT".equals(bson.getString("log_type")) && "COURSE_IMPORT".equals(detail.getString("source"))
                && detail.getLong("imported_by")==actor.userId(),"import provenance cannot impersonate a real login or business action");
        var same = admin.importLogs("\n"+file+"\r\n");
        check(same.batchId().equals(result.batchId()) && same.total()==3
                && collection.countDocuments(eq("action_detail.batch_id",result.batchId()))==3,"repeat import reuses canonical batch without duplicates");
        var found = admin.systemLogs(new SystemLogFilter(actor.userId(),"IMPORT","ERROR",day,day));
        check(found.stream().anyMatch(log -> log.message().equals("边界内-start")) && found.stream().allMatch(log -> log.level().equals("ERROR")),
                "combined type level user and Beijing date filters return intended event");
        var dayLogs = admin.systemLogs(new SystemLogFilter(actor.userId(),"IMPORT",null,day,day));
        check(dayLogs.stream().noneMatch(log -> log.message().startsWith("边界外")),"inclusive Beijing end date excludes next midnight");
        long logsBeforeRead = collection.countDocuments(); admin.systemLogs(SystemLogFilter.all());
        check(collection.countDocuments()==logsBeforeRead,"read-only log query does not recursively generate audit");

        // 同一事务中的第2行被数据库拒绝时，第1行也必须回滚。
        execute(sql,"CREATE TRIGGER reject_log_stage BEFORE INSERT ON log_import_rows FOR EACH ROW BEGIN IF NEW.row_no=2 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic rejection'; END IF; END");
        long stagedBefore = scalar(sql,"SELECT COUNT(*) FROM log_import_rows");
        try {
            rejected(() -> admin.importLogs(file.replace("边界", "rollback")),503,"JDBC batch failure is reported");
            check(scalar(sql,"SELECT COUNT(*) FROM log_import_rows")==stagedBefore,"failed JDBC batch rolls back every staged row");
        } finally { execute(sql,"DROP TRIGGER reject_log_stage"); }

        // 无序Mongo批写允许部分写入；状态保留PENDING，同一ID重试补齐而不复制。
        validator(mongo,new Document("message",new Document("$ne","REJECT_IMPORT")));
        String partial = row(actor.userId(),"partial-left","INFO","2026-11-08T02:00:00Z") + "\n"
                + row(actor.userId(),"REJECT_IMPORT","WARN","2026-11-08T02:01:00Z") + "\n"
                + row(actor.userId(),"partial-right","INFO","2026-11-08T02:02:00Z");
        LogImportResult partialResult;
        try {
            partialResult = admin.importLogs(partial);
            check(partialResult.pending()==3 && partialResult.synced()==0,"partial Mongo failure does not claim whole batch succeeded");
            check(collection.countDocuments(eq("action_detail.batch_id",partialResult.batchId()))==2,"synthetic failure actually produced a partial cross-database state");
        } finally { validator(mongo,new Document()); }
        var recovered = admin.importLogs(partial);
        check(recovered.pending()==0 && recovered.synced()==3
                && collection.countDocuments(eq("action_detail.batch_id",recovered.batchId()))==3,"retry repairs partially written Mongo batch without duplicating successful rows");

        execute(sql,"CREATE TRIGGER reject_log_mark BEFORE UPDATE ON log_import_rows FOR EACH ROW BEGIN IF NEW.sync_status='SYNCED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic mark rejection'; END IF; END");
        String unmarked = row(actor.userId(),"mark-retry","INFO","2026-11-08T03:00:00Z");
        try {
            var pending = admin.importLogs(unmarked);
            check(pending.pending()==1 && collection.countDocuments(eq("action_detail.batch_id",pending.batchId()))==1,
                    "Mongo success with SQL mark failure remains explicitly pending");
        } finally { execute(sql,"DROP TRIGGER reject_log_mark"); }
        var marked = admin.importLogs(unmarked);
        check(marked.pending()==0 && collection.countDocuments(eq("action_detail.batch_id",marked.batchId()))==1,
                "retry after SQL mark failure acknowledges existing Mongo row once");

        StringBuilder large = new StringBuilder();
        for(int n=0;n<52;n++) large.append(row(actor.userId(),"batch-page-"+n,"INFO","2026-11-08T04:00:00Z")).append('\n');
        var many = admin.importLogs(large.toString());
        check(many.synced()==52 && many.pending()==0,"PreparedStatement batch crosses 50-row execution boundary");
        var pool = Executors.newFixedThreadPool(2);
        try {
            String concurrentFile = row(actor.userId(),"concurrent-import","INFO","2026-11-08T05:00:00Z");
            var left = pool.submit(() -> admin.importLogs(concurrentFile));
            var right = pool.submit(() -> admin.importLogs(concurrentFile));
            var a = left.get(15,TimeUnit.SECONDS); var b = right.get(15,TimeUnit.SECONDS);
            // 并发upsert若出现重复键，其中一个请求可暂报PENDING；再次重试必须收敛。
            var settled = admin.importLogs(concurrentFile);
            check(a.batchId().equals(b.batchId()) && settled.pending()==0
                    && collection.countDocuments(eq("action_detail.batch_id",a.batchId()))==1,"concurrent identical imports converge to one log");
        } finally { pool.shutdownNow(); }

        admin.changePhone("LOG-AUDIT-OK");
        check(collection.countDocuments(and(eq("user_id",actor.userId()),eq("log_type","OPERATION"),
                eq("action_detail.operation","PATCH /api/me/phone")))>0,"successful backend write records actor and route");
        validator(mongo,new Document("log_type",new Document("$ne","OPERATION")));
        try {
            check(admin.changePhone("LOG-AUDIT-FAIL").changed() && admin.account().phone().equals("LOG-AUDIT-FAIL"),
                    "audit failure does not report committed MySQL update as rolled back");
        } finally { validator(mongo,new Document()); }
        var revoked = new MallApiClient(base);
        var revokedUser = admin.register("log_revoked","log_revoked@example.test","",PASSWORD.toCharArray(),PASSWORD.toCharArray());
        admin.updateUser(revokedUser.userId(),"ADMIN",1); revoked.login(revokedUser.username(),PASSWORD.toCharArray());
        admin.updateUser(revokedUser.userId(),"USER",1);
        rejected(() -> revoked.importLogs(file),403,"role revoked after login cannot import");
        rejected(() -> revoked.systemLogs(SystemLogFilter.all()),403,"role revoked after login cannot view audit");
        check(collection.listIndexes().into(new ArrayList<>()).stream().anyMatch(index -> "idx_system_type_level_time".equals(index.getString("name"))),
                "log filter index migration is installed");
        check(admin.systemLogs(SystemLogFilter.all()).size()<=200,"log list keeps documented 200-row limit");
        ordinary.logout(); revoked.logout(); admin.logout();
        return checks;
    }
    private static String row(long user,String message,String level,String timestamp) {
        return ApiJson.GSON.toJson(Map.of("user_id",user,"timestamp",timestamp,"level",level,"message",message,"operation","COURSE_DEMO"));
    }
    private static void validator(MongoDatabase mongo,Document validator) {
        mongo.runCommand(new Document("collMod","system_logs").append("validator",validator).append("validationLevel","strict").append("validationAction","error"));
    }
    private static void execute(DataSource sql,String text) throws Exception {
        try(var connection=sql.getConnection();var statement=connection.prepareStatement(text)) {statement.execute();}
    }
    private static long scalar(DataSource sql,String text) throws Exception {
        try(var connection=sql.getConnection();var statement=connection.prepareStatement(text);var rows=statement.executeQuery()) {rows.next();return rows.getLong(1);}
    }
    private static String token(URI base) throws Exception {
        var response=HttpIntegrationProbe.raw(base,"POST","/api/auth/login",null,
                ApiJson.GSON.toJson(Map.of("username","integration_admin","password",PASSWORD)),"application/json");
        return ApiJson.GSON.fromJson(response.body(),ApiDtos.LoginResponse.class).token();
    }
    private static void rejected(Runnable action,int status,String label) {
        try {action.run();throw new AssertionError("Expected rejection: "+label);}
        catch(ApiException failure) {check(failure.status()==status,label);}
    }
    private static void check(boolean condition,String label) {
        if(!condition)throw new AssertionError(label);
        checks++;System.out.println("PASS "+label);
    }
}
