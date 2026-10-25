package ru.cherdakmd.chrdkregister;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Клиент к приватному веб-API Boosty (api.boosty.to/v1/…).
 *
 * <p>Публичного документированного API у Boosty нет — но API приложения стабильно
 * годами (на нём живут их же веб/мобильные клиенты). Авторизация — bearer-токен
 * ручной выгрузки из браузера, обновление через {@code POST /oauth/token/}
 * (grant_type=refresh_token + device_id). Boosty РОТИРУЕТ refresh при каждом
 * обновлении — свежая пара сразу сохраняется ({@link BoostyTokenStore}), иначе
 * перезапуск сервера убьёт сессию.</p>
 */
public final class BoostyClient {

    public static final String API_BASE = "https://api.boosty.to";
    public static final String SOURCE = "boosty";

    /** UA под официальные клиенты — WAF блочит «голые» java-агенты. */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36";

    private static final int PAGE_LIMIT = 300;
    private static final int MAX_PAGES = 20; // до 6000 подписчиков — с запасом

    private final BoostyTokenStore tokens;
    private final Logger logger;
    private PluginConfig.BoostySettings settings;

    public BoostyClient(PluginConfig config, BoostyTokenStore tokens, Logger logger) {
        this.tokens = tokens;
        this.logger = logger;
        this.settings = config.boosty();
    }

    public void updateConfig(PluginConfig newConfig) {
        this.settings = newConfig.boosty();
    }

    /** Один подписчик из выдачи /subscribers. */
    public record Subscriber(
            long id,
            String name,
            boolean subscribed,
            boolean blacklisted,
            String levelName,
            double levelPriceRub) {

        /** Активен с точки зрения автопродления проходки. */
        boolean countsForPass(double minimumPriceRub) {
            return subscribed && !blacklisted && levelPriceRub >= minimumPriceRub;
        }
    }

    /** Все подписчики блога с пагинацией (sort_by=on_time&order=gt — как в их вебе). */
    public List<Subscriber> fetchSubscribers() throws IOException {
        List<Subscriber> all = new ArrayList<>();
        int offset = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            String url = API_BASE + "/v1/blog/" + settings.blog()
                    + "/subscribers?sort_by=on_time&order=gt&offset=" + offset + "&limit=" + PAGE_LIMIT;
            String body = authedGet(url);
            JsonArray data = parseArray(body);
            for (JsonElement element : data) {
                all.add(parseSubscriber(element.getAsJsonObject()));
            }
            if (data.size() < PAGE_LIMIT) {
                break;
            }
            offset += data.size();
        }
        return all;
    }

    /** Жив ли API + токен: одна запись достаточна (для doctor/самотеста). */
    public boolean probe() {
        try {
            String url = API_BASE + "/v1/blog/" + settings.blog()
                    + "/subscribers?sort_by=on_time&order=gt&offset=0&limit=1";
            parseArray(authedGet(url));
            return true;
        } catch (Exception exception) {
            if (settings != null && settings.debugLog()) {
                logger.warning("[Boosty] probe НЕ прошёл: " + exception.getMessage());
            }
            return false;
        }
    }

    private static JsonArray parseArray(String body) throws IOException {
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        if (!json.has("data") || !json.get("data").isJsonArray()) {
            throw new IOException("Boosty: в ответе нет массива data");
        }
        return json.getAsJsonArray("data");
    }

    private static Subscriber parseSubscriber(JsonObject raw) {
        JsonObject level = raw.has("level") && raw.get("level").isJsonObject()
                ? raw.getAsJsonObject("level") : null;
        double price = 0.0;
        String levelName = "";
        if (level != null) {
            levelName = BoostyTokenStore.optString(level, "name", "");
            if (level.has("price") && level.get("price").isJsonPrimitive()) {
                price = level.get("price").getAsDouble();
            }
            if (level.has("currencyPrices") && level.get("currencyPrices").isJsonObject()) {
                JsonObject currency = level.getAsJsonObject("currencyPrices");
                if (currency.has("RUB") && currency.get("RUB").isJsonPrimitive()) {
                    price = currency.get("RUB").getAsDouble();
                }
            }
        }
        long id = raw.has("id") && raw.get("id").isJsonPrimitive() ? raw.get("id").getAsLong() : 0L;
        boolean subscribed = raw.has("subscribed") && raw.get("subscribed").getAsBoolean();
        boolean blacklisted = raw.has("isBlackListed") && raw.get("isBlackListed").getAsBoolean();
        return new Subscriber(id,
                BoostyTokenStore.optString(raw, "name", "").trim(),
                subscribed, blacklisted, levelName, price);
    }

    /* ------------------------------------------------------------------ *
     *  HTTP + авторизация
     * ------------------------------------------------------------------ */

    private String authedGet(String url) throws IOException {
        HttpResponse first = request("GET", url, null, tokens.accessToken());
        if (first.code == 401 || first.code == 403) {
            // токен прокис — пробуем один refresh и повторяем
            if (!refreshOnce()) {
                throw new IOException("Boosty HTTP " + first.code + " (токен устарел, refresh невозможен)");
            }
            HttpResponse second = request("GET", url, null, tokens.accessToken());
            if (second.code / 100 != 2) {
                throw new IOException("Boosty HTTP " + second.code + " после refresh");
            }
            return second.body;
        }
        if (first.code / 100 != 2) {
            throw new IOException("Boosty HTTP " + first.code);
        }
        return first.body;
    }

    /** POST /oauth/token/ с grant_type=refresh_token. true, если пара обновилась и сохранена. */
    private boolean refreshOnce() {
        if (!settings.enabled() || !tokens.canRefresh()) {
            return false;
        }
        try {
            String form = "grant_type=refresh_token"
                    + "&refresh_token=" + urlEncode(tokens.refreshToken())
                    + "&device_id=" + urlEncode(tokens.deviceId());
            HttpResponse response = request("POST", API_BASE + "/oauth/token/", form, null);
            if (response.code / 100 != 2) {
                logger.warning(String.format(Locale.ROOT,
                        "[Boosty] refresh токена отклонён (HTTP %d). Снимите новые токены из браузера.",
                        response.code));
                return false;
            }
            JsonObject json = JsonParser.parseString(response.body).getAsJsonObject();
            String access = BoostyTokenStore.optString(json, "access_token", "");
            String refresh = BoostyTokenStore.optString(json, "refresh_token", tokens.refreshToken());
            // device_id возвращается не всегда — если есть, обновляем
            String device = BoostyTokenStore.optString(json, "device_id", tokens.deviceId());
            if (access.isBlank()) {
                logger.warning("[Boosty] refresh-токен не вернул access_token.");
                return false;
            }
            tokens.save(access, refresh, device);
            logger.info("[Boosty] Токен обновлён и сохранён (boosty-tokens.json).");
            return true;
        } catch (Exception exception) {
            logger.warning("[Boosty] Ошибка refresh токена: " + exception.getMessage());
            return false;
        }
    }

    private static HttpResponse request(String method, String url, String formBody, String bearer)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");
            if (bearer != null && !bearer.isBlank()) {
                connection.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            if (formBody != null) {
                byte[] bytes = formBody.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            int code = connection.getResponseCode();
            java.io.InputStream stream = code < 400 ? connection.getInputStream() : connection.getErrorStream();
            String body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return new HttpResponse(code, body);
        } finally {
            connection.disconnect();
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record HttpResponse(int code, String body) {
    }
}
