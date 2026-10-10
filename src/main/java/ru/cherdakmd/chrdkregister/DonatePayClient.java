package ru.cherdakmd.chrdkregister;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads successful DonatePay transactions and extracts the donor's display name (Minecraft nickname). */
public final class DonatePayClient {
    private static final String API_URL = "https://donatepay.ru/api/v1/transactions";
    private static final DateTimeFormatter DONATEPAY_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private volatile PluginConfig config;
    private final HttpClient http;

    public DonatePayClient(PluginConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public void updateConfig(PluginConfig newConfig) {
        this.config = newConfig;
    }

    /** Reads the newest successful donations when establishing the initial cursor. */
    public List<Transaction> fetchRecentSuccessful(int limit) throws IOException {
        return requestTransactions(0, limit, true);
    }

    /** Reads successful donations with ID strictly greater than {@code id}. */
    public List<Transaction> fetchAfter(long id, int limit) throws IOException {
        return requestTransactions(id, limit, false);
    }

    private List<Transaction> requestTransactions(long afterId, int limit, boolean latestOnly) throws IOException {
        PluginConfig current = this.config;
        if (current == null || !current.donatePayReady()) return List.of();

        Map<String, String> query = new LinkedHashMap<>();
        query.put("access_token", current.donatePayApiToken());
        query.put("limit", Integer.toString(Math.clamp(limit, 1, 100)));
        query.put("order", latestOnly ? "DESC" : "ASC");
        query.put("type", "donation");
        query.put("status", "success");
        if (!latestOnly && afterId > 0) {
            query.put("after", Long.toString(afterId));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(API_URL + "?" + encodeQuery(query)))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET()
                .build();
        String responseBody = sendRequest(request);
        return parseTransactionsResponse(responseBody);
    }

    public static List<Transaction> parseTransactionsResponse(String responseBody) throws IOException {
        JsonObject root;
        try {
            JsonElement element = JsonParser.parseString(responseBody);
            if (!element.isJsonObject()) {
                throw new IOException("DonatePay returned a non-object JSON response");
            }
            root = element.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("DonatePay returned invalid JSON", exception);
        }

        if (!"success".equalsIgnoreCase(string(root, "status"))) {
            throw new IOException("DonatePay returned an unsuccessful API response");
        }
        JsonElement dataElement = root.get("data");
        if (dataElement == null || !dataElement.isJsonArray()) {
            throw new IOException("DonatePay response is missing the transactions array");
        }

        JsonArray data = dataElement.getAsJsonArray();
        List<Transaction> transactions = new ArrayList<>();
        for (JsonElement element : data) {
            if (!element.isJsonObject()) continue;
            JsonObject transaction = element.getAsJsonObject();
            long transactionId = number(transaction, "id", 0);
            String status = string(transaction, "status");
            String type = string(transaction, "type");
            JsonObject vars = object(transaction, "vars");
            String donorName = vars == null ? "" : string(vars, "name").trim();
            BigDecimal amount = decimal(transaction, "sum");
            long createdAt = parseCreatedAt(transaction.get("created_at"));
            if (transactionId > 0) {
                transactions.add(new Transaction(transactionId, donorName, amount, status, type, createdAt));
            }
        }
        transactions.sort(Comparator.comparingLong(Transaction::id));
        return transactions;
    }

    private String sendRequest(HttpRequest request) throws IOException {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("DonatePay returned HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("DonatePay request was interrupted", exception);
        }
    }

    private static long parseCreatedAt(JsonElement createdAtElement) {
        if (createdAtElement == null || createdAtElement.isJsonNull()) return 0;
        if (createdAtElement.isJsonPrimitive()) {
            return parseDateString(createdAtElement.getAsString().trim(), ZoneId.of("Europe/Moscow"));
        }
        if (!createdAtElement.isJsonObject()) return 0;
        JsonObject createdAt = createdAtElement.getAsJsonObject();
        String raw = string(createdAt, "date").trim();
        if (raw.isBlank()) return 0;
        String zoneName = string(createdAt, "timezone").trim();
        ZoneId zone;
        try {
            zone = zoneName.isBlank() ? ZoneId.of("Europe/Moscow") : ZoneId.of(zoneName);
        } catch (RuntimeException ignored) {
            zone = ZoneId.of("Europe/Moscow");
        }
        return parseDateString(raw, zone);
    }

    private static long parseDateString(String raw, ZoneId zone) {
        if (raw.isBlank()) return 0;
        try {
            return LocalDateTime.parse(raw, DONATEPAY_DATE).atZone(zone).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            try {
                // Handle fractional seconds like "2026-10-11 18:30:00.000000" from PHP DateTime serialization
                int dotIndex = raw.indexOf('.');
                if (dotIndex > 0) {
                    return LocalDateTime.parse(raw.substring(0, dotIndex), DONATEPAY_DATE)
                            .atZone(zone)
                            .toInstant()
                            .toEpochMilli();
                }
                return Instant.parse(raw).toEpochMilli();
            } catch (DateTimeParseException ignoredAgain) {
                return 0;
            }
        }
    }

    private static JsonObject object(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
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

    private static BigDecimal decimal(JsonObject object, String name) {
        try {
            JsonElement element = object.get(name);
            if (element == null || element.isJsonNull()) return BigDecimal.ZERO;
            return new BigDecimal(element.getAsString().trim().replace(',', '.'));
        } catch (RuntimeException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private static String encodeQuery(Map<String, String> values) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!result.isEmpty()) result.append('&');
            result.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            result.append('=');
            result.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return result.toString();
    }

    public record Transaction(long id, String donorName, BigDecimal amount, String status, String type, long createdAt) {
        public boolean isSuccessfulDonation() {
            return "success".equalsIgnoreCase(status)
                    && "donation".equalsIgnoreCase(type)
                    && createdAt > 0;
        }
    }
}
