package com.sportsmall.integration;

import com.sportsmall.api.ApiDtos;
import com.sportsmall.api.ApiJson;
import com.sportsmall.client.ApiException;
import com.sportsmall.client.MallApiClient;
import com.sportsmall.dao.mysql.CategoryDao;
import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.model.CategoryView;
import com.sportsmall.util.CategoryTrees;

import javax.sql.DataSource;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 复用父探针确认过的临时实例，测试真实HTTP修改、删除、回滚与并发防环。 */
final class CategoryMutationChecks {
    private static int checks;

    static int run(URI address, DataSource sql) throws Exception {
        checks = 0;
        MallApiClient admin = new MallApiClient(address);
        rejected(() -> admin.updateCategory(1, "denied", null), 401, "anonymous category edit denied");
        rejected(() -> admin.deleteCategory(1), 401, "anonymous category deletion denied");
        MallApiClient ordinary = new MallApiClient(address);
        ordinary.login("integration_owner", "synthetic-integration-password".toCharArray());
        rejected(() -> ordinary.updateCategory(1, "denied", null), 403, "ordinary category edit denied");
        rejected(() -> ordinary.deleteCategory(1), 403, "ordinary category deletion denied");
        var identity = admin.login("integration_admin", "synthetic-integration-password".toCharArray());
        CategoryView root = admin.createCategory("编辑树根", null);
        CategoryView other = admin.createCategory("目标树根", null);
        CategoryView child = admin.createCategory("子分类", root.categoryId());
        CategoryView leaf = admin.createCategory("叶子", child.categoryId());
        long count = admin.categories().size();
        var renamed = admin.updateCategory(child.categoryId(), " 篮球装备 ", root.categoryId());
        check(renamed.categoryId() == child.categoryId() && renamed.name().equals("篮球装备"),
                "rename preserves category identity and trims submitted name");
        check(admin.categories().size() == count, "edit updates existing row instead of inserting another category");
        var moved = admin.updateCategory(child.categoryId(), renamed.name(), other.categoryId());
        check(moved.parentId().equals(other.categoryId()), "subtree can move to another root");
        check(find(admin, leaf.categoryId()).parentId().equals(child.categoryId()), "moving subtree preserves descendant parent IDs");
        check(read(sql, child.categoryId()).equals(moved), "HTTP edit equals direct SQL readback");
        var detached = admin.updateCategory(child.categoryId(), renamed.name(), null);
        check(detached.parentId() == null && read(sql, child.categoryId()).parentId() == null,
                "moving category to root writes SQL NULL");
        check(admin.updateCategory(child.categoryId(), renamed.name(), null).equals(detached), "unchanged edit is accepted accurately");
        admin.updateCategory(child.categoryId(), renamed.name(), root.categoryId());
        rejected(() -> admin.updateCategory(root.categoryId(), "self", root.categoryId()), 400, "self parent rejected");
        rejected(() -> admin.updateCategory(root.categoryId(), "cycle", leaf.categoryId()), 400, "descendant parent rejected");
        check(read(sql, root.categoryId()).equals(root), "cycle rejection preserves original name and parent");
        rejected(() -> admin.updateCategory(child.categoryId(), "missing parent", 999999L), 400, "edit missing parent rejected");
        rejected(() -> admin.updateCategory(999999L, "missing target", null), 400, "edit missing target rejected");
        rejected(() -> admin.updateCategory(child.categoryId(), " ", null), 400, "blank rename rejected");
        rejected(() -> admin.updateCategory(child.categoryId(), "字".repeat(51), null), 400, "overlong rename rejected");
        rejected(() -> admin.deleteCategory(root.categoryId()), 400, "category with children cannot be deleted");
        rejected(() -> admin.deleteCategory(1), 400, "category referenced by products cannot be deleted");
        check(admin.items().get(0).categoryId() == 1 && find(admin, root.categoryId()).equals(root),
                "rejected deletion leaves category and product assignment intact");

        var login = HttpIntegrationProbe.raw(address, "POST", "/api/auth/login", null,
                "{\"username\":\"integration_admin\",\"password\":\"synthetic-integration-password\"}", "application/json");
        String token = ApiJson.GSON.fromJson(login.body(), ApiDtos.LoginResponse.class).token();
        String path = "/api/admin/categories/" + child.categoryId();
        check(HttpIntegrationProbe.raw(address, "PUT", path, token,
                "{\"name\":\"injected\",\"role\":\"ADMIN\"}", "application/json").statusCode() == 400,
                "category edit rejects caller identity fields");
        check(HttpIntegrationProbe.raw(address, "PUT", path, token,
                "{\"name\":\"fraction\",\"parentId\":1.5}", "application/json").statusCode() == 400,
                "category edit rejects fractional parent ID");
        check(HttpIntegrationProbe.raw(address, "DELETE", path, token,
                "{\"userId\":1}", "application/json").statusCode() == 400, "category delete rejects body fields");
        check(HttpIntegrationProbe.raw(address, "DELETE", path + "?role=ADMIN", token,
                "{}", "application/json").statusCode() == 400, "category delete rejects identity query parameters");
        check(HttpIntegrationProbe.raw(address, "POST", path, token, "{}", "application/json").statusCode() == 405,
                "category item endpoint rejects unsupported method");
        var literal = admin.updateCategory(other.categoryId(), "球类'); DELETE FROM users; --", null);
        check(read(sql, other.categoryId()).equals(literal) && admin.account().userId() == identity.userId(),
                "SQL-looking rename is stored as literal data");

        trigger(sql, "CREATE TRIGGER category_edit_reject AFTER UPDATE ON categories "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'fixture rejection'");
        try {
            rejected(() -> admin.updateCategory(root.categoryId(), "must rollback", null), 503, "database edit failure is reported");
            check(read(sql, root.categoryId()).equals(root), "failed update rolls back original row");
        } finally { trigger(sql, "DROP TRIGGER category_edit_reject"); }
        trigger(sql, "CREATE TRIGGER category_delete_reject AFTER DELETE ON categories "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'fixture rejection'");
        try {
            rejected(() -> admin.deleteCategory(leaf.categoryId()), 503, "database delete failure is reported");
            check(read(sql, leaf.categoryId()).equals(leaf), "failed delete restores category row");
        } finally { trigger(sql, "DROP TRIGGER category_delete_reject"); }

        CategoryView x = admin.createCategory("并发根X", null);
        CategoryView y = admin.createCategory("并发根Y", null);
        CountDownLatch start = new CountDownLatch(1);
        var first = CompletableFuture.supplyAsync(() -> concurrentMove(admin, start, x, y));
        var second = CompletableFuture.supplyAsync(() -> concurrentMove(admin, start, y, x));
        start.countDown();
        int succeeded = first.get(20, TimeUnit.SECONDS) + second.get(20, TimeUnit.SECONDS);
        check(succeeded == 1, "opposing concurrent moves cannot both succeed");
        CategoryTrees.validate(admin.categories());
        check(true, "concurrent moves leave an acyclic tree");

        try (var connection = sql.getConnection(); var statement = connection.prepareStatement("UPDATE users SET role='USER' WHERE user_id=?")) {
            statement.setLong(1, identity.userId()); statement.executeUpdate();
        }
        try {
            rejected(() -> admin.updateCategory(root.categoryId(), "revoked", null), 403, "revoked admin cannot edit with old session");
            rejected(() -> admin.deleteCategory(leaf.categoryId()), 403, "revoked admin cannot delete with old session");
            var dao = new CategoryDao(sql);
            businessDenied(() -> dao.update(identity.userId(), root.categoryId(), "dao bypass", null), "edit transaction rechecks role");
            businessDenied(() -> dao.delete(identity.userId(), leaf.categoryId()), "delete transaction rechecks role");
        } finally {
            try (var connection = sql.getConnection(); var statement = connection.prepareStatement("UPDATE users SET role='ADMIN' WHERE user_id=?")) {
                statement.setLong(1, identity.userId()); statement.executeUpdate();
            }
        }
        check(admin.deleteCategory(leaf.categoryId()), "unreferenced leaf can be deleted");
        check(read(sql, leaf.categoryId()) == null && admin.categories().stream().noneMatch(row -> row.categoryId() == leaf.categoryId()),
                "successful delete disappears from SQL and HTTP tree");
        rejected(() -> admin.deleteCategory(leaf.categoryId()), 400, "repeated delete does not claim another removal");
        check(admin.deleteCategory(child.categoryId()) && admin.deleteCategory(root.categoryId()),
                "parents can be deleted after their children have been removed");
        admin.logout(); ordinary.logout();
        HttpIntegrationProbe.raw(address, "POST", "/api/auth/logout", token, "{}", "application/json");
        return checks;
    }

    private static int concurrentMove(MallApiClient client, CountDownLatch start, CategoryView target, CategoryView parent) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Concurrent fixture did not start");
            client.updateCategory(target.categoryId(), target.name(), parent.categoryId());
            return 1;
        } catch (ApiException failure) {
            if ((failure.status() == 400 && failure.code().equals("CATEGORY_CYCLE")) || failure.status() == 503) return 0;
            throw failure;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new AssertionError(failure);
        }
    }

    private static CategoryView find(MallApiClient client, long id) {
        return client.categories().stream().filter(row -> row.categoryId() == id).findFirst().orElseThrow();
    }
    private static CategoryView read(DataSource sql, long id) throws Exception {
        try (var connection = sql.getConnection(); var statement = connection.prepareStatement(
                "SELECT category_id,name,parent_id FROM categories WHERE category_id=?")) {
            statement.setLong(1, id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                long parent = rows.getLong("parent_id");
                Long parentId = rows.wasNull() ? null : parent;
                return new CategoryView(id, rows.getString("name"), parentId);
            }
        }
    }
    private static void trigger(DataSource sql, String fixedDdl) throws Exception {
        try (var connection = sql.getConnection(); var statement = connection.prepareStatement(fixedDdl)) { statement.execute(); }
    }
    private static void businessDenied(Runnable action, String label) {
        try { action.run(); throw new AssertionError(label); }
        catch (MallBusinessException failure) { check(Objects.equals(failure.code(), "ADMIN_REQUIRED"), label); }
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
