package com.sportsmall.integration;

import com.mongodb.client.MongoClients;
import com.sportsmall.api.ApiJson;
import com.sportsmall.server.BuildInfo;
import com.sportsmall.server.MallApiServer;
import com.sportsmall.server.MallServices;
import com.zaxxer.hikari.HikariDataSource;

import java.net.URI;
import java.net.http.HttpResponse;

/** 只关闭本检查单独创建的客户端和连接池，数据库实例由外层临时测试拥有。 */
final class ReadinessIntegrationChecks {
    private static int checks;

    static int run(URI base, HikariDataSource existing, String mongoUri) throws Exception {
        checks = 0;
        var health = HttpIntegrationProbe.raw(base, "GET", "/api/health", null, null, null);
        var json = ApiJson.GSON.fromJson(health.body(), com.google.gson.JsonObject.class);
        check(health.statusCode() == 200 && json.get("version").getAsString().equals(BuildInfo.current().version())
                && json.get("builtAt").getAsString().equals(BuildInfo.current().builtAt()), "health reports packaged build metadata");
        report(base, 200, "UP", "UP");
        environmentScript(base, existing.getJdbcUrl(), mongoUri, true);
        check(HttpIntegrationProbe.raw(base, "POST", "/api/ready", null, null, null).statusCode() == 405,
                "readiness accepts GET only");
        check(HttpIntegrationProbe.raw(base, "GET", "/api/ready/extra", null, null, null).statusCode() == 401,
                "readiness public exception is exact path only");

        try (var sql = new HikariDataSource(); var mongo = MongoClients.create(mongoUri)) {
            sql.setJdbcUrl(existing.getJdbcUrl()); sql.setUsername("root"); sql.setPassword("");
            sql.setMaximumPoolSize(1); sql.setMinimumIdle(0);
            try (var server = new MallApiServer(new MallServices(sql, mongo, mongo.getDatabase("sports_mall")), 0)) {
                server.start();
                URI probe = URI.create("http://127.0.0.1:" + server.port());
                report(probe, 200, "UP", "UP");
                mongo.close();
                report(probe, 503, "UP", "DOWN");
                environmentScript(probe, existing.getJdbcUrl(), mongoUri, false);
                check(HttpIntegrationProbe.raw(probe, "GET", "/api/health", null, null, null).statusCode() == 200,
                        "liveness remains available after dependency failure");
                sql.close();
                report(probe, 503, "DOWN", "DOWN");
            }
        }
        try (var sql = new HikariDataSource(); var mongo = MongoClients.create(mongoUri)) {
            sql.close();
            try (var server = new MallApiServer(new MallServices(sql, mongo, mongo.getDatabase("sports_mall")), 0)) {
                server.start();
                report(URI.create("http://127.0.0.1:" + server.port()), 503, "DOWN", "UP");
            }
        }
        return checks;
    }

    private static void report(URI base, int status, String mysql, String mongodb) throws Exception {
        HttpResponse<String> response = HttpIntegrationProbe.raw(base, "GET", "/api/ready", null, null, null);
        var json = ApiJson.GSON.fromJson(response.body(), com.google.gson.JsonObject.class);
        var dependencies = json.getAsJsonObject("dependencies");
        check(response.statusCode() == status && dependencies.get("mysql").getAsString().equals(mysql)
                && dependencies.get("mongodb").getAsString().equals(mongodb), "readiness HTTP=" + status + " SQL=" + mysql + " Mongo=" + mongodb);
        check(json.keySet().equals(java.util.Set.of("status", "dependencies")), "readiness exposes fixed status fields only");
    }

    private static void check(boolean passed, String label) {
        if (!passed) throw new AssertionError(label);
        checks++; System.out.println("PASS " + label);
    }

    private static void environmentScript(URI base, String sqlUrl, String mongoUri, boolean expected) throws Exception {
        var process = new ProcessBuilder("pwsh.exe", "-NoLogo", "-NoProfile", "-File", "Check-Environment.ps1",
                "-ApiPort", Integer.toString(base.getPort()),
                "-MySqlPort", Integer.toString(URI.create(sqlUrl.substring(5)).getPort()),
                "-MongoPort", Integer.toString(URI.create(mongoUri).getPort()), "-Json")
                .redirectErrorStream(true).start();
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly(); throw new AssertionError("Environment check did not finish");
        }
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        var report = ApiJson.GSON.fromJson(output, com.google.gson.JsonObject.class);
        if (process.exitValue() != (expected ? 0 : 1) || report.get("passed").getAsBoolean() != expected) {
            System.out.println("ENVIRONMENT_CHECK_DIAGNOSTIC=" + output);
        }
        check(process.exitValue() == (expected ? 0 : 1) && report.get("passed").getAsBoolean() == expected,
                "environment script reports overall readiness=" + expected);
        var dependencies = java.util.stream.StreamSupport.stream(report.getAsJsonArray("checks").spliterator(), false)
                .map(com.google.gson.JsonElement::getAsJsonObject)
                .filter(item -> item.get("name").getAsString().equals("后端数据库连接")).findFirst().orElseThrow();
        check(dependencies.get("passed").getAsBoolean() == expected,
                "environment script verifies backend connection rather than TCP only");
    }
}
