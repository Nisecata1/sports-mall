package com.sportsmall.server;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** 在HTTP入口记录处理过程，只输出固定的接口名称，避免记录请求里的敏感内容。 */
final class RequestLoggingFilter extends Filter {
    private static final Logger LOG = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final Set<String> METHODS = Set.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS", "HEAD");
    private static final Map<String, String> OPERATIONS = Map.ofEntries(
            Map.entry("/api/health", "检查后端状态"),
            Map.entry("/api/auth/register", "注册商城账号"),
            Map.entry("/api/auth/login", "登录商城"),
            Map.entry("/api/auth/logout", "退出登录"),
            Map.entry("/api/me", "读取本人账号"),
            Map.entry("/api/admin/users", "管理员读取用户列表"),
            Map.entry("/api/admin/categories", "管理员查看或新增分类"),
            Map.entry("/api/admin/categories/{categoryId}", "管理员编辑或删除分类"),
            Map.entry("/api/me/phone", "修改本人电话"),
            Map.entry("/api/me/overview", "读取资料与订单概览"),
            Map.entry("/api/me/profile", "维护本人档案"),
            Map.entry("/api/me/orders/monthly", "查询本人月度订单报表"),
            Map.entry("/api/me/activity", "统计本人浏览活动"),
            Map.entry("/api/items", "读取在售商品"),
            Map.entry("/api/orders", "处理本人订单"),
            Map.entry("/api/orders/{orderId}/cancel", "取消本人订单"),
            Map.entry("/api/items/{itemId}/comments", "处理商品评论"),
            Map.entry("/api/items/{itemId}/comment-statistics", "统计商品评论"),
            Map.entry("/api/statistics/hot-items", "统计热门商品"),
            Map.entry("/api/browses", "打开商品详情"),
            Map.entry("/api/browses/{browseId}/finish", "保存浏览记录")
    );
    private final AtomicLong requestSequence = new AtomicLong();

    @Override
    public String description() {
        return "记录HTTP请求的接口、状态和耗时";
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        long requestId = requestSequence.incrementAndGet();
        long startedAt = System.nanoTime();
        String endpoint = safeEndpoint(exchange.getRequestURI().getPath());
        String method = METHODS.contains(exchange.getRequestMethod()) ? exchange.getRequestMethod() : "OTHER";
        String operation = OPERATIONS.getOrDefault(endpoint, "访问未识别接口");
        LOG.info("收到请求 id={} 操作={} 方法={} 接口={}", requestId, operation, method, endpoint);

        boolean completed = false;
        try {
            chain.doFilter(exchange);
            completed = true;
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            int status = exchange.getResponseCode();
            if (completed && status >= 200 && status < 400) {
                LOG.info("请求完成 id={} 操作={} HTTP={} 耗时={}毫秒", requestId, operation, status, durationMs);
            } else {
                LOG.warn("请求未成功 id={} 操作={} HTTP={} 响应完成={} 耗时={}毫秒",
                        requestId, operation, status, completed, durationMs);
            }
        }
    }

    private static String safeEndpoint(String path) {
        if (OPERATIONS.containsKey(path)) {
            return path;
        }
        if (path.matches("/api/orders/[1-9][0-9]*/cancel")) {
            return "/api/orders/{orderId}/cancel";
        }
        if (path.matches("/api/admin/categories/[1-9][0-9]*")) {
            return "/api/admin/categories/{categoryId}";
        }
        if (path.matches("/api/items/[1-9][0-9]*/(comments|comment-statistics)")) {
            return path.replaceFirst("/[1-9][0-9]*/", "/{itemId}/");
        }
        if (path.matches("/api/browses/[A-Za-z0-9_-]{43}/finish")) {
            return "/api/browses/{browseId}/finish";
        }
        return "[未识别接口]";
    }
}
