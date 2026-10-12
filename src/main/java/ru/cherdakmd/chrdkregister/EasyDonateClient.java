package ru.cherdakmd.chrdkregister;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Клиент EasyDonate API v3 (https://docs.easydonate.ru): список успешных платежей
 * магазина через заголовок {@code Shop-Key}. Зеркалит поведение DonatePay-клиента —
 * поэтому выдача, очередь и журнал работают с обоими сервисами одинаково.
 */
public final class EasyDonateClient implements DonationProvider {

    private static final String API_URL = "https://easydonate.ru/api/v3/shop/payments";
    public static final String SOURCE = "easydonate";

    private volatile PluginConfig config;
    private final HttpClient http;

    public EasyDonateClient(PluginConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String key() {
        return SOURCE;
    }

    @Override
    public String displayName() {
        return "EasyDonate";
    }

    @Override
    public boolean ready() {
        PluginConfig current = this.config;
        return current != null && current.easyDonateReady();
    }

    @Override
    public void updateConfig(PluginConfig newConfig) {
        this.config = newConfig;
    }

    @Override
    public List<DonatePayClient.Transaction> fetchRecentSuccessful(int limit) throws IOException {
        // у ED «короткая голова» = те же 50 последних платежей — лимит соблюдём выборкой
        List<DonatePayClient.Transaction> all = fetchPayments();
        if (all.size() > limit) {
            return new ArrayList<>(all.subList(all.size() - limit, all.size()));
        }
        return all;
    }

    @Override
    public List<DonatePayClient.Transaction> fetchAfter(long cursorId, int limit) throws IOException {
        List<DonatePayClient.Transaction> all = fetchPayments();
        List<DonatePayClient.Transaction> result = new ArrayList<>();
        for (DonatePayClient.Transaction transaction : all) {
            if (transaction.id() > cursorId) {
                result.add(transaction);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    private List<DonatePayClient.Transaction> fetchPayments() throws IOException {
        PluginConfig current = this.config;
        if (current == null || !current.easyDonateReady()) {
            return List.of();
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Shop-Key", current.easyDonateShopKey())
                .header("Accept", "application/json")
                .header("User-Agent", "CHRDK_F_REGISTER")
                .GET()
                .build();
        String body;
        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("EasyDonate returned HTTP " + response.statusCode());
            }
            body = response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("EasyDonate request was interrupted", exception);
        }
        return parsePaymentsResponse(body, current.timeZone());
    }

    /**
     * Разбор ответа {@code GET /api/v3/shop/payments}: берём только оплаченные
     * (status == 2), ник — из {@code customer}, сумма — {@code enrolled}
     * (начисленная сервисом, после комиссии). Отсортировано по возрастанию id.
     */
    public static List<DonatePayClient.Transaction> parsePaymentsResponse(String responseBody,
                                                                          java.time.ZoneId timezone)
            throws IOException {
        JsonObject root;
        try {
            JsonElement element = JsonParser.parseString(responseBody);
            if (!element.isJsonObject()) {
                throw new IOException("EasyDonate returned a non-object JSON response");
            }
            root = element.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("EasyDonate returned invalid JSON", exception);
        }

        if (!root.has("success") || !root.get("success").getAsBoolean()) {
            String error = root.has("response") && root.get("response").isJsonPrimitive()
                    ? root.get("response").getAsString() : "unknown";
            throw new IOException("EasyDonate returned an unsuccessful API response: " + error);
        }
        JsonElement responseElement = root.get("response");
        if (responseElement == null || !responseElement.isJsonArray()) {
            throw new IOException("EasyDonate response is missing the payments array");
        }

        List<DonatePayClient.Transaction> transactions = new ArrayList<>();
        for (JsonElement element : responseElement.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject payment = element.getAsJsonObject();
            long id = optLong(payment, "id", 0);
            int status = (int) optLong(payment, "status", 0);
            if (id <= 0 || status != 2) {
                continue; // охотимся только на реально оплаченные
            }
            String customer = payment.has("customer") && payment.get("customer").isJsonPrimitive()
                    ? payment.get("customer").getAsString().trim() : "";
            java.math.BigDecimal amount = DonationProvider.parseAmountBigDecimal(payment.get("enrolled"));
            String createdRaw = payment.has("created_at") && payment.get("created_at").isJsonPrimitive()
                    ? payment.get("created_at").getAsString() : "";
            long createdAt = DonationProvider.parseServiceTimestamp(createdRaw, timezone);
            String paymentType = payment.has("payment_type") && payment.get("payment_type").isJsonPrimitive()
                    ? payment.get("payment_type").getAsString().trim() : "";
            transactions.add(new DonatePayClient.Transaction(
                    id, customer, amount, "success", "donation", createdAt,
                    "test".equalsIgnoreCase(paymentType)));
        }
        transactions.sort(Comparator.comparingLong(DonatePayClient.Transaction::id));
        return transactions;
    }

    private static long optLong(JsonObject object, String key, long fallback) {
        try {
            JsonElement element = object.get(key);
            return element == null || !element.isJsonPrimitive()
                    ? fallback : Long.parseLong(element.getAsString().trim());
        } catch (RuntimeException exception) {
            return fallback;
        }
    }
}
