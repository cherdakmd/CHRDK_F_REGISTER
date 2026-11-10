package ru.cherdakmd.chrdkregister.storage.db;

import ru.cherdakmd.chrdkregister.api.storage.StorageMode;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Конфигурация {@code plugins/CHRDK_Core/database.yml}. Парсер намеренно
 * минимальный: два уровня вложенности, комментарии и env-подстановки
 * {@code ${VAR}}. Реальные секреты в репозиторий и логи не попадают.
 */
public final class ChrdkDatabaseConfig {

    private static final Pattern ENV = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private final StorageMode mode;
    private final String host;
    private final int port;
    private final String database;
    private final String user;
    private final String password;
    private final boolean tls;
    private final int poolSize;
    private final int connectTimeoutMillis;
    private final int socketTimeoutMillis;

    private ChrdkDatabaseConfig(
            StorageMode mode,
            String host,
            int port,
            String database,
            String user,
            String password,
            boolean tls,
            int poolSize,
            int connectTimeoutMillis,
            int socketTimeoutMillis
    ) {
        this.mode = mode;
        this.host = host;
        this.port = port;
        this.database = database;
        this.user = user;
        this.password = password;
        this.tls = tls;
        this.poolSize = poolSize;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.socketTimeoutMillis = socketTimeoutMillis;
    }

    /** Отсутствующий файл означает безопасный легаси-режим с файлами на диске. */
    public static ChrdkDatabaseConfig load(Path file) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        if (Files.isRegularFile(file)) {
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String section = "";
                String line;
                while ((line = reader.readLine()) != null) {
                    String withoutComment = stripComment(line);
                    if (withoutComment.isBlank()) continue;
                    int indent = countIndent(withoutComment);
                    String body = withoutComment.trim();
                    int colon = body.indexOf(':');
                    if (colon < 0) throw new IOException("Некорректная строка database.yml: " + body);
                    String key = body.substring(0, colon).trim();
                    String raw = body.substring(colon + 1).trim();
                    if (indent == 0) section = "";
                    if (raw.isEmpty()) {
                        section = key;
                        continue;
                    }
                    String flatKey = section.isEmpty() ? key : section + "." + key;
                    values.put(flatKey, interpolate(unquote(raw)));
                }
            }
        }
        return from(values);
    }

    private static ChrdkDatabaseConfig from(Map<String, String> values) {
        String rawMode = values.getOrDefault("backend", "FILE").trim().toUpperCase(Locale.ROOT);
        StorageMode mode;
        try {
            mode = StorageMode.valueOf(rawMode);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Неизвестный backend: " + rawMode, error);
        }
        String host = values.getOrDefault("mysql.host", "");
        int port = parseInteger(values.getOrDefault("mysql.port", "3306"), "mysql.port", 1, 65535);
        String database = values.getOrDefault("mysql.database", "");
        String user = values.getOrDefault("mysql.user", "");
        String password = values.getOrDefault("mysql.password", "");
        boolean tls = Boolean.parseBoolean(values.getOrDefault("mysql.tls", "true"));
        int poolSize = parseInteger(values.getOrDefault("mysql.pool-size", "8"), "mysql.pool-size", 1, 64);
        int connect = parseInteger(values.getOrDefault("mysql.connect-timeout-ms", "5000"),
                "mysql.connect-timeout-ms", 100, 120_000);
        int socket = parseInteger(values.getOrDefault("mysql.socket-timeout-ms", "10000"),
                "mysql.socket-timeout-ms", 100, 600_000);
        if (mode == StorageMode.MYSQL) {
            requireEnv("mysql.host", host);
            requireEnv("mysql.database", database);
            requireEnv("mysql.user", user);
            requireEnv("mysql.password", password);
        }
        return new ChrdkDatabaseConfig(mode, host, port, database, user, password, tls,
                poolSize, connect, socket);
    }

    private static void requireEnv(String key, String value) {
        if (value == null || value.isBlank() || value.contains("${")) {
            throw new IllegalArgumentException("Для backend=MYSQL нужно задать " + key + " через env");
        }
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    private static int countIndent(String line) {
        int count = 0;
        while (count < line.length() && (line.charAt(count) == ' ' || line.charAt(count) == '\t')) count++;
        return count;
    }

    private static String unquote(String raw) {
        if (raw.length() >= 2 && ((raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"')
                || (raw.charAt(0) == '\'' && raw.charAt(raw.length() - 1) == '\''))) {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    private static String interpolate(String raw) {
        Matcher matcher = ENV.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = System.getenv(name);
            if (value == null) value = System.getProperty(name);
            if (value == null) value = matcher.group(0);
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static int parseInteger(String raw, String key, int min, int max) {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < min || value > max) throw new NumberFormatException("outside range");
            return value;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Некорректное целое " + key + ": " + raw, error);
        }
    }

    public StorageMode mode() {
        return mode;
    }

    public String jdbcUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC"
                + "&rewriteBatchedStatements=true&allowPublicKeyRetrieval=false";
    }

    public Properties connectionProperties() {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("useSSL", Boolean.toString(tls));
        properties.setProperty("requireSSL", Boolean.toString(tls));
        properties.setProperty("verifyServerCertificate", Boolean.toString(tls));
        properties.setProperty("connectTimeout", Integer.toString(connectTimeoutMillis));
        properties.setProperty("socketTimeout", Integer.toString(socketTimeoutMillis));
        properties.setProperty("characterEncoding", "utf8");
        properties.setProperty("connectionTimeZone", "UTC");
        return properties;
    }

    /** Тот же конфиг, но с принудительным MYSQL для офлайн-мигратора. */
    public ChrdkDatabaseConfig forMigration() {
        requireEnv("mysql.host", host);
        requireEnv("mysql.database", database);
        requireEnv("mysql.user", user);
        requireEnv("mysql.password", password);
        return new ChrdkDatabaseConfig(StorageMode.MYSQL, host, port, database, user, password,
                tls, poolSize, connectTimeoutMillis, socketTimeoutMillis);
    }

    public String user() {
        return user;
    }

    public String password() {
        return password;
    }

    public int poolSize() {
        return poolSize;
    }

    public int connectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public int socketTimeoutMillis() {
        return socketTimeoutMillis;
    }

    public boolean tls() {
        return tls;
    }

    /** Безопасная строка для лога: пароль и пользователь не печатаются. */
    public String redacted() {
        return "backend=" + mode + ", mysql=" + (mode == StorageMode.MYSQL
                ? host + ":" + port + "/" + database + ", tls=" + tls + ", pool=" + poolSize
                : "disabled");
    }

    @Override
    public String toString() {
        return redacted();
    }
}
