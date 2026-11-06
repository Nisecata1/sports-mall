package com.sportsmall.integration;

import com.sportsmall.client.MallApiClient;
import java.net.URI;

/** 只用desktop发行包运行，证明前端不需要数据库凭据或驱动。 */
public final class HttpClientProcessProbe {
    public static void main(String[] args) {
        if (System.getenv("MALL_DB_PASSWORD") != null) throw new AssertionError("Database credential must be absent");
        for (String name : new String[]{"com.mysql.cj.jdbc.Driver","com.mongodb.client.MongoClient","com.sportsmall.dao.ItemDao","com.sportsmall.service.OrderService"}) {
            try { Class.forName(name); throw new AssertionError("Frontend contains backend class: "+name); }
            catch (ClassNotFoundException expected) { }
        }
        var client=new MallApiClient(URI.create(args[0]));
        if (client.login("integration_owner","synthetic-integration-password".toCharArray()).userId()!=1
                || client.items().size()!=1 || client.overview().cancelledCount()!=1) throw new AssertionError("Unexpected API result");
        client.logout();
        System.out.println("CLIENT_PROCESS_PASSED");
    }
}
