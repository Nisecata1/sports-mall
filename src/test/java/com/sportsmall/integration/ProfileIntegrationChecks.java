package com.sportsmall.integration;

import com.sportsmall.api.ApiDtos;
import com.sportsmall.api.ApiJson;
import com.sportsmall.client.ApiException;
import com.sportsmall.client.MallApiClient;
import com.sportsmall.dao.mysql.ProfileDao;
import com.sportsmall.model.OrderFilter;
import com.sportsmall.model.UserProfile;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 在父探针已经验证过的临时双库中验证档案CRUD与所属用户。 */
final class ProfileIntegrationChecks {
    private static int checks;

    static int run(URI address, DataSource sql) throws Exception {
        checks = 0;
        MallApiClient owner = new MallApiClient(address);
        expectFailure(owner::profile, 401, "profile read requires login");
        expectFailure(owner::deleteProfile, 401, "profile delete requires login");
        owner.login("integration_owner", "synthetic-integration-password".toCharArray());
        check(owner.profile().profileId() == null, "missing profile is an explicit empty result");
        UserProfile created = owner.saveProfile(" 演示用户 ", "DEMO-CARD", "演示地址一", "第一行\n第二行");
        check(created.userId() == 1 && created.profileId() != null && created.profileId() > 0,
                "first save creates profile for logged-in owner");
        check(created.realName().equals("演示用户") && created.notes().equals("第一行\n第二行"),
                "profile Chinese text and multiline notes round trip");
        check(owner.profile().equals(created), "GET returns the persisted profile");
        UserProfile repeated = owner.saveProfile("演示用户", "DEMO-CARD", "演示地址一", "第一行\n第二行");
        check(repeated.profileId().equals(created.profileId()) && profileCount(sql, 1) == 1,
                "repeated save keeps one profile and the same primary key");
        UserProfile updated = owner.saveProfile("演示用户", "DEMO-CARD", "演示地址二", "修改备注");
        check(updated.profileId().equals(created.profileId()) && updated.address().equals("演示地址二"),
                "existing profile updates without changing profile ID");
        var overview = owner.overview();
        check(overview.realName().equals(updated.realName()) && overview.address().equals(updated.address()),
                "existing MySQL view reads back changed profile");
        expectFailure(() -> owner.saveProfile("字".repeat(51), "", "", ""), 400, "overlong profile input rejected");
        check(owner.profile().equals(updated), "invalid update preserves previous fields");
        String unicodeName = "𠮷".repeat(50);
        UserProfile unicode = owner.saveProfile(unicodeName, "", "演示地址二", "Unicode演示");
        check(unicode.realName().equals(unicodeName), "utf8mb4 accepts fifty supplementary characters");
        UserProfile cleared = owner.saveProfile("", null, " ", "\n");
        check(cleared.profileId().equals(created.profileId()) && cleared.realName() == null
                        && cleared.idCard() == null && cleared.address() == null && cleared.notes() == null,
                "blank optional fields persist as SQL NULL in the existing row");
        owner.saveProfile("待删除演示", "DEMO", "待删除地址", "待删除备注");

        MallApiClient other = new MallApiClient(address);
        other.login("integration_other", "synthetic-integration-password".toCharArray());
        check(other.profile().userId() == 2 && other.profile().profileId() == null,
                "other account does not see the first owners profile");
        MallApiClient secondSession = new MallApiClient(address);
        secondSession.login("integration_other", "synthetic-integration-password".toCharArray());
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> {
                start.await();
                return other.saveProfile("并发甲", "A", "地址甲", "备注甲");
            });
            var second = executor.submit(() -> {
                start.await();
                return secondSession.saveProfile("并发乙", "B", "地址乙", "备注乙");
            });
            start.countDown();
            UserProfile firstResult = first.get(15, TimeUnit.SECONDS);
            UserProfile secondResult = second.get(15, TimeUnit.SECONDS);
            UserProfile actual = other.profile();
            check(firstResult.profileId().equals(secondResult.profileId()) && profileCount(sql, 2) == 1,
                    "concurrent first saves create one profile");
            check(actual.equals(firstResult) || actual.equals(secondResult),
                    "concurrent save returns one complete submission rather than mixed fields");
        } finally {
            executor.shutdownNow();
        }

        var login = HttpIntegrationProbe.raw(address, "POST", "/api/auth/login", null,
                "{\"username\":\"integration_owner\",\"password\":\"synthetic-integration-password\"}", "application/json");
        String token = ApiJson.GSON.fromJson(login.body(), ApiDtos.LoginResponse.class).token();
        check(HttpIntegrationProbe.raw(address, "PUT", "/api/me/profile", token,
                "{\"realName\":\"injected\",\"userId\":2}", "application/json").statusCode() == 400,
                "profile save rejects injected owner");
        check(HttpIntegrationProbe.raw(address, "DELETE", "/api/me/profile", token,
                "{\"userId\":2}", "application/json").statusCode() == 400,
                "profile delete rejects injected owner");
        check(other.profile().userId() == 2 && other.profile().profileId() != null,
                "injected requests leave other profile present");
        HttpIntegrationProbe.raw(address, "POST", "/api/auth/logout", token, "{}", "application/json");
        expectSqlFailure(sql, "INSERT INTO profiles(user_id) VALUES(999999)", 1452,
                "foreign key rejects profile for missing user");
        expectSqlFailure(sql, "INSERT INTO profiles(user_id) VALUES(1)", 1062,
                "unique user ID rejects a second profile");

        UserProfile beforeRejectedUpdate = owner.profile();
        try (Connection connection = sql.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TRIGGER profile_fixture_reject AFTER UPDATE ON profiles
                    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'fixture rejection'
                    """);
        }
        try {
            expectFailure(() -> owner.saveProfile("被拒绝的修改", "", "被拒绝的地址", ""), 503,
                    "database write rejection is not reported as success");
            check(owner.profile().equals(beforeRejectedUpdate), "failed profile update leaves previous values intact");
        } finally {
            try (Connection connection = sql.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER profile_fixture_reject");
            }
        }

        int ordersBeforeDelete = owner.orders(OrderFilter.all()).size();
        check(owner.deleteProfile(), "owner can delete its existing profile");
        check(owner.profile().profileId() == null && owner.overview().realName() == null && owner.overview().address() == null,
                "profile absence is reflected in direct read and LEFT JOIN overview");
        check(owner.account().userId() == 1 && owner.orders(OrderFilter.all()).size() == ordersBeforeDelete,
                "profile deletion preserves account and orders");
        check(other.profile().profileId() != null, "profile deletion is restricted to current owner");
        check(!owner.deleteProfile(), "repeated profile deletion returns already absent");

        ProfileDao profiles = new ProfileDao(sql);
        UserProfile beforeDisabling = profiles.findByUserId(2).orElseThrow();
        try (Connection connection = sql.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE users SET status = 0 WHERE user_id = 2");
        }
        try {
            expectFailure(other::profile, 400, "disabled account cannot read profile through active session");
            expectFailure(() -> other.saveProfile("disabled", "", "", ""), 400,
                    "disabled account cannot save profile through active session");
            expectFailure(other::deleteProfile, 400, "disabled account cannot delete profile through active session");
            check(profiles.findByUserId(2).orElseThrow().equals(beforeDisabling),
                    "disabled-account requests preserve the profile");
        } finally {
            try (Connection connection = sql.getConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE users SET status = 1 WHERE user_id = 2");
            }
        }
        secondSession.logout();
        other.logout();
        owner.logout();
        return checks;
    }

    private static long profileCount(DataSource sql, long userId) throws SQLException {
        try (Connection connection = sql.getConnection();
             var statement = connection.prepareStatement("SELECT COUNT(*) FROM profiles WHERE user_id = ?")) {
            statement.setLong(1, userId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Profile count missing");
                return rows.getLong(1);
            }
        }
    }

    private static void expectSqlFailure(DataSource sql, String query, int code, String label) throws SQLException {
        try (Connection connection = sql.getConnection(); Statement statement = connection.createStatement()) {
            try {
                statement.executeUpdate(query);
                throw new AssertionError("Expected fixture constraint rejection");
            } catch (SQLException failure) {
                check(failure.getErrorCode() == code, label);
            }
        }
    }

    private static void expectFailure(Runnable action, int status, String label) {
        try {
            action.run();
            throw new AssertionError("Expected HTTP rejection");
        } catch (ApiException failure) {
            check(failure.status() == status, label);
        }
    }

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++;
        System.out.println("PASS " + label);
    }
}
