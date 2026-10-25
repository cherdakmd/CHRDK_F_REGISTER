package ru.cherdakmd.chrdkregister;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

/**
 * Долговечное хранилище токенов Boosty (boosty-tokens.json в папке плагина).
 *
 * <p>Зачем отдельный файл: Boosty РОТИРУЕТ refresh-token при каждом обновлении,
 * поэтому новую пару нужно сразу записывать на диск, иначе сессия умрёт после
 * перезапуска сервера. config.yml остаётся «точкой посева» (стартовые токены),
 * а рабочий набор живёт здесь. Первый запуск с пустым файлом и заполненными
 * токенами в config.yml просто пересаживает их сюда.</p>
 */
public final class BoostyTokenStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Logger logger;

    private String accessToken = "";
    private String refreshToken = "";
    private String deviceId = "";
    private long savedAtMillis;

    public BoostyTokenStore(File dataFolder, Logger logger) {
        this.file = dataFolder.toPath().resolve("boosty-tokens.json");
        this.logger = logger;
    }

    /** Чтение файла; отсутствие/битость — не ошибка (админ пересеет токены). */
    public synchronized void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            accessToken = optString(json, "accessToken", "");
            refreshToken = optString(json, "refreshToken", "");
            deviceId = optString(json, "deviceId", "");
            savedAtMillis = json.has("savedAt") ? json.get("savedAt").getAsLong() : 0L;
        } catch (Exception exception) {
            logger.warning("[Boosty] Не удалось прочитать boosty-tokens.json: " + exception.getMessage());
        }
    }

    /** Высадить токены из конфига, если хранилище ещё пусто. */
    public synchronized void seedFrom(String access, String refresh, String device) {
        if (!accessToken.isBlank() || !refreshToken.isBlank()) {
            return; // уже есть рабочий набор — конфиг не трогаем
        }
        accessToken = access == null ? "" : access.trim();
        refreshToken = refresh == null ? "" : refresh.trim();
        deviceId = device == null ? "" : device.trim();
    }

    public synchronized boolean hasAccessToken() {
        return !accessToken.isBlank();
    }

    public synchronized boolean canRefresh() {
        return !refreshToken.isBlank() && !deviceId.isBlank();
    }

    public synchronized String accessToken() {
        return accessToken;
    }

    public synchronized String refreshToken() {
        return refreshToken;
    }

    public synchronized String deviceId() {
        return deviceId;
    }

    /** Зафиксировать свежую пару после /oauth/token/ (ротация!). Атомарная запись через temp-файл. */
    public synchronized void save(String newAccess, String newRefresh, String newDevice) throws IOException {
        accessToken = newAccess == null ? accessToken : newAccess.trim();
        refreshToken = newRefresh == null || newRefresh.isBlank() ? refreshToken : newRefresh.trim();
        deviceId = newDevice == null || newDevice.isBlank() ? deviceId : newDevice.trim();
        savedAtMillis = System.currentTimeMillis();

        JsonObject json = new JsonObject();
        if (!accessToken.isBlank()) {
            json.addProperty("accessToken", accessToken);
        }
        if (!refreshToken.isBlank()) {
            json.addProperty("refreshToken", refreshToken);
        }
        if (!deviceId.isBlank()) {
            json.addProperty("deviceId", deviceId);
        }
        json.addProperty("savedAt", savedAtMillis);

        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.createDirectories(file.getParent());
        Files.writeString(temp, GSON.toJson(json), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    static String optString(JsonObject json, String key, String fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsString();
        } catch (Exception exception) {
            return fallback;
        }
    }
}
