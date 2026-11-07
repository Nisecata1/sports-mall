package com.sportsmall.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** 独立后端进程，按需运行，Ctrl+C关闭；不安装系统服务。 */
public final class MallServerMain {
    private static final Logger LOG = LoggerFactory.getLogger(MallServerMain.class);

    private MallServerMain() {}
    public static void main(String[] args) throws Exception {
        System.setProperty("sun.net.httpserver.maxReqTime", "15");
        System.setProperty("sun.net.httpserver.maxRspTime", "15");
        int port = Integer.parseInt(System.getenv().getOrDefault("MALL_API_PORT", "18808"));
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("MALL_API_PORT应在1024到65535之间");
        LOG.info("正在启动商城后端，准备连接 MySQL 和 MongoDB");
        MallServices services = MallServices.open();
        LOG.info("数据库连接已就绪，开始启动 HTTP 接口");
        MallApiServer server;
        try { server = new MallApiServer(services, port); }
        catch (Exception failure) { services.close(); throw failure; }
        AtomicBoolean closed = new AtomicBoolean();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (closed.compareAndSet(false, true)) {
                LOG.info("正在停止商城后端并释放数据库连接");
                try {
                    server.close();
                } finally {
                    services.close();
                }
                LOG.info("商城后端已停止");
            }
        }, "mall-backend-shutdown"));
        server.start();
        LOG.info("商城后端已启动，地址 http://127.0.0.1:{}", server.port());
        LOG.info("等待商城窗口请求，使用 Ctrl+C 结束后端");
        new CountDownLatch(1).await();
    }
}
