package com.sportsmall.server;

import com.google.gson.*;
import com.sportsmall.api.*;
import com.sportsmall.exception.*;
import com.sportsmall.model.*;
import com.sun.net.httpserver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 本机HTTP入口：解析请求、鉴权、调用既有Service；不在路由中编写SQL。 */
public final class MallApiServer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(MallApiServer.class);
    private static final int MAX_BODY = 32768;
    private final HttpServer server;
    private final ExecutorService executor;
    private final MallServices services;
    private final SessionStore sessions = new SessionStore();

    public MallApiServer(MallServices services, int port) throws IOException {
        this.services = Objects.requireNonNull(services);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 32);
        executor = new ThreadPoolExecutor(4, 8, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), new ThreadPoolExecutor.CallerRunsPolicy());
        server.setExecutor(executor);
        server.createContext("/", this::handle).getFilters().add(new RequestLoggingFilter());
    }
    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            Object result = route(exchange);
            respond(exchange, 200, result);
        } catch (ApiProblem problem) {
            respond(exchange, problem.status, new ApiDtos.ErrorResponse(problem.code, problem.getMessage()));
        } catch (MallBusinessException problem) {
            int status = problem.code().equals("ADMIN_REQUIRED") ? 403 : 400;
            respond(exchange, status, new ApiDtos.ErrorResponse(problem.code(), problem.getMessage()));
        } catch (MallDataAccessException problem) {
            LOG.warn("接口数据操作未完成，代码={}", problem.code());
            if (problem.code().startsWith("MONTHLY_REPORT_") || problem.code().startsWith("PROFILE_")
                    || problem.code().startsWith("CATEGORY_")) {
                respond(exchange, 503, new ApiDtos.ErrorResponse(problem.code(), problem.getMessage()));
                return;
            }
            // 保留业务操作的不确定性提示，连接层细节只留在后端。
            respond(exchange, 503, new ApiDtos.ErrorResponse(problem.code(),
                    "后端数据操作未完成；写入操作请先查询实际结果，再决定是否重试。"));
        } catch (RuntimeException problem) {
            LOG.error("接口处理失败，类型={}", problem.getClass().getName());
            respond(exchange, 500, new ApiDtos.ErrorResponse("SERVER_ERROR", "后端处理未完成；请先查询实际结果，保留错误代码供排查。"));
        } finally { exchange.close(); }
    }

    private Object route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (path.equals("/api/health")) {
            requireMethod(method, "GET");
            return Map.of("application", "sports-mall", "version", "0.2.5", "status", "UP");
        }
        if (path.equals("/api/auth/login")) {
            requireMethod(method, "POST");
            JsonObject body = body(exchange, "username", "password");
            char[] password = text(body, "password").toCharArray();
            try {
                var user = services.login.login(text(body, "username"), password, exchange.getRemoteAddress().getAddress().getHostAddress());
                return new ApiDtos.LoginResponse(sessions.create(user), user);
            } finally { Arrays.fill(password, '\0'); body.remove("password"); }
        }
        if (path.equals("/api/auth/register")) {
            requireMethod(method, "POST");
            JsonObject body = body(exchange, "username", "email", "phone", "password", "confirmation");
            char[] password = text(body, "password").toCharArray(), confirmation = text(body, "confirmation").toCharArray();
            try { return services.registration.register(text(body, "username"), text(body, "email"), text(body, "phone"), password, confirmation); }
            finally { Arrays.fill(password, '\0'); Arrays.fill(confirmation, '\0'); body.remove("password"); body.remove("confirmation"); }
        }
        if (!path.startsWith("/api/")) throw new ApiProblem(404, "NOT_FOUND", "接口不存在。");
        String token = token(exchange);
        var session = sessions.require(token);
        var user = session.user; // 身份只来自后端会话，从不读取请求中的user_id或role。
        if (path.equals("/api/auth/logout")) {
            requireMethod(method, "POST"); body(exchange);
            sessions.remove(token); return Map.of("loggedOut", true);
        }
        if (path.equals("/api/me")) {
            requireMethod(method, "GET"); return services.accounts.viewMyAccount(user);
        }
        if (path.equals("/api/admin/users")) {
            requireMethod(method, "GET");
            query(exchange); // 不接受role或user_id等自报身份参数。
            return services.adminUsers.listUsers(user);
        }
        if (path.equals("/api/admin/categories")) {
            query(exchange);
            if (method.equals("GET")) return services.categories.treeFor(user);
            requireMethod(method, "POST");
            var body = body(exchange, "name", "parentId");
            var parent = body.get("parentId");
            Long parentId = parent == null || parent.isJsonNull() ? null : positiveId(body, "parentId");
            return services.categories.createFor(user, text(body, "name"), parentId);
        }
        if (path.equals("/api/me/phone")) {
            requireMethod(method, "PATCH");
            var changed = services.accounts.changeMyPhone(user, text(body(exchange, "phone"), "phone"));
            return new ApiDtos.PhoneChange(changed.account(), changed.changed());
        }
        if (path.equals("/api/me/overview")) {
            requireMethod(method, "GET"); return services.overviews.viewMine(user);
        }
        if (path.equals("/api/me/profile")) {
            if (method.equals("GET")) return services.profiles.viewMine(user);
            if (method.equals("PUT")) {
                var body = body(exchange, "realName", "idCard", "address", "notes");
                return services.profiles.saveMine(user, text(body, "realName"), text(body, "idCard"),
                        text(body, "address"), text(body, "notes"));
            }
            requireMethod(method, "DELETE");
            body(exchange);
            return new ApiDtos.ProfileDeletion(services.profiles.deleteMine(user));
        }
        if (path.equals("/api/me/orders/monthly")) {
            requireMethod(method, "GET");
            return services.monthlyOrders.viewMine(user, query(exchange, "month").get("month"));
        }
        if (path.equals("/api/items")) {
            requireMethod(method, "GET"); return services.browsing.availableFor(user);
        }
        if (path.equals("/api/orders")) {
            if (method.equals("POST")) {
                var body = body(exchange, "itemId", "quotedPrice");
                return services.orders.createFor(user, positiveId(body, "itemId"), decimal(body, "quotedPrice"));
            }
            requireMethod(method, "GET");
            var query = query(exchange, "status", "from", "through");
            try {
                var filter = new OrderFilter(query.containsKey("status") ? Integer.valueOf(query.get("status")) : null,
                        query.containsKey("from") ? LocalDate.parse(query.get("from")) : null,
                        query.containsKey("through") ? LocalDate.parse(query.get("through")) : null);
                return services.orders.findMine(user, filter);
            } catch (NumberFormatException | DateTimeException failure) { throw invalid("订单状态或日期格式不正确。"); }
        }
        if (path.matches("/api/orders/[1-9][0-9]*/cancel")) {
            requireMethod(method, "POST"); body(exchange);
            return services.orders.cancelMine(user, idSegment(path, 3));
        }
        if (path.matches("/api/items/[1-9][0-9]*/comments")) {
            long itemId = idSegment(path, 3);
            if (method.equals("GET")) return services.comments.latestFor(user, itemId);
            requireMethod(method, "POST");
            var body = body(exchange, "content", "rating", "tags");
            int rating;
            try { rating = decimal(body, "rating").intValueExact(); }
            catch (ArithmeticException failure) { throw invalid("评分应是整数。"); }
            return services.comments.post(user, itemId, text(body, "content"), rating, text(body, "tags"));
        }
        if (path.matches("/api/items/[1-9][0-9]*/comment-statistics")) {
            requireMethod(method, "GET"); return services.statistics.commentStatsFor(user, idSegment(path, 3));
        }
        if (path.equals("/api/statistics/hot-items")) {
            requireMethod(method, "GET");
            var report = services.statistics.hotItemsFor(user);
            return new ApiDtos.HotReport(report.fromInclusive(), report.throughInclusive(), report.items().stream()
                    .map(row -> new ApiDtos.RankedItem(row.statistics(), row.item())).toList());
        }
        if (path.equals("/api/me/activity")) {
            requireMethod(method, "GET");
            var report = services.statistics.myActivityFor(user);
            return new ApiDtos.UserActivityReport(report.fromInclusive(), report.throughInclusive(), report.summary());
        }
        if (path.equals("/api/browses")) {
            requireMethod(method, "POST");
            var opened = services.browsing.openFor(user, positiveId(body(exchange, "itemId"), "itemId"));
            return new ApiDtos.BrowseTicket(session.open(opened), opened.item(), opened.detail());
        }
        if (path.matches("/api/browses/[A-Za-z0-9_-]{43}/finish")) {
            requireMethod(method, "POST"); body(exchange);
            var pending = session.take(path.split("/")[3]);
            // 先消费凭证，重复请求不会再写一条；持久化失败时要求先查询实际记录。
            return services.browsing.finishFor(user, pending.opened(), pending.seconds());
        }
        throw new ApiProblem(404, "NOT_FOUND", "接口不存在。");
    }

    private static String token(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        return header != null && header.matches("Bearer [A-Za-z0-9_-]{43}") ? header.substring(7) : null;
    }
    private static void requireMethod(String actual, String required) {
        if (!actual.equals(required)) throw new ApiProblem(405, "METHOD_NOT_ALLOWED", "此接口不支持该请求方式。");
    }
    private static JsonObject body(HttpExchange exchange, String... allowed) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.split(";")[0].strip().equalsIgnoreCase("application/json"))
            throw new ApiProblem(415, "JSON_REQUIRED", "请求应使用JSON格式。");
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (bytes.length > MAX_BODY) throw new ApiProblem(413, "BODY_TOO_LARGE", "请求内容过长。");
        try {
            JsonElement parsed = ApiJson.GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonElement.class);
            if (parsed == null || !parsed.isJsonObject()) throw invalid("请求应是JSON对象。");
            JsonObject object = parsed.getAsJsonObject();
            Set<String> fields = Set.of(allowed);
            if (!fields.containsAll(object.keySet())) throw invalid("请求含有不支持的字段。");
            return object;
        } catch (JsonParseException | IllegalStateException failure) { throw invalid("JSON内容或字段格式不正确。"); }
    }
    private static String text(JsonObject object, String key) {
        var element = object.get(key);
        if (element == null || element.isJsonNull()) return "";
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw invalid("文字字段格式不正确。");
        return element.getAsString();
    }
    private static java.math.BigDecimal decimal(JsonObject object, String key) {
        var element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) throw invalid("缺少有效的数字字段。");
        try { return element.getAsBigDecimal(); }
        catch (NumberFormatException failure) { throw invalid("数字字段格式不正确。"); }
    }
    private static long positiveId(JsonObject object, String key) {
        try {
            long id = decimal(object, key).longValueExact();
            if (id < 1) throw invalid("编号应为正整数。");
            return id;
        } catch (ArithmeticException failure) { throw invalid("编号应为有效整数。"); }
    }
    private static long idSegment(String path, int segment) {
        try { return Long.parseLong(path.split("/")[segment]); }
        catch (NumberFormatException failure) { throw invalid("编号超出范围。"); }
    }
    private static Map<String,String> query(HttpExchange exchange, String... allowed) {
        String raw = exchange.getRequestURI().getRawQuery();
        Map<String,String> values = new HashMap<>();
        if (raw == null || raw.isEmpty()) return values;
        try {
            for (String pair : raw.split("&")) {
                String[] parts = pair.split("=", 2);
                String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                if (parts.length != 2 || !Set.of(allowed).contains(key) || values.containsKey(key))
                    throw invalid("查询条件不正确。");
                values.put(key, URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
        } catch (IllegalArgumentException failure) { throw invalid("查询条件编码不正确。"); }
        return values;
    }
    private static ApiProblem invalid(String message) { return new ApiProblem(400, "INVALID_REQUEST", message); }
    private static void respond(HttpExchange exchange, int status, Object data) throws IOException {
        byte[] bytes = ApiJson.GSON.toJson(data).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
    @Override public void close() {
        server.stop(1); executor.shutdown();
        try { if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException interrupted) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
