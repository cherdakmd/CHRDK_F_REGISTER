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
public final class DonatePayClient implements DonationProvider {
    @Override
    public String key() {
        return RegistrationStore.DONATION_SOURCE_DONATEPAY;
    }

    @Override
    public String displayName() {
        return "DonatePay";
    }

    @Override
    public boolean ready() {
        PluginConfig current = this.config;
        return current != null && current.donatePayReady();
    }

    private static final String API_URL = "https://donatepay.ru/api/v1/transactions";
    private static final DateTimeFormatter DONATEPAY_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Правило DonatePay: любую API-операцию можно вызывать не чаще раза в 20 секунд
     * (+0,5 с запаса на джиттер сети и расхождение часов — иначе HTTP 429).
     */
    static final long API_MIN_INTERVAL_MILLIS = 20_500L;

    private volatile PluginConfig config;
    private final HttpClient http;
    private final ApiThrottle apiThrottle = new ApiThrottle(API_MIN_INTERVAL_MILLIS);

    public DonatePayClient(PluginConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public void updateConfig(PluginConfig newConfig) {
        this.config = newConfig;
    }

    /** Профиль кошелька для boot-проверки токена (GET /user по официальной спецификации). */
    public record UserInfo(String name, long balanceRubles) {
    }

    /**
     * Рукопожатие GET /api/v1/user: жив ли токен и чей это кошелёк. Дешёвый
     * способ дать владельцу сервера мгновенную обратную связь «DonatePay подключён»
     * прямо в консоли при старте — вместо тишины до первого доната.
     *
     * @throws IOException если API недоступен или ответил status != success (невалидный токен)
     */
    public UserInfo verifyToken() throws IOException {
        PluginConfig current = this.config;
        if (current == null || !current.donatePayReady()) {
            throw new IOException("DonatePay token is not configured");
        }
        URI uri = URI.create("https://donatepay.ru/api/v1/user?access_token="
                + URLEncoder.encode(current.donatePayApiToken(), StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET()
                .build();
        String responseBody = sendRequest(request);
        JsonObject root;
        try {
            JsonElement element = JsonParser.parseString(responseBody);
            if (!element.isJsonObject()) {
                throw new IOException("DonatePay /user вернул не-объект");
            }
            root = element.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("DonatePay /user вернул невалидный JSON", exception);
        }
        if (!"success".equalsIgnoreCase(string(root, "status"))) {
            String message = string(root, "message");
            throw new DonatePayException("DonatePay /user: status="
                    + string(root, "status") + (message.isBlank() ? "" : " (" + message + ")"),
                    DonatePayException.Kind.UNAUTHORIZED);
        }
        JsonObject data = object(root, "data");
        String name = data == null ? "" : string(data, "name").trim();
        long balance = data == null ? 0 : number(data, "balance", 0);
        return new UserInfo(name, balance);
    }

    /**
     * Официальный set_notification (POST /api/v1/notification): создаёт тестовую
     * запись о донате ПРЯМО в кошельке DonatePay. {@code notification=-1} — без
     * показа в публичном виджете алертов. Запись навсегда остаётся в истории
     * кошелька; светится ли она опросом /transactions как success — на совести API.
     *
     * @return компактный вердикт вида «success · Операция проведена успешно»
     * @throws IOException при сетевой ошибке или status != success
     */
    public String postNotification(String name, long sumRubles, String comment) throws IOException {
        PluginConfig current = this.config;
        if (current == null || !current.donatePayReady()) {
            throw new IOException("DonatePay token is not configured");
        }
        String date = java.time.LocalDateTime.now(java.time.ZoneId.of("Europe/Moscow"))
                .format(DONATEPAY_DATE) + ".000000";
        Map<String, String> query = new LinkedHashMap<>();
        query.put("access_token", current.donatePayApiToken());
        query.put("name", name == null ? "" : name);
        query.put("sum", Long.toString(Math.max(0, sumRubles)));
        query.put("comment", comment == null ? "" : comment);
        query.put("date", date);
        query.put("notification", "-1"); // не светим в публичном виджете стримера
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("https://donatepay.ru/api/v1/notification?" + encodeQuery(query)))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        String responseBody = sendRequest(request);
        try {
            JsonElement element = JsonParser.parseString(responseBody);
            if (element.isJsonObject()) {
                JsonObject root = element.getAsJsonObject();
                String status = string(root, "status");
                String result = string(root, "result");
                String message = string(root, "message");
                if ("success".equalsIgnoreCase(status) || "success".equalsIgnoreCase(result)) {
                    String verdict = "success".equalsIgnoreCase(status) ? status : result;
                    return verdict + (message.isBlank() ? "" : " · " + message);
                }
                throw new IOException("DonatePay set_notification: status=" + status
                        + (message.isBlank() ? "" : " — " + message));
            }
        } catch (IllegalStateException exception) {
            throw new IOException("DonatePay set_notification вернул невалидный JSON", exception);
        }
        return responseBody;
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
            String comment = vars == null ? "" : string(vars, "comment").trim();
            BigDecimal amount = decimal(transaction, "sum");
            long createdAt = parseCreatedAt(transaction.get("created_at"));
            if (transactionId > 0) {
                transactions.add(new Transaction(transactionId, donorName, amount, status, type,
                        createdAt, false, comment));
            }
        }
        transactions.sort(Comparator.comparingLong(Transaction::id));
        return transactions;
    }

    private String sendRequest(HttpRequest request) throws IOException {
        apiThrottle.await();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("DonatePay request was interrupted", exception);
        } catch (IOException exception) {
            throw new DonatePayException("DonatePay network error: " + exception.getMessage(),
                    DonatePayException.Kind.UNREACHABLE, exception);
        }
        if (response.statusCode() == 429) {
            // нас зарежали — отодвигаем следующий слот, чтобы повторы не добивали лимит
            apiThrottle.noteRateLimited();
            throw new DonatePayException("DonatePay returned HTTP 429", DonatePayException.Kind.RATE_LIMITED);
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new DonatePayException("DonatePay returned HTTP " + response.statusCode(),
                    DonatePayException.Kind.UNAUTHORIZED);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new DonatePayException("DonatePay returned HTTP " + response.statusCode(),
                    DonatePayException.Kind.BAD_RESPONSE);
        }
        return response.body();
    }

    /**
     * Ошибка обращения к DonatePay с разобранной причиной — AccessManager переводит
     * её в понятный вердикт админам («токен отозван» vs «сеть лежит» vs «лимит API»)
     * вместо голого текста исключения.
     */
    public static final class DonatePayException extends IOException {
        /** Категория отказа для диагностики. */
        public enum Kind {
            /** HTTP 429 — чаще раза в 20 с; вероятно, тем же токеном пользуется кто-то ещё. */
            RATE_LIMITED,
            /** HTTP 401/403 или status != success на GET /user — токен отклонён. */
            UNAUTHORIZED,
            /** HTTP-запрос вообще не доехал (сеть сервера / падение donatepay.ru). */
            UNREACHABLE,
            /** Нештатный HTTP-код (5xx и прочие сюрпризы). */
            BAD_RESPONSE,
            /** Всё остальное (битый JSON, прерванный поток, внутренние ошибки). */
            UNKNOWN
        }

        private final Kind kind;

        public DonatePayException(String message, Kind kind) {
            super(message);
            this.kind = kind;
        }

        public DonatePayException(String message, Kind kind, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }

    /**
     * Сколько миллисекунд осталось до ближайшего разрешённого окна API
     * (0 — можно звонить прямо сейчас). Для диагностики/GUI.
     */
    public long apiRateLimitWaitMillis() {
        return apiThrottle.waitMillis();
    }

    /**
     * Троттлер «не чаще одного HTTP-вызова в N мс». ВСЕ обращения к DonatePay идут
     * через {@link #sendRequest} → {@link ApiThrottle#await()}: плановый опрос,
     * {@code /pass recheck}, boot-проверка токена и обновление баланса из GUI
     * физически не могут долбить API чаще разрешённого окна. До появления троттлера
     * их наложение (recheck за пару секунд до планового опроса, boot-проверка и первый
     * опрос через 5 с) закономерно собирало HTTP 429 (Too Many Requests).
     */
    static final class ApiThrottle {
        private final long minIntervalMillis;
        private long nextSlotAtMillis;

        ApiThrottle(long minIntervalMillis) {
            this.minIntervalMillis = minIntervalMillis;
        }

        /** Сколько миллисекунд осталось до ближайшего разрешённого слота (0 — слот свободен). */
        synchronized long waitMillis() {
            return Math.max(0L, nextSlotAtMillis - System.currentTimeMillis());
        }

        /** Дожидается своего слота и сразу резервирует следующий; вызывается перед каждым HTTP-вызовом. */
        synchronized void await() throws IOException {
            long wait = waitMillis();
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("DonatePay request interrupted while awaiting the API rate-limit window",
                            exception);
                }
            }
            nextSlotAtMillis = System.currentTimeMillis() + minIntervalMillis;
        }

        /** Пришёл HTTP 429 — следующий слот считаем занятым ещё на полный интервал. */
        synchronized void noteRateLimited() {
            nextSlotAtMillis = Math.max(nextSlotAtMillis, System.currentTimeMillis() + minIntervalMillis);
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

    public record Transaction(long id, String donorName, BigDecimal amount, String status, String type,
                              long createdAt, boolean testPayment, String comment) {
        public Transaction(long id, String donorName, BigDecimal amount, String status,
                           String type, long createdAt) {
            this(id, donorName, amount, status, type, createdAt, false, "");
        }

        public Transaction(long id, String donorName, BigDecimal amount, String status,
                           String type, long createdAt, boolean testPayment) {
            this(id, donorName, amount, status, type, createdAt, testPayment, "");
        }

        public boolean isSuccessfulDonation() {
            return "success".equalsIgnoreCase(status)
                    && "donation".equalsIgnoreCase(type)
                    && createdAt > 0;
        }
    }
}
