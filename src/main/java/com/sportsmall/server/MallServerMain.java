package com.sportsmall.server;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** 独立后端进程，按需运行，Ctrl+C关闭；不安装系统服务。 */
public final class MallServerMain {
    private MallServerMain() {}
    public static void main(String[] args) throws Exception {
        System.setProperty("sun.net.httpserver.maxReqTime", "15");
        System.setProperty("sun.net.httpserver.maxRspTime", "15");
        int port = Integer.parseInt(System.getenv().getOrDefault("MALL_API_PORT", "18808"));
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("MALL_API_PORT应在1024到65535之间");
        MallServices services = MallServices.open();
        MallApiServer server;
        try { server = new MallApiServer(services, port); }
        catch (Exception failure) { services.close(); throw failure; }
        AtomicBoolean closed = new AtomicBoolean();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (closed.compareAndSet(false, true)) { server.close(); services.close(); }
        }, "mall-backend-shutdown"));
        server.start();
        System.out.println("商城后端已启动：http://127.0.0.1:" + server.port());
        System.out.println("请另行打开商城窗口；本窗口保留运行，结束时按 Ctrl+C。");
        new CountDownLatch(1).await();
    }
}
