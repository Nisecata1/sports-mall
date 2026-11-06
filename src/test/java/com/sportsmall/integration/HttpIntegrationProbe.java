package com.sportsmall.integration;

import com.mongodb.client.*;
import com.sportsmall.server.*;
import com.sportsmall.client.*;
import com.sportsmall.api.*;
import com.sportsmall.model.*;
import com.zaxxer.hikari.HikariDataSource;
import org.bson.Document;
import org.bson.types.ObjectId;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static com.mongodb.client.model.Filters.eq;

/** 显式运行的真实HTTP集成探针；必须传入临时实例的端口及绝对数据目录。 */
public final class HttpIntegrationProbe {
    private static int checks;
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checks++; System.out.println("PASS " + name);
    }
    private static void rejected(Runnable action, int status, String label) {
        try { action.run(); throw new AssertionError("Expected rejection: " + label); }
        catch (ApiException failure) { check(failure.status() == status, label); }
    }
    private static HttpResponse<String> raw(URI base, String method, String path, String token, String body, String contentType) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve(path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (contentType != null) request.header("Content-Type", contentType);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 4 || args[0].equals("18306") || URI.create(args[2]).getPort() == 27017)
            throw new IllegalArgumentException("Only explicitly isolated instances are accepted");
        try (HikariDataSource sql = new HikariDataSource(); MongoClient mongo = MongoClients.create(args[2])) {
            sql.setJdbcUrl("jdbc:mysql://127.0.0.1:" + args[0] + "/sports_mall?sslMode=DISABLED&allowPublicKeyRetrieval=true&characterEncoding=UTF-8");
            sql.setUsername("root"); sql.setPassword(""); sql.setMaximumPoolSize(2); sql.setMinimumIdle(0);
            try (Connection c=sql.getConnection(); var s=c.prepareStatement("SELECT @@datadir"); var r=s.executeQuery()) {
                if (!r.next() || !Files.isSameFile(Path.of(r.getString(1)), Path.of(args[1]))) throw new IllegalStateException("Unexpected SQL data path");
            }
            var storage=mongo.getDatabase("admin").runCommand(new Document("getCmdLineOpts",1)).get("parsed",Document.class).get("storage",Document.class);
            if (!Files.isSameFile(Path.of(storage.getString("dbPath")),Path.of(args[3]))) throw new IllegalStateException("Unexpected Mongo data path");
            check(true,"data directories verified before fixture writes");
            var db=mongo.getDatabase("sports_mall");
            try (MallApiServer server=new MallApiServer(new MallServices(sql,mongo,db),0)) {
                server.start();
                URI address=URI.create("http://127.0.0.1:"+server.port());
                var client=new MallApiClient(address);
                check(raw(address,"GET","/api/health",null,null,null).statusCode()==200,"independent backend health");
                rejected(client::account,401,"unauthenticated account denied");
                rejected(()->client.login("integration_owner","wrong".toCharArray()),400,"incorrect mall password denied");
                check(client.login("integration_owner","synthetic-integration-password".toCharArray()).userId()==1,"HTTP login resolves server identity");
                check(client.account().userId()==1,"own account via HTTP");
                check(client.items().size()==1,"disabled items excluded");
                rejected(()->client.createOrder(1,new BigDecimal("99.00")),400,"price mismatch rejected");
                var order=client.createOrder(1,new BigDecimal("100.00"));
                check(order.userId()==1 && order.amount().equals(new BigDecimal("100.00")),"HTTP order stores price snapshot");
                check(client.orders(OrderFilter.all()).size()==1,"own orders readable");
                var other=new MallApiClient(address);
                other.login("integration_other","synthetic-integration-password".toCharArray());
                check(other.orders(OrderFilter.all()).isEmpty(),"second account cannot read first account orders");
                rejected(()->other.cancelOrder(order.orderId()),400,"cross-user cancellation rejected");
                check(client.cancelOrder(order.orderId()).status()==2,"own pending order cancelled through procedure");
                check(client.orders(new OrderFilter(0,null,null)).isEmpty(),"status filter retains semantics");
                check(client.orders(new OrderFilter(2,order.createdAt().toLocalDate(),order.createdAt().toLocalDate())).size()==1,"inclusive end date via HTTP");
                var comment=client.postComment(1,"HTTP隔离验证评论",5,"训练,篮球");
                check(client.comments(1).get(0).commentId().equals(comment.commentId()),"comment round trip preserves BSON and Chinese text");
                check(client.commentStatistics(1).commentCount()==1,"comment pipeline via backend");
                var browse=client.openItem(1);
                check(browse.item().itemId()==1 && browse.detail()!=null,"browse ticket decodes optional detail");
                rejected(()->other.finishBrowse(browse.browseId()),404,"browse ticket belongs to server session");
                var action=client.finishBrowse(browse.browseId());
                var saved=db.getCollection("action_logs").find(eq("_id",new ObjectId(action.actionId()))).first();
                check(saved!=null && "DESKTOP".equals(saved.get("client_info",Document.class).getString("client_type")),"HTTP browsing preserves desktop provenance");
                rejected(()->client.finishBrowse(browse.browseId()),404,"replayed browse does not duplicate log");
                check(client.hotItems().items().get(0).statistics().viewCount()==1,"hot pipeline via backend");
                check(client.myActivity().summary().viewCount()==1,"own activity pipeline via backend");
                check(client.changePhone("010-12345678").changed(),"phone update via backend");
                check(!client.changePhone("010-12345678").changed(),"unchanged phone reported accurately");
                check(client.overview().cancelledCount()==1 && client.overview().completedAmount().compareTo(BigDecimal.ZERO)==0,"views preserve cancelled order summary");
                var registered=client.register("http_new_user","http@example.test","","new-mall-password".toCharArray(),"new-mall-password".toCharArray());
                var newUser=new MallApiClient(address);
                check(newUser.login(registered.username(),"new-mall-password".toCharArray()).role().equals("USER"),"HTTP registration fixes ordinary-user role");
                newUser.logout();
                var login=raw(address,"POST","/api/auth/login",null,"{\"username\":\"integration_owner\",\"password\":\"synthetic-integration-password\"}","application/json");
                var loginDto=ApiJson.GSON.fromJson(login.body(),ApiDtos.LoginResponse.class);
                String token=loginDto.token();
                check(!login.body().contains("password") && !login.body().contains("hash"),"response exposes no password or hash");
                check(raw(address,"POST","/api/orders",token,"{\"itemId\":1,\"quotedPrice\":100,\"user_id\":2}","application/json").statusCode()==400,"identity injection rejected");
                check(raw(address,"GET","/api/orders?user_id=2",token,null,null).statusCode()==400,"identity query injection rejected");
                check(raw(address,"POST","/api/orders",token,"{bad-json","application/json").statusCode()==400,"malformed JSON rejected");
                check(raw(address,"POST","/api/auth/login",null,"{}","text/plain").statusCode()==415,"non-JSON login rejected");
                check(raw(address,"POST","/api/auth/login",null," ".repeat(32769),"application/json").statusCode()==413,"oversized request rejected");
                check(raw(address,"DELETE","/api/me",token,null,null).statusCode()==405,"unsupported method rejected");
                check(raw(address,"GET","/api/missing",token,null,null).statusCode()==404,"unknown API rejected");
                raw(address,"POST","/api/auth/logout",token,"{}","application/json");
                check(raw(address,"GET","/api/me",token,null,null).statusCode()==401,"server logout invalidates replayed token");
                client.logout(); rejected(client::account,401,"client logout clears identity");
                // 独立客户端JAR的子进程只携带HTTP地址，不设置数据库口令。
                Path desktop=Path.of(System.getProperty("mall.desktop.jar"));
                Path classes=Path.of(HttpIntegrationProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                ProcessBuilder child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),
                        "-Dfile.encoding=UTF-8","-cp",classes+java.io.File.pathSeparator+desktop,
                        "com.sportsmall.integration.HttpClientProcessProbe",address.toString());
                child.environment().remove("MALL_DB_PASSWORD");
                Process process=child.redirectErrorStream(true).start();
                String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                check(process.waitFor()==0 && output.contains("CLIENT_PROCESS_PASSED"),"separate client JVM works without database password or drivers");
                other.logout();
            }
        }
        System.out.println("INTEGRATION_CHECKS_PASSED="+checks);
    }
}
