package com.sportsmall.integration;

import com.sportsmall.api.ApiDtos;
import com.sportsmall.api.ApiJson;
import com.sportsmall.client.ApiException;
import com.sportsmall.client.MallApiClient;
import com.sportsmall.dao.mysql.CategoryDao;
import com.sportsmall.exception.MallBusinessException;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;

/** 在父探针验证过目录的临时实例中检查分类权限、树结构与JDBC写入。 */
final class CategoryIntegrationChecks {
    private static int checks;

    static int run(URI address, DataSource sql) throws Exception {
        checks = 0;
        MallApiClient admin = new MallApiClient(address);
        rejected(admin::categories, 401, "category tree requires login");
        rejected(() -> admin.createCategory("anonymous", null), 401, "category creation requires login");
        MallApiClient ordinary = new MallApiClient(address);
        ordinary.login("integration_owner", "synthetic-integration-password".toCharArray());
        long before = count(sql, "categories");
        rejected(ordinary::categories, 403, "ordinary user cannot query management tree");
        rejected(() -> ordinary.createCategory("denied", null), 403, "ordinary user cannot create a category");
        check(count(sql, "categories") == before, "denied category requests do not insert rows");

        var identity = admin.login("integration_admin", "synthetic-integration-password".toCharArray());
        var root = admin.createCategory("  课程分类  ", null);
        check(root.name().equals("课程分类") && root.parentId() == null, "root category stores SQL NULL parent");
        var child = admin.createCategory("篮球", root.categoryId());
        var grandchild = admin.createCategory("训练篮球", child.categoryId());
        check(child.parentId().equals(root.categoryId()) && grandchild.parentId().equals(child.categoryId()),
                "category parent IDs form multiple levels");
        check(admin.categories().containsAll(java.util.List.of(root, child, grandchild)),
                "category tree round trip retains IDs, parents and Chinese text");
        try (Connection connection = sql.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT name, parent_id FROM categories WHERE category_id=?")) {
            statement.setLong(1, child.categoryId());
            try (var rows = statement.executeQuery()) {
                check(rows.next() && rows.getString("name").equals(child.name())
                                && rows.getLong("parent_id") == root.categoryId(),
                        "HTTP category write matches direct SQL readback");
            }
        }
        long beforeInvalid = count(sql, "categories");
        rejected(() -> admin.createCategory("missing parent", 999999L), 400, "missing category parent is rejected");
        rejected(() -> admin.createCategory(" ", null), 400, "blank category name is rejected");
        rejected(() -> admin.createCategory("字".repeat(51), null), 400, "overlong category name is rejected");
        check(count(sql, "categories") == beforeInvalid, "invalid category requests preserve row count");

        var login = HttpIntegrationProbe.raw(address, "POST", "/api/auth/login", null,
                "{\"username\":\"integration_admin\",\"password\":\"synthetic-integration-password\"}",
                "application/json");
        String token = ApiJson.GSON.fromJson(login.body(), ApiDtos.LoginResponse.class).token();
        check(HttpIntegrationProbe.raw(address, "POST", "/api/admin/categories", token,
                "{\"name\":\"injected\",\"role\":\"ADMIN\",\"userId\":1}", "application/json").statusCode() == 400,
                "category write rejects caller-supplied identity fields");
        check(HttpIntegrationProbe.raw(address, "POST", "/api/admin/categories", token,
                "{\"name\":\"fraction\",\"parentId\":1.5}", "application/json").statusCode() == 400,
                "fractional category parent is rejected");
        check(HttpIntegrationProbe.raw(address, "DELETE", "/api/admin/categories", token, "{}", "application/json").statusCode() == 405,
                "category stage does not accept unsupported delete requests");

        long usersBefore = count(sql, "users");
        String literal = "球类'); DELETE FROM users; --";
        var literalCategory = admin.createCategory(literal, null);
        check(literalCategory.name().equals(literal) && count(sql, "users") == usersBefore,
                "SQL-looking category name remains literal data");

        execute(sql, "UPDATE categories SET parent_id=? WHERE category_id=?", grandchild.categoryId(), grandchild.categoryId());
        try {
            rejected(admin::categories, 503, "self-referencing corrupt data is not displayed as a valid tree");
        } finally {
            execute(sql, "UPDATE categories SET parent_id=? WHERE category_id=?", child.categoryId(), grandchild.categoryId());
        }
        check(admin.categories().contains(grandchild), "category tree works after fixture relationship is restored");

        long beforeRejection = count(sql, "categories");
        try (Connection connection = sql.getConnection(); var statement = connection.prepareStatement("""
                CREATE TRIGGER category_fixture_reject AFTER INSERT ON categories
                FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'fixture rejection'
                """)) {
            statement.execute();
        }
        try {
            rejected(() -> admin.createCategory("database rejected", null), 503, "failed category write is not reported as saved");
            check(count(sql, "categories") == beforeRejection, "failed insert leaves no category row");
        } finally {
            try (Connection connection = sql.getConnection();
                 var statement = connection.prepareStatement("DROP TRIGGER category_fixture_reject")) {
                statement.execute();
            }
        }

        execute(sql, "UPDATE users SET role=? WHERE user_id=?", "USER", identity.userId());
        try {
            rejected(admin::categories, 403, "revoked administrator cannot read categories with old session");
            rejected(() -> admin.createCategory("revoked", null), 403, "revoked administrator cannot create with old session");
            try {
                new CategoryDao(sql).create(identity.userId(), "dao bypass", null);
                throw new AssertionError("Write transaction did not recheck actor role");
            } catch (MallBusinessException failure) {
                check(failure.code().equals("ADMIN_REQUIRED"), "write transaction independently rechecks administrator role");
            }
        } finally {
            execute(sql, "UPDATE users SET role=? WHERE user_id=?", "ADMIN", identity.userId());
        }
        execute(sql, "UPDATE users SET status=? WHERE user_id=?", 0, identity.userId());
        try {
            rejected(() -> admin.createCategory("disabled", null), 400, "disabled administrator cannot create categories");
        } finally {
            execute(sql, "UPDATE users SET status=? WHERE user_id=?", 1, identity.userId());
        }
        check(admin.categories().stream().anyMatch(row -> row.categoryId() == 1),
                "original fixture category is preserved");
        check(admin.items().get(0).categoryId() == 1, "new categories do not change existing product assignment");
        HttpIntegrationProbe.raw(address, "POST", "/api/auth/logout", token, "{}", "application/json");
        admin.logout();
        ordinary.logout();
        return checks;
    }

    private static long count(DataSource sql, String table) throws Exception {
        // 仅允许探针内部固定表名；业务DAO不拼接外部输入。
        String query = switch (table) {
            case "categories" -> "SELECT COUNT(*) FROM categories";
            case "users" -> "SELECT COUNT(*) FROM users";
            default -> throw new IllegalArgumentException("Unknown fixture table");
        };
        try (Connection connection = sql.getConnection(); var statement = connection.prepareStatement(query);
             var rows = statement.executeQuery()) {
            if (!rows.next()) throw new AssertionError("Fixture count missing");
            return rows.getLong(1);
        }
    }

    private static void execute(DataSource sql, String query, Object first, Object second) throws Exception {
        try (Connection connection = sql.getConnection(); var statement = connection.prepareStatement(query)) {
            statement.setObject(1, first);
            statement.setObject(2, second);
            if (statement.executeUpdate() != 1) throw new AssertionError("Fixture row missing");
        }
    }

    private static void rejected(Runnable action, int status, String label) {
        try {
            action.run();
            throw new AssertionError("Expected rejection: " + label);
        } catch (ApiException expected) {
            check(expected.status() == status, label);
        }
    }

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++;
        System.out.println("PASS " + label);
    }
}
