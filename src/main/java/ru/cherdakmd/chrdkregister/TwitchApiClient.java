package ru.cherdakmd.chrdkregister;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Twitch OAuth and Helix calls. Viewer tokens are only kept server-side for rechecking follows. */
public final class TwitchApiClient {
    private static final String OAUTH_BASE = "https://id.twitch.tv/oauth2";
    private static final String HELIX_BASE = "https://api.twitch.tv/helix";
    private static final String FOLLOW_SCOPE = "user:read:follows";

    private final ModConfig config;
    private final HttpClient http;
    private volatile String cachedChannelId = "";
    private volatile String cachedAppToken = "";
    private volatile long cachedAppTokenExpiresAt;

    public TwitchApiClient(ModConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public String authorizationUrl(String state, String redirectUri) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("client_id", config.twitchClientId.trim());
        values.put("redirect_uri", redirectUri);
        values.put("response_type", "code");
        values.put("scope", FOLLOW_SCOPE);
        values.put("state", state);
        values.put("force_verify", "true");
        return OAUTH_BASE + "/authorize?" + encodeQuery(values);
    }

    public TokenBundle exchangeAuthorizationCode(String code, String redirectUri) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("client_id", config.twitchClientId.trim());
        values.put("client_secret", config.twitchClientSecret.trim());
        values.put("code", code);
        values.put("grant_type", "authorization_code");
        values.put("redirect_uri", redirectUri);
        return tokenRequest(values, null);
    }

    public TwitchUser getAuthenticatedUser(String accessToken) throws IOException {
        JsonObject root = helixGet("/users", "", accessToken);
        JsonArray data = array(root, "data");
        if (data.isEmpty() || !data.get(0).isJsonObject()) {
            throw new IOException("Twitch did not return the authenticated user");
        }
        JsonObject user = data.get(0).getAsJsonObject();
        String id = string(user, "id");
        String login = string(user, "login");
        if (id.isBlank() || login.isBlank()) throw new IOException("Twitch user response is missing an id or login");
        return new TwitchUser(id, login);
    }

    public boolean followsConfiguredChannel(String userId, String accessToken) throws IOException {
        String channelId = getChannelId();
        String query = "?user_id=" + encode(userId) + "&broadcaster_id=" + encode(channelId);
        JsonObject root = helixGet("/channels/followed", query, accessToken);
        JsonArray data = array(root, "data");
        for (JsonElement element : data) {
            if (!element.isJsonObject()) continue;
            if (channelId.equals(string(element.getAsJsonObject(), "broadcaster_id"))) return true;
        }
        return false;
    }

    public FollowCheck refreshAndCheck(RegistrationStore.Member member) throws IOException {
        TokenBundle tokens = new TokenBundle(
                member.twitchAccessToken,
                member.twitchRefreshToken,
                member.twitchAccessExpiresAt
        );
        if (tokens.accessToken().isBlank() || tokens.refreshToken().isBlank()) {
            throw new IOException("Stored Twitch authorization is missing");
        }

        if (tokens.expiresAt() <= System.currentTimeMillis() + Duration.ofMinutes(1).toMillis()) {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("client_id", config.twitchClientId.trim());
            values.put("client_secret", config.twitchClientSecret.trim());
            values.put("grant_type", "refresh_token");
            values.put("refresh_token", tokens.refreshToken());
            tokens = tokenRequest(values, tokens.refreshToken());
        }

        boolean following = followsConfiguredChannel(member.twitchUserId, tokens.accessToken());
        return new FollowCheck(following, tokens);
    }

    private TokenBundle tokenRequest(Map<String, String> values, String previousRefreshToken) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(OAUTH_BASE + "/token"))
                .timeout(Duration.ofSeconds(12))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(encodeQuery(values), StandardCharsets.UTF_8))
                .build();
        JsonObject root = sendJson(request, "Twitch OAuth");
        String accessToken = string(root, "access_token");
        String refreshToken = string(root, "refresh_token");
        if (refreshToken.isBlank()) refreshToken = previousRefreshToken == null ? "" : previousRefreshToken;
        long expiresIn = number(root, "expires_in", 0);
        if (accessToken.isBlank() || expiresIn <= 0) throw new IOException("Twitch OAuth response is missing a token or expiry");
        return new TokenBundle(accessToken, refreshToken, System.currentTimeMillis() + expiresIn * 1000L);
    }

    private String getChannelId() throws IOException {
        if (config.twitchChannelId != null && !config.twitchChannelId.isBlank()) return config.twitchChannelId.trim();
        String current = cachedChannelId;
        if (!current.isBlank()) return current;

        String token = getAppToken();
        String query = "?login=" + encode(config.twitchChannelLogin.trim().toLowerCase());
        JsonObject root = helixGet("/users", query, token);
        JsonArray data = array(root, "data");
        if (data.isEmpty() || !data.get(0).isJsonObject()) {
            throw new IOException("Could not resolve the configured Twitch channel login");
        }
        String id = string(data.get(0).getAsJsonObject(), "id");
        if (id.isBlank()) throw new IOException("Twitch channel response is missing an id");
        cachedChannelId = id;
        return id;
    }

    private String getAppToken() throws IOException {
        if (!cachedAppToken.isBlank() && cachedAppTokenExpiresAt > System.currentTimeMillis() + 30_000L) {
            return cachedAppToken;
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("client_id", config.twitchClientId.trim());
        values.put("client_secret", config.twitchClientSecret.trim());
        values.put("grant_type", "client_credentials");
        TokenBundle token = tokenRequest(values, null);
        cachedAppToken = token.accessToken();
        cachedAppTokenExpiresAt = token.expiresAt();
        return cachedAppToken;
    }

    private JsonObject helixGet(String path, String query, String accessToken) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(HELIX_BASE + path + query))
                .timeout(Duration.ofSeconds(12))
                .header("Client-Id", config.twitchClientId.trim())
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET()
                .build();
        return sendJson(request, "Twitch Helix");
    }

    private JsonObject sendJson(HttpRequest request, String service) throws IOException {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException(service + " returned HTTP " + response.statusCode());
            }
            JsonElement element = JsonParser.parseString(response.body());
            if (!element.isJsonObject()) throw new IOException(service + " returned a non-object JSON response");
            return element.getAsJsonObject();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(service + " request was interrupted", exception);
        } catch (RuntimeException exception) {
            throw new IOException(service + " returned invalid JSON", exception);
        }
    }

    private static JsonArray array(JsonObject object, String name) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonArray()) throw new IOException("Twitch response is missing " + name);
        return element.getAsJsonArray();
    }

    private static String string(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static long number(JsonObject object, String name, long fallback) {
        try {
            JsonElement element = object.get(name);
            return element == null || element.isJsonNull() ? fallback : element.getAsLong();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String encodeQuery(Map<String, String> values) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!result.isEmpty()) result.append('&');
            result.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return result.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record TwitchUser(String id, String login) { }
    public record TokenBundle(String accessToken, String refreshToken, long expiresAt) { }
    public record FollowCheck(boolean following, TokenBundle tokens) { }
}
