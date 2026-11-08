package com.sportsmall.client;

import com.google.gson.reflect.TypeToken;
import com.sportsmall.api.*;
import com.sportsmall.model.*;
import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** 窗口只认识HTTP接口，不导入DAO、Service、连接池或数据库配置。 */
public final class MallApiClient {
    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private volatile String token;
    public MallApiClient(URI base) {
        if (!"http".equals(base.getScheme()) || !Set.of("127.0.0.1", "localhost").contains(base.getHost())
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !(base.getPath().isEmpty() || base.getPath().equals("/"))) {
            throw new IllegalArgumentException("课程客户端地址应为本机HTTP地址，例如http://127.0.0.1:18808");
        }
        this.base = base;
    }
    public static MallApiClient load() {
        return new MallApiClient(URI.create(System.getenv().getOrDefault("MALL_API_URL", "http://127.0.0.1:18808")));
    }
    public String address() { return base.toString(); }
    public AuthenticatedUser login(String username, char[] password) {
        token = null;
        ApiDtos.LoginResponse result = request("POST", "/api/auth/login", Map.of("username", username, "password", new String(password)), ApiDtos.LoginResponse.class);
        token = result.token();
        return result.user();
    }
    public RegisteredUser register(String username, String email, String phone, char[] password, char[] confirmation) {
        return request("POST", "/api/auth/register", Map.of("username", username, "email", email, "phone", phone,
                "password", new String(password), "confirmation", new String(confirmation)), RegisteredUser.class);
    }
    public void logout() {
        try { if (token != null) request("POST", "/api/auth/logout", Map.of(), Object.class); }
        finally { token = null; }
    }
    public List<ItemView> items() { return request("GET", "/api/items", null, new TypeToken<List<ItemView>>(){}.getType()); }
    public ItemPage searchItems(ItemFilter filter) {
        Map<String,Object> fields = new LinkedHashMap<>();
        fields.put("keyword",filter.keyword());fields.put("categoryId",filter.categoryId());
        fields.put("minPrice",filter.minPrice());fields.put("maxPrice",filter.maxPrice());
        fields.put("sort",filter.sort());fields.put("page",filter.page());fields.put("pageSize",filter.pageSize());
        String query = fields.entrySet().stream().filter(entry -> entry.getValue() != null)
                .map(entry -> entry.getKey() + "=" + java.net.URLEncoder.encode(entry.getValue().toString(),java.nio.charset.StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&"));
        return request("GET","/api/items/search?" + query,null,ItemPage.class);
    }
    public List<CategoryView> catalogCategories() {
        return request("GET","/api/categories",null,new TypeToken<List<CategoryView>>(){}.getType());
    }
    public UserAccount account() { return request("GET", "/api/me", null, UserAccount.class); }
    public List<AdminUserSummary> adminUsers() {
        return request("GET", "/api/admin/users", null, new TypeToken<List<AdminUserSummary>>(){}.getType());
    }
    public List<SystemLogView> systemLogs(SystemLogFilter filter) {
        List<String> fields = new ArrayList<>();
        if (filter.userId() != null) fields.add("userId=" + filter.userId());
        if (filter.type() != null) fields.add("type=" + filter.type());
        if (filter.level() != null) fields.add("level=" + filter.level());
        if (filter.from() != null) fields.add("from=" + filter.from());
        if (filter.through() != null) fields.add("through=" + filter.through());
        return request("GET", "/api/admin/logs" + (fields.isEmpty() ? "" : "?" + String.join("&", fields)),
                null, new TypeToken<List<SystemLogView>>(){}.getType());
    }
    public LogImportResult importLogs(String content) {
        return request("POST", "/api/admin/log-imports", Map.of("content", content), LogImportResult.class);
    }
    public List<AdminItemView> adminItems() {
        return request("GET", "/api/admin/items", null, new TypeToken<List<AdminItemView>>(){}.getType());
    }
    public AdminUserSummary updateUser(long id,String role,int status) {
        return request("PUT","/api/admin/users/"+id,Map.of("role",role,"status",status),AdminUserSummary.class);
    }
    public List<OrderView> adminOrders(Long userId,OrderFilter filter) {
        List<String> fields=new ArrayList<>();
        if(userId!=null)fields.add("userId="+userId);
        if(filter.status()!=null)fields.add("status="+filter.status());
        if(filter.startDate()!=null)fields.add("from="+filter.startDate());
        if(filter.endDate()!=null)fields.add("through="+filter.endDate());
        return request("GET","/api/admin/orders"+(fields.isEmpty() ? "" : "?"+String.join("&",fields)),null,
                new TypeToken<List<OrderView>>(){}.getType());
    }
    public OrderView processOrder(long id,int status) {
        return request("PUT","/api/admin/orders/"+id+"/status",Map.of("status",status),OrderView.class);
    }
    public List<CommentView> myComments() {
        return request("GET","/api/me/comments",null,new TypeToken<List<CommentView>>(){}.getType());
    }
    public List<CommentView> adminComments() {
        return request("GET","/api/admin/comments",null,new TypeToken<List<CommentView>>(){}.getType());
    }
    public CommentView updateComment(String id,String content,int rating,String tags) {
        return request("PUT","/api/me/comments/"+id,Map.of("content",content,"rating",rating,"tags",tags),CommentView.class);
    }
    public boolean deleteComment(String id,boolean administrator) {
        com.google.gson.JsonObject result=request("DELETE",(administrator ? "/api/admin/comments/" : "/api/me/comments/")+id,
                Map.of(),com.google.gson.JsonObject.class);
        return result.get("deleted").getAsBoolean();
    }
    public AdminItemView createItem(String title, long categoryId, BigDecimal price, int status) {
        return request("POST", "/api/admin/items", new ItemFields(title, categoryId, price, status), AdminItemView.class);
    }
    public AdminItemView updateItem(long id, String title, long categoryId, BigDecimal price, int status) {
        return request("PUT", "/api/admin/items/" + id, new ItemFields(title, categoryId, price, status), AdminItemView.class);
    }
    public List<CategoryView> categories() {
        return request("GET", "/api/admin/categories", null, new TypeToken<List<CategoryView>>(){}.getType());
    }
    public Optional<ItemDetail> adminItemDetail(long itemId) {
        ApiDtos.ItemDetailResponse result = request("GET", "/api/admin/items/" + itemId + "/detail", null, ApiDtos.ItemDetailResponse.class);
        return result.detail();
    }
    public ItemDetail saveItemDetail(long itemId, String description, List<String> images, String language) {
        return request("PUT", "/api/admin/items/" + itemId + "/detail",
                new ApiDtos.ItemDetailFields(description, images, language), ItemDetail.class);
    }
    public boolean deleteItemDetail(long itemId) {
        ApiDtos.ItemDetailDeletion result = request("DELETE", "/api/admin/items/" + itemId + "/detail", Map.of(), ApiDtos.ItemDetailDeletion.class);
        return result.deleted();
    }
    public CategoryView createCategory(String name, Long parentId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("parentId", parentId);
        return request("POST", "/api/admin/categories", body, CategoryView.class);
    }
    public CategoryView updateCategory(long id, String name, Long parentId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("parentId", parentId);
        return request("PUT", "/api/admin/categories/" + id, body, CategoryView.class);
    }
    public boolean deleteCategory(long id) {
        ApiDtos.CategoryDeletion result = request("DELETE", "/api/admin/categories/" + id, Map.of(), ApiDtos.CategoryDeletion.class);
        return result.deleted();
    }
    public AccountOverview overview() { return request("GET", "/api/me/overview", null, AccountOverview.class); }
    public UserProfile profile() {
        return request("GET", "/api/me/profile", null, UserProfile.class);
    }
    public UserProfile saveProfile(String realName, String idCard, String address, String notes) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("realName", realName);
        body.put("idCard", idCard);
        body.put("address", address);
        body.put("notes", notes);
        return request("PUT", "/api/me/profile", body, UserProfile.class);
    }
    public boolean deleteProfile() {
        ApiDtos.ProfileDeletion result = request("DELETE", "/api/me/profile", Map.of(), ApiDtos.ProfileDeletion.class);
        return result.deleted();
    }
    public MonthlyOrderReport monthlyOrders(String month) {
        String encodedMonth = java.net.URLEncoder.encode(month, java.nio.charset.StandardCharsets.UTF_8);
        return request("GET", "/api/me/orders/monthly?month=" + encodedMonth, null, MonthlyOrderReport.class);
    }
    public ApiDtos.PhoneChange changePhone(String phone) { return request("PATCH", "/api/me/phone", Map.of("phone", phone), ApiDtos.PhoneChange.class); }
    public OrderView createOrder(long itemId, BigDecimal price) {
        return request("POST", "/api/orders", Map.of("itemId", itemId, "quotedPrice", price), OrderView.class);
    }
    public List<OrderView> orders(OrderFilter filter) {
        List<String> conditions = new ArrayList<>();
        if (filter.status() != null) conditions.add("status=" + filter.status());
        if (filter.startDate() != null) conditions.add("from=" + filter.startDate());
        if (filter.endDate() != null) conditions.add("through=" + filter.endDate());
        return request("GET", "/api/orders" + (conditions.isEmpty() ? "" : "?" + String.join("&", conditions)),
                null, new TypeToken<List<OrderView>>(){}.getType());
    }
    public OrderView cancelOrder(long id) { return request("POST", "/api/orders/" + id + "/cancel", Map.of(), OrderView.class); }
    public List<CommentView> comments(long itemId) {
        return request("GET", "/api/items/" + itemId + "/comments", null, new TypeToken<List<CommentView>>(){}.getType());
    }
    public CommentView postComment(long itemId, String content, int rating, String tags) {
        return request("POST", "/api/items/" + itemId + "/comments", Map.of("content", content, "rating", rating, "tags", tags), CommentView.class);
    }
    public CommentStatistics commentStatistics(long itemId) {
        return request("GET", "/api/items/" + itemId + "/comment-statistics", null, CommentStatistics.class);
    }
    public ApiDtos.HotReport hotItems() { return request("GET", "/api/statistics/hot-items", null, ApiDtos.HotReport.class); }
    public ApiDtos.UserActivityReport myActivity() { return request("GET", "/api/me/activity", null, ApiDtos.UserActivityReport.class); }
    public ApiDtos.BrowseTicket openItem(long itemId) { return request("POST", "/api/browses", Map.of("itemId", itemId), ApiDtos.BrowseTicket.class); }
    public ActionLogView finishBrowse(String browseId) { return request("POST", "/api/browses/" + browseId + "/finish", Map.of(), ActionLogView.class); }

    private <T> T request(String method, String path, Object body, Type type) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json");
        String currentToken = token;
        if (currentToken != null) builder.header("Authorization", "Bearer " + currentToken);
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json; charset=utf-8")
                .method(method, HttpRequest.BodyPublishers.ofString(ApiJson.GSON.toJson(body), java.nio.charset.StandardCharsets.UTF_8));
        try {
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                ApiDtos.ErrorResponse error;
                try { error = ApiJson.GSON.fromJson(response.body(), ApiDtos.ErrorResponse.class); }
                catch (RuntimeException malformed) { error = null; }
                if (response.statusCode() == 401) token = null;
                throw new ApiException(response.statusCode(), error == null || error.code() == null ? "HTTP_ERROR" : error.code(),
                        error == null || error.message() == null ? "后端未完成请求，请检查后端运行状态。" : error.message());
            }
            T data = ApiJson.GSON.fromJson(response.body(), type);
            if (data == null) throw new ApiException(502, "INVALID_RESPONSE", "后端返回了空结果，请检查程序版本是否一致。");
            return data;
        } catch (IOException failure) {
            throw new ApiException(0, "API_CONNECTION_FAILED", "无法确认后端处理结果。请确认商城后端已启动；写入操作先查询结果，再决定是否重试。");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ApiException(0, "REQUEST_INTERRUPTED", "请求已中断，请先查询实际结果。");
        } catch (com.google.gson.JsonParseException failure) {
            throw new ApiException(502, "INVALID_RESPONSE", "后端数据格式不正确，请检查前后端版本是否一致。");
        }
    }
}
