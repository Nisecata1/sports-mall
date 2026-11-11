package com.sportsmall.server;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

/** 读取构建时生成的版本信息，状态接口不再手写另一份版本号。 */
public record BuildInfo(String version, String builtAt) {
    private static final BuildInfo CURRENT = load();

    public static BuildInfo current() { return CURRENT; }

    static BuildInfo load() {
        Properties properties = new Properties();
        try (InputStream input = BuildInfo.class.getResourceAsStream("/build-info.properties")) {
            if (input != null) properties.load(input);
        } catch (IOException failure) {
            return new BuildInfo("development", "unknown");
        }
        return new BuildInfo(value(properties, "application.version", "development"),
                value(properties, "application.builtAt", "unknown"));
    }

    private static String value(Properties properties, String key, String fallback) {
        String value = properties.getProperty(key, "").trim();
        return value.isEmpty() || value.contains("${") ? fallback : value;
    }

    public Map<String, String> health() {
        return Map.of("application", "sports-mall", "version", version,
                "builtAt", builtAt, "status", "UP");
    }
}
