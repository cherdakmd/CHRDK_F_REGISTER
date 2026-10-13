package ru.cherdakmd.chrdkregister;

import org.json.JSONObject;

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

    /** Имена ключей в JSON + в config.yml (для подсказок админу). */
    public static final String KEY_ACCESS = "token";
    public static final String KEY_REFRESH = "refresh-token";
    public static final String KEY_DEVICE = "device-id";

    /** Чтение файла; отсутствие/битость — не ошибка (админ пересеет токены). */
    public synchronized void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            JSONObject json = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
            accessToken = json.optString("accessToken", "");
            refreshToken = json.optString("refreshToken", "");
            deviceId = json.optString("deviceId", "");
            savedAtMillis = json.optLong("savedAt", 0L);
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

        JSONObject json = new JSONObject();
        json.put("accessToken", accessToken.isBlank() ? JSONObject.NULL : accessToken);
        // refresh отдаём как есть — без него следующий. 401 рефрешевки уже не поможет
        json.put("refreshToken", refreshToken.isBlank() ? JSONObject.NULL : refreshToken);
        json.put("deviceId", deviceId.isBlank() ? JSONObject.NULL : deviceId);
        json.put("savedAt", savedAtMillis);

        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.createDirectories(file.getParent());
        Files.writeString(temp, json.toString(2), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
