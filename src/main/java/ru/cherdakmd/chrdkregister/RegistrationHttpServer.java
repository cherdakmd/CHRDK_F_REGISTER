package ru.cherdakmd.chrdkregister;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small server-side registration site. Put it behind HTTPS (for example, Caddy). */
public final class RegistrationHttpServer {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long OAUTH_STATE_LIFETIME_MILLIS = Duration.ofMinutes(10).toMillis();

    private final ModConfig config;
    private final RegistrationStore store;
    private final TwitchApiClient twitch;
    private final AccessManager accessManager;
    private final Logger logger;
    private final ConcurrentHashMap<String, OAuthState> oauthStates = new ConcurrentHashMap<>();

    private volatile HttpServer httpServer;
    private volatile ExecutorService httpExecutor;

    public RegistrationHttpServer(ModConfig config, RegistrationStore store, TwitchApiClient twitch,
                                  AccessManager accessManager, Logger logger) {
        this.config = config;
        this.store = store;
        this.twitch = twitch;
        this.accessManager = accessManager;
        this.logger = logger;
    }

    public synchronized void start() {
        if (!config.webReady()) return;
        try {
            HttpServer created = HttpServer.create(new InetSocketAddress(config.bindAddress, config.webPort), 64);
            created.createContext("/register/twitch", this::handleTwitchPage);
            created.createContext("/register/donate", this::handleDonatePage);
            created.createContext("/oauth/twitch/start", this::handleTwitchStart);
            created.createContext("/oauth/twitch/callback", this::handleTwitchCallback);
            created.createContext("/health", this::handleHealth);
            created.createContext("/", this::handleNotFound);
            httpExecutor = Executors.newFixedThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "chrdk-f-register-http");
                thread.setDaemon(true);
                return thread;
            });
            created.setExecutor(httpExecutor);
            created.start();
            httpServer = created;
            logger.info("Registration web server listening on {}:{} (public URL: {})",
                    config.bindAddress, config.webPort, config.normalizedPublicBaseUrl());
        } catch (Exception exception) {
            logger.error("Could not start registration web server on {}:{}", config.bindAddress, config.webPort, exception);
            stop();
        }
    }

    public synchronized void stop() {
        HttpServer current = httpServer;
        httpServer = null;
        if (current != null) current.stop(0);
        ExecutorService executor = httpExecutor;
        httpExecutor = null;
        if (executor != null) executor.shutdownNow();
        oauthStates.clear();
    }

    public boolean isRunning() {
        return httpServer != null;
    }

    private void handleTwitchPage(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) return;
        if (!exchange.getRequestURI().getPath().equals("/register/twitch")) {
            sendHtml(exchange, 404, page("Ссылка недействительна", "<p>Запроси новую ссылку, попробовав зайти на сервер ещё раз.</p>"));
            return;
        }
        RegistrationStore.Ticket ticket = ticketFrom(exchange);
        if (ticket == null) {
            sendHtml(exchange, 410, page("Заявка истекла", "<p>Ссылка регистрации уже недействительна. Попробуй снова подключиться к серверу.</p>"));
            return;
        }

        String body = "<p>Игровой ник: <strong>" + escapeHtml(ticket.minecraftName) + "</strong></p>"
                + "<ol><li>Подпишись на Twitch-канал " + channelLink() + ".</li>"
                + "<li>Нажми кнопку ниже и разреши проверку подписки.</li>"
                + "<li>После подтверждения вернись в Minecraft и переподключись.</li></ol>"
                + (config.twitchReady()
                    ? "<a class=\"button\" href=\"" + escapeHtml(config.normalizedPublicBaseUrl()
                        + "/oauth/twitch/start?ticket=" + encode(ticket.token)) + "\">Проверить Twitch follow</a>"
                    : "<p class=\"notice\">Проверка Twitch ещё не настроена администратором.</p>");
        sendHtml(exchange, 200, page("Проходка через Twitch", body));
    }

    private void handleDonatePage(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) return;
        if (!exchange.getRequestURI().getPath().equals("/register/donate")) {
            sendHtml(exchange, 404, page("Ссылка недействительна", "<p>Запроси новую ссылку, попробовав зайти на сервер ещё раз.</p>"));
            return;
        }
        RegistrationStore.Ticket ticket = ticketFrom(exchange);
        if (ticket == null) {
            sendHtml(exchange, 410, page("Заявка истекла", "<p>Ссылка регистрации уже недействительна. Попробуй снова подключиться к серверу.</p>"));
            return;
        }

        String body = "<p>Игровой ник: <strong>" + escapeHtml(ticket.minecraftName) + "</strong></p>"
                + "<ol><li>Перейди на страницу DonatePay.</li>"
                + "<li>Сумма одного успешного доната — не меньше <strong>" + config.minimumDonationRubles + " ₽</strong>.</li>"
                + "<li>В поле имени донатера укажи точный Minecraft-ник: <strong>" + escapeHtml(ticket.minecraftName) + "</strong>.</li>"
                + "<li>После проверки платежа вернись в Minecraft и переподключись. Доступ выдаётся на "
                + config.grantDays + " дней.</li></ol>"
                + (validDonationUrl(config.donatePayPaymentUrl)
                    ? "<a class=\"button\" target=\"_blank\" rel=\"noopener noreferrer\" href=\""
                        + escapeHtml(config.donatePayPaymentUrl.trim()) + "\">Открыть DonatePay</a>"
                    : "<p class=\"notice\">Ссылка на страницу DonatePay ещё не настроена администратором.</p>")
                + (!config.donatePayReady() ? "<p class=\"notice\">Автоматическая проверка донатов ещё не настроена администратором.</p>" : "");
        sendHtml(exchange, 200, page("Проходка через DonatePay", body));
    }

    private void handleTwitchStart(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) return;
        if (!exchange.getRequestURI().getPath().equals("/oauth/twitch/start")) {
            sendHtml(exchange, 404, page("Страница не найдена", ""));
            return;
        }
        RegistrationStore.Ticket ticket = ticketFrom(exchange);
        if (ticket == null) {
            sendHtml(exchange, 410, page("Заявка истекла", "<p>Попробуй снова подключиться к серверу.</p>"));
            return;
        }
        if (!config.twitchReady()) {
            sendHtml(exchange, 503, page("Twitch не настроен", "<p>Администратор ещё не подключил Twitch API.</p>"));
            return;
        }

        purgeOldStates();
        String state = randomToken();
        oauthStates.put(state, new OAuthState(ticket.token, System.currentTimeMillis() + OAUTH_STATE_LIFETIME_MILLIS));
        redirect(exchange, twitch.authorizationUrl(state, callbackUrl()));
    }

    private void handleTwitchCallback(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) return;
        if (!exchange.getRequestURI().getPath().equals("/oauth/twitch/callback")) {
            sendHtml(exchange, 404, page("Страница не найдена", ""));
            return;
        }
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String stateValue = query.getOrDefault("state", "");
        OAuthState state = oauthStates.remove(stateValue);
        if (state == null || state.expiresAt() < System.currentTimeMillis()) {
            sendHtml(exchange, 410, page("Ссылка истекла", "<p>Вернись в Minecraft, повтори попытку входа и открой новую ссылку.</p>"));
            return;
        }
        if (query.containsKey("error")) {
            sendHtml(exchange, 400, page("Twitch не подтвердил вход", "<p>Авторизация была отменена. Можно вернуться и попробовать ещё раз.</p>"));
            return;
        }
        String code = query.getOrDefault("code", "");
        if (code.isBlank()) {
            sendHtml(exchange, 400, page("Не получен код Twitch", "<p>Повтори авторизацию по новой ссылке с сервера.</p>"));
            return;
        }

        String minecraftName = store.getTicket(state.ticketToken())
                .map(ticket -> ticket.minecraftName)
                .orElse("");
        if (minecraftName.isBlank()) {
            sendHtml(exchange, 410, page("Заявка истекла", "<p>Попробуй снова подключиться к серверу и пройти проверку.</p>"));
            return;
        }

        try {
            TwitchApiClient.TokenBundle tokens = twitch.exchangeAuthorizationCode(code, callbackUrl());
            TwitchApiClient.TwitchUser user = twitch.getAuthenticatedUser(tokens.accessToken());
            if (!twitch.followsConfiguredChannel(user.id(), tokens.accessToken())) {
                String link = config.normalizedPublicBaseUrl() + "/register/twitch?ticket=" + encode(state.ticketToken());
                sendHtml(exchange, 200, page("Подписка не найдена", "<p>Аккаунт Twitch <strong>"
                        + escapeHtml(user.login()) + "</strong> сейчас не подписан на нужный канал.</p>"
                        + "<p>Подпишись и затем снова нажми кнопку проверки.</p><a class=\"button\" href=\""
                        + escapeHtml(link) + "\">Вернуться к проверке</a>"));
                return;
            }
            if (!accessManager.grantTwitch(state.ticketToken(), user, tokens)) {
                sendHtml(exchange, 410, page("Заявка истекла", "<p>Попробуй снова подключиться к серверу и пройти проверку.</p>"));
                return;
            }
            sendHtml(exchange, 200, page("Готово!", "<p>Подписка подтверждена для ника <strong>"
                    + escapeHtml(minecraftName) + "</strong>.</p>"
                    + "<p>Whitelist активирован. Вернись в Minecraft и переподключись к серверу.</p>"));
        } catch (Exception exception) {
            logger.warn("Twitch OAuth/follow verification failed: {}", exception.getMessage());
            sendHtml(exchange, 502, page("Не удалось проверить подписку", "<p>Сервис Twitch временно недоступен или вернул ошибку. Попробуй позже.</p>"));
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) return;
        send(exchange, 200, "text/plain; charset=utf-8", "ok\n");
    }

    private void handleNotFound(HttpExchange exchange) throws IOException {
        sendHtml(exchange, 404, page("CHRDK Registration", "<p>Ссылка не найдена. Новую ссылку можно получить при попытке подключения к Minecraft-серверу.</p>"));
    }

    private RegistrationStore.Ticket ticketFrom(HttpExchange exchange) {
        String token = parseQuery(exchange.getRequestURI().getRawQuery()).getOrDefault("ticket", "");
        if (!token.matches("[A-Za-z0-9_-]{40,50}")) return null;
        return store.getTicket(token).orElse(null);
    }

    private String callbackUrl() {
        return config.normalizedPublicBaseUrl() + "/oauth/twitch/callback";
    }

    private String channelLink() {
        String url = config.twitchChannelUrl();
        if (url.isBlank()) return "каналу";
        return "<a href=\"" + escapeHtml(url) + "\" target=\"_blank\" rel=\"noopener noreferrer\">каналу Twitch</a>";
    }

    private void purgeOldStates() {
        long now = System.currentTimeMillis();
        oauthStates.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
    }

    private static boolean isGet(HttpExchange exchange) throws IOException {
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) return true;
        exchange.getResponseHeaders().set("Allow", "GET");
        send(exchange, 405, "text/plain; charset=utf-8", "Method not allowed\n");
        return false;
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        commonHeaders(exchange.getResponseHeaders());
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void sendHtml(HttpExchange exchange, int status, String html) throws IOException {
        send(exchange, status, "text/html; charset=utf-8", html);
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        commonHeaders(headers);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void commonHeaders(Headers headers) {
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> values = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return values;
        for (String part : rawQuery.split("&")) {
            int separator = part.indexOf('=');
            String key = separator < 0 ? part : part.substring(0, separator);
            String value = separator < 0 ? "" : part.substring(separator + 1);
            try {
                values.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed query parts.
            }
        }
        return values;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String page(String title, String body) {
        return """
                <!doctype html>
                <html lang="ru">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>%s — CHRDK</title>
                  <style>
                    :root { color-scheme: dark; font-family: Inter, system-ui, -apple-system, Segoe UI, sans-serif; }
                    * { box-sizing: border-box; }
                    body { margin: 0; min-height: 100vh; display: grid; place-items: center; padding: 24px; color: #eef2ff;
                      background: radial-gradient(circle at 15%% 10%%, #24344f 0, transparent 36%%), #0c1018; }
                    main { width: min(100%%, 620px); padding: clamp(24px, 5vw, 44px); border: 1px solid #283244; border-radius: 24px;
                      background: #121925; box-shadow: 0 24px 80px #0008; }
                    .brand { color: #93a4bf; font-size: 12px; font-weight: 800; letter-spacing: .18em; text-transform: uppercase; }
                    h1 { margin: 12px 0 22px; font-size: clamp(26px, 5vw, 38px); line-height: 1.1; }
                    p, li { color: #c6d0e1; line-height: 1.7; }
                    ol { padding-left: 22px; margin: 22px 0; }
                    strong { color: #fff; }
                    .button { display: inline-flex; align-items: center; justify-content: center; min-height: 48px; margin-top: 10px;
                      padding: 0 20px; border-radius: 12px; background: #9c6bff; color: #fff; font-weight: 800; text-decoration: none; }
                    .button:hover { background: #8754ef; }
                    .notice { padding: 12px 14px; border: 1px solid #674f24; border-radius: 12px; background: #2a2113; }
                    footer { margin-top: 20px; color: #66758d; font-size: 12px; text-align: center; }
                  </style>
                </head>
                <body><main><div class="brand">CHRDK · Minecraft registration</div><h1>%s</h1>%s</main></body>
                </html>
                """.formatted(escapeHtml(title), escapeHtml(title), body);
    }

    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static boolean validDonationUrl(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            URI uri = URI.create(value.trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private record OAuthState(String ticketToken, long expiresAt) { }
}
