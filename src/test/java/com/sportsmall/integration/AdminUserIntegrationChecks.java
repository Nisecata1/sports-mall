package com.sportsmall.integration;

import com.sportsmall.api.ApiDtos;
import com.sportsmall.api.ApiJson;
import com.sportsmall.client.ApiException;
import com.sportsmall.client.MallApiClient;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;

/** 仅由父探针在核对过目录的临时双库中运行；不连接课程主库。 */
final class AdminUserIntegrationChecks {
    private static int checks;

    static int run(URI address, DataSource sql) throws Exception {
        checks = 0;
        MallApiClient admin = new MallApiClient(address);
        rejected(admin::adminUsers, 401, "anonymous user list denied");
        MallApiClient ordinary = new MallApiClient(address);
        ordinary.login("integration_owner", "synthetic-integration-password".toCharArray());
        rejected(ordinary::adminUsers, 403, "ordinary user cannot call management API directly");
        check(HttpIntegrationProbe.raw(address, "POST", "/api/auth/register", null,
                "{\"username\":\"injected_admin\",\"role\":\"ADMIN\"}", "application/json").statusCode() == 400,
                "registration cannot assign its own administrator role");

        var registered = admin.register("integration_admin", "admin@example.test", "",
                "synthetic-integration-password".toCharArray(), "synthetic-integration-password".toCharArray());
        check(admin.login(registered.username(), "synthetic-integration-password".toCharArray()).role().equals("USER"),
                "new administrator candidate initially registers as USER");
        rejected(admin::adminUsers, 403, "candidate has no permission before trusted promotion");
        setRoleAndStatus(sql, registered.userId(), "ADMIN", 1);
        check(admin.adminUsers().stream().anyMatch(row -> row.userId() == registered.userId()
                        && row.role().equals("ADMIN")),
                "permission uses current database role rather than stale login role");
        admin.logout();
        check(admin.login(registered.username(), "synthetic-integration-password".toCharArray()).role().equals("ADMIN"),
                "fresh login exposes current role for navigation");

        var login = HttpIntegrationProbe.raw(address, "POST", "/api/auth/login", null,
                "{\"username\":\"integration_admin\",\"password\":\"synthetic-integration-password\"}",
                "application/json");
        String token = ApiJson.GSON.fromJson(login.body(), ApiDtos.LoginResponse.class).token();
        var response = HttpIntegrationProbe.raw(address, "GET", "/api/admin/users", token, null, null);
        check(response.statusCode() == 200
                        && ApiJson.GSON.fromJson(response.body(), com.google.gson.JsonArray.class).asList().stream()
                        .allMatch(row -> row.getAsJsonObject().keySet().equals(
                                java.util.Set.of("userId", "username", "role", "status"))),
                "management response includes only identity and status fields");
        check(HttpIntegrationProbe.raw(address, "GET", "/api/admin/users?role=ADMIN", token, null, null).statusCode() == 400,
                "management API rejects caller-supplied identity query");
        check(HttpIntegrationProbe.raw(address, "POST", "/api/admin/users", token, "{}", "application/json").statusCode() == 405,
                "read-only management route rejects writes");

        setRoleAndStatus(sql, registered.userId(), "USER", 1);
        rejected(admin::adminUsers, 403, "revoked role takes effect for existing session");
        setRoleAndStatus(sql, registered.userId(), "ADMIN", 0);
        rejected(admin::adminUsers, 400, "disabled administrator cannot use existing session");
        setRoleAndStatus(sql, registered.userId(), "ADMIN", 1);
        check(!admin.adminUsers().isEmpty(), "restored active role can read again");

        try (Connection connection = sql.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO users(username,password_hash,email) VALUES(?,?,?)")) {
            for (int i = 0; i < 105; i++) {
                insert.setString(1, "admin_list_fixture_" + i);
                insert.setString(2, "synthetic-unused-hash");
                insert.setString(3, "list_" + i + "@example.test");
                insert.addBatch();
            }
            insert.executeBatch();
        }
        var bounded = admin.adminUsers();
        check(bounded.size() == 100 && java.util.stream.IntStream.range(1, bounded.size())
                        .allMatch(i -> bounded.get(i - 1).userId() < bounded.get(i).userId()),
                "management list is bounded and ordered by primary key");
        HttpIntegrationProbe.raw(address, "POST", "/api/auth/logout", token, "{}", "application/json");
        check(HttpIntegrationProbe.raw(address, "GET", "/api/admin/users", token, null, null).statusCode() == 401,
                "logged-out management token cannot be replayed");
        admin.logout();
        ordinary.logout();
        return checks;
    }

    private static void setRoleAndStatus(DataSource sql, long id, String role, int status) throws Exception {
        try (Connection connection = sql.getConnection();
             PreparedStatement statement = connection.prepareStatement("UPDATE users SET role=?, status=? WHERE user_id=?")) {
            statement.setString(1, role);
            statement.setInt(2, status);
            statement.setLong(3, id);
            if (statement.executeUpdate() != 1) throw new AssertionError("Fixture account missing");
        }
    }

    private static void rejected(Runnable action, int status, String label) {
        ApiException failure;
        try {
            action.run();
            throw new AssertionError("Expected rejection: " + label);
        } catch (ApiException expected) { failure = expected; }
        check(failure.status() == status, label);
    }

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++;
        System.out.println("PASS " + label);
    }
}
