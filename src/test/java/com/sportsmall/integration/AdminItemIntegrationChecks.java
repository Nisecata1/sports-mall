package com.sportsmall.integration;

import com.mongodb.client.MongoDatabase;
import com.sportsmall.api.ApiDtos;
import com.sportsmall.api.ApiJson;
import com.sportsmall.client.ApiException;
import com.sportsmall.client.MallApiClient;
import com.sportsmall.dao.mysql.AdminItemDao;
import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.model.*;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;

import static com.mongodb.client.model.Filters.eq;

/** 商品基础信息真实链路；仅使用父探针已经核实的数据目录。 */
final class AdminItemIntegrationChecks {
    private static int checks;
    private static final BigDecimal PRICE = new BigDecimal("88.80");

    static int run(URI address, DataSource sql, MongoDatabase mongo) throws Exception {
        checks = 0;
        MallApiClient admin = new MallApiClient(address);
        rejected(admin::adminItems, 401, "item management requires login");
        rejected(() -> admin.createItem("anonymous", 1, PRICE, 1), 401, "anonymous item insertion denied");
        rejected(() -> admin.updateItem(1, "anonymous", 1, PRICE, 1), 401, "anonymous item update denied");
        MallApiClient ordinary = new MallApiClient(address);
        ordinary.login("integration_owner", "synthetic-integration-password".toCharArray());
        long before = count(sql);
        rejected(ordinary::adminItems, 403, "ordinary user cannot read management item list");
        rejected(() -> ordinary.createItem("denied", 1, PRICE, 1), 403, "ordinary user cannot insert item");
        rejected(() -> ordinary.updateItem(1, "denied", 1, PRICE, 0), 403, "ordinary user cannot edit or shelve item");
        check(count(sql) == before, "denied management writes preserve item rows");

        var actor = admin.login("integration_admin", "synthetic-integration-password".toCharArray());
        check(admin.adminItems().stream().anyMatch(item -> item.itemId() == 2 && item.status() == 0),
                "management list includes existing inactive item");
        var category = admin.createCategory("商品管理测试分类", null);
        var otherCategory = admin.createCategory("改后商品分类", null);
        var item = admin.createItem(" 管理演示球 ", category.categoryId(), PRICE, 0);
        check(item.title().equals("管理演示球") && item.status() == 0 && item.categoryId() == category.categoryId(),
                "item insertion returns normalized title and selected category and status");
        check(count(sql) == before + 1 && read(sql, item.itemId()).equals(item), "item insertion matches direct SQL readback");
        check(admin.items().stream().noneMatch(row -> row.itemId() == item.itemId()), "new inactive item is absent from available products");
        check(mongo.getCollection("item_details").countDocuments(eq("item_id", item.itemId())) == 0,
                "basic item operation does not invent a MongoDB detail document");
        var active = admin.updateItem(item.itemId(), item.title(), item.categoryId(), PRICE, 1);
        check(active.itemId() == item.itemId() && active.status() == 1, "activation keeps the generated item ID");
        check(ordinary.items().stream().anyMatch(row -> row.itemId() == item.itemId()), "activated item appears through ordinary product API");
        var order = ordinary.createOrder(item.itemId(), PRICE);
        check(order.amount().compareTo(PRICE) == 0, "new product can be ordered at current database price");
        var changed = admin.updateItem(item.itemId(), "改名后的演示球", otherCategory.categoryId(), new BigDecimal("99.90"), 1);
        check(changed.categoryName().equals(otherCategory.name()) && changed.price().compareTo(new BigDecimal("99.90")) == 0,
                "item edit changes name category and price without changing identity");
        check(read(sql, item.itemId()).equals(changed) && count(sql) == before + 1,
                "edit is a SQL update rather than a second item insertion");
        var historical = ordinary.orders(OrderFilter.all()).stream().filter(row -> row.orderId() == order.orderId()).findFirst().orElseThrow();
        check(historical.amount().compareTo(PRICE) == 0, "price edit preserves previous order amount snapshot");
        rejected(() -> ordinary.createOrder(item.itemId(), PRICE), 400, "stale price quote cannot create a new order");
        admin.updateItem(item.itemId(), changed.title(), changed.categoryId(), changed.price(), 0);
        check(admin.adminItems().stream().anyMatch(row -> row.itemId() == item.itemId() && row.status() == 0),
                "shelved item remains in management list");
        check(ordinary.items().stream().noneMatch(row -> row.itemId() == item.itemId()), "shelved item disappears from available products");
        rejected(() -> ordinary.createOrder(item.itemId(), changed.price()), 400, "shelved item cannot be ordered");
        check(ordinary.orders(OrderFilter.all()).stream().anyMatch(row -> row.orderId() == order.orderId()),
                "shelving preserves historical order linkage");
        rejected(() -> admin.deleteCategory(changed.categoryId()), 400, "inactive item still protects its referenced category");
        admin.updateItem(item.itemId(), changed.title(), changed.categoryId(), changed.price(), 1);
        check(ordinary.createOrder(item.itemId(), changed.price()).amount().compareTo(changed.price()) == 0,
                "reactivated product uses new price for new order");
        check(admin.updateItem(item.itemId(), changed.title(), changed.categoryId(), changed.price(), 1).equals(changed),
                "unchanged item save returns actual stored values");

        long validCount = count(sql);
        rejected(() -> admin.createItem(" ", category.categoryId(), PRICE, 1), 400, "blank item title rejected");
        rejected(() -> admin.createItem("字".repeat(201), category.categoryId(), PRICE, 1), 400, "overlong item title rejected");
        rejected(() -> admin.createItem("missing", 999999, PRICE, 1), 400, "nonexistent item category rejected");
        rejected(() -> admin.updateItem(999999, "missing", category.categoryId(), PRICE, 1), 400, "nonexistent item update rejected");
        rejected(() -> admin.updateItem(item.itemId(), "missing category", 999999, PRICE, 1), 400, "update with missing category rejected");
        for (String value : new String[]{"0", "-1", "0.001", "100000000.00"}) {
            rejected(() -> admin.createItem("bad price", category.categoryId(), new BigDecimal(value), 1), 400, "invalid item price rejected: " + value);
        }
        rejected(() -> admin.createItem("bad status", category.categoryId(), PRICE, 2), 400, "unsupported item status rejected");
        check(count(sql) == validCount && read(sql, item.itemId()).equals(changed), "invalid item requests preserve stored rows");

        var login = HttpIntegrationProbe.raw(address, "POST", "/api/auth/login", null,
                "{\"username\":\"integration_admin\",\"password\":\"synthetic-integration-password\"}", "application/json");
        String token = ApiJson.GSON.fromJson(login.body(), ApiDtos.LoginResponse.class).token();
        String body = "{\"title\":\"raw\",\"categoryId\":1,\"price\":10,\"status\":1}";
        String path = "/api/admin/items/" + item.itemId();
        check(HttpIntegrationProbe.raw(address, "POST", "/api/admin/items", token, body.replace("\"status\":1", "\"status\":1,\"role\":\"ADMIN\""), "application/json").statusCode() == 400,
                "item management rejects injected role");
        check(HttpIntegrationProbe.raw(address, "PUT", path, token, body.replace("\"status\":1", "\"status\":0.5"), "application/json").statusCode() == 400,
                "fractional sale status rejected");
        check(HttpIntegrationProbe.raw(address, "POST", "/api/admin/items", token, body.replace("\"categoryId\":1", "\"categoryId\":1.5"), "application/json").statusCode() == 400,
                "fractional product category rejected");
        check(HttpIntegrationProbe.raw(address, "GET", "/api/admin/items?userId=1", token, null, null).statusCode() == 400,
                "item management rejects caller identity query");
        check(HttpIntegrationProbe.raw(address, "DELETE", path, token, "{}", "application/json").statusCode() == 405,
                "physical item deletion is not exposed as shelving");
        check(HttpIntegrationProbe.raw(address, "PUT", "/api/admin/items/999999999999999999999999999", token, body, "application/json").statusCode() == 400,
                "overflowing item ID rejected");

        var literal = admin.createItem("球'); DELETE FROM users; --", category.categoryId(), PRICE, 0);
        check(read(sql, literal.itemId()).equals(literal) && admin.account().userId() == actor.userId(),
                "SQL-looking product title remains literal data");

        trigger(sql, "CREATE TRIGGER item_fixture_insert_reject AFTER INSERT ON items FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'fixture rejection'");
        long beforeFailure = count(sql);
        try {
            rejected(() -> admin.createItem("must rollback", category.categoryId(), PRICE, 1), 503, "database insert failure is reported");
            check(count(sql) == beforeFailure, "failed product insert leaves no row");
        } finally { trigger(sql, "DROP TRIGGER item_fixture_insert_reject"); }
        trigger(sql, "CREATE TRIGGER item_fixture_update_reject AFTER UPDATE ON items FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'fixture rejection'");
        try {
            rejected(() -> admin.updateItem(item.itemId(), "must rollback", category.categoryId(), PRICE, 0), 503, "database product update failure is reported");
            check(read(sql, item.itemId()).equals(changed), "failed item edit restores prior name category price and status");
        } finally { trigger(sql, "DROP TRIGGER item_fixture_update_reject"); }

        setAccount(sql, actor.userId(), "USER", 1);
        try {
            rejected(admin::adminItems, 403, "revoked administrator cannot list managed products");
            rejected(() -> admin.createItem("revoked", 1, PRICE, 1), 403, "revoked administrator cannot add product");
            rejected(() -> admin.updateItem(item.itemId(), "revoked", 1, PRICE, 0), 403, "revoked administrator cannot update product");
            AdminItemDao dao = new AdminItemDao(sql);
            businessDenied(() -> dao.create(actor.userId(), new ItemFields("bypass", 1, PRICE, 1)), "item insertion transaction rechecks role");
            businessDenied(() -> dao.update(actor.userId(), item.itemId(), new ItemFields("bypass", 1, PRICE, 0)), "item update transaction rechecks role");
        } finally { setAccount(sql, actor.userId(), "ADMIN", 1); }
        setAccount(sql, actor.userId(), "ADMIN", 0);
        try { rejected(() -> admin.createItem("disabled", 1, PRICE, 1), 400, "disabled administrator cannot add product"); }
        finally { setAccount(sql, actor.userId(), "ADMIN", 1); }

        try (var connection = sql.getConnection(); var statement = connection.prepareStatement(
                "INSERT INTO items(title,category_id,price,status) VALUES(?,?,?,0)")) {
            for (int i = 0; i < 102; i++) {
                statement.setString(1, "item_list_fixture_" + i); statement.setLong(2, category.categoryId());
                statement.setBigDecimal(3, PRICE); statement.addBatch();
            }
            statement.executeBatch();
        }
        var bounded = admin.adminItems();
        check(bounded.size() == 100 && java.util.stream.IntStream.range(1, bounded.size())
                .allMatch(i -> bounded.get(i - 1).itemId() < bounded.get(i).itemId()), "management item list has stable order and explicit limit");
        admin.logout(); ordinary.logout();
        HttpIntegrationProbe.raw(address, "POST", "/api/auth/logout", token, "{}", "application/json");
        return checks;
    }

    private static AdminItemView read(DataSource sql, long id) throws Exception {
        try (var connection = sql.getConnection(); var statement = connection.prepareStatement(
                "SELECT i.title,i.category_id,c.name,i.price,i.status FROM items i JOIN categories c ON i.category_id=c.category_id WHERE i.item_id=?")) {
            statement.setLong(1, id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new AdminItemView(id, rows.getString(1), rows.getLong(2), rows.getString(3), rows.getBigDecimal(4), rows.getInt(5));
            }
        }
    }
    private static long count(DataSource sql) throws Exception {
        try (var connection = sql.getConnection(); var statement = connection.prepareStatement("SELECT COUNT(*) FROM items"); var rows = statement.executeQuery()) {
            rows.next(); return rows.getLong(1);
        }
    }
    private static void setAccount(DataSource sql, long id, String role, int status) throws Exception {
        try (var connection = sql.getConnection(); var statement = connection.prepareStatement("UPDATE users SET role=?,status=? WHERE user_id=?")) {
            statement.setString(1, role); statement.setInt(2, status); statement.setLong(3, id); statement.executeUpdate();
        }
    }
    private static void trigger(DataSource sql, String ddl) throws Exception {
        try (var connection = sql.getConnection(); var statement = connection.prepareStatement(ddl)) { statement.execute(); }
    }
    private static void businessDenied(Runnable action, String label) {
        try { action.run(); throw new AssertionError(label); }
        catch (MallBusinessException failure) { check(failure.code().equals("ADMIN_REQUIRED"), label); }
    }
    private static void rejected(Runnable action, int status, String label) {
        try { action.run(); throw new AssertionError(label); }
        catch (ApiException failure) { check(failure.status() == status, label); }
    }
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++; System.out.println("PASS " + label);
    }
}
