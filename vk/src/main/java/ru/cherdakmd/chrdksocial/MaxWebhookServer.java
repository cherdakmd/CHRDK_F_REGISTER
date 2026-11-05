package ru.cherdakmd.chrdksocial;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Встроенный приёмник webhook'ов MAX на отдельном порту игрового хостинга.
 *
 * <p>Собственный минимальный HTTP/1.1 на {@link ServerSocket}: не требует
 * {@code jdk.httpserver} и гарантированно собирается с {@code --release 25}.
 * При наличии PKCS12-keystore поднимается {@link SSLServerSocket} (MAX с 25 мая
 * принимает webhook только по HTTPS с доверенным сертификатом). Проверка секрета
 * Constant-time, тело ограничено 64 КБ, ответ 200 отправляется до обработки,
 * чтобы уложиться в 30-секундный таймаут доставки MAX.</p>
 */
public final class MaxWebhookServer {

    private static final int MAX_HEADERS = 16 * 1024;
    private static final int MAX_BODY = 64 * 1024;
    private static final String[] SECRET_HEADERS = {
            "x-max-bot-api-secret-token", "x-max-secret", "x-webhook-secret"
    };

    private final String bindAddress;
    private final int configuredPort;
    private final String path;
    private final String secret;
    private final Path keystore;
    private final String keystorePassword;
    private final boolean requireTls;
    private final Consumer<JsonObject> dispatcher;
    private final Logger logger;
    private final ExecutorService workers;

    private volatile ServerSocket server;
    private volatile Thread acceptor;
    private volatile boolean running;
    private volatile int actualPort;
    private volatile boolean tls;

    public MaxWebhookServer(String bindAddress, int port, String path, String secret,
                            Path keystore, String keystorePassword, boolean requireTls,
                            Consumer<JsonObject> dispatcher, Logger logger) {
        this.bindAddress = bindAddress == null || bindAddress.isBlank() ? "0.0.0.0" : bindAddress;
        this.configuredPort = port;
        this.path = path == null || path.isBlank() ? "/max/webhook" : path;
        this.secret = secret == null ? "" : secret;
        this.keystore = keystore;
        this.keystorePassword = keystorePassword == null ? "" : keystorePassword;
        this.requireTls = requireTls;
        this.dispatcher = dispatcher;
        this.logger = logger;
        this.workers = new ThreadPoolExecutor(1, 2, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(128), runnable -> {
            Thread thread = new Thread(runnable, "CHRDK-MAX-Webhook");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    /** @return {@code true}, если сокет поднят. При отсутствии TLS-keystore — отказ, не HTTP. */
    public synchronized boolean start() {
        if (running) return true;
        ServerSocket socket = createSocket();
        if (socket == null) return false;
        server = socket;
        actualPort = socket.getLocalPort();
        running = true;
        acceptor = new Thread(this::acceptLoop, "CHRDK-MAX-Accept");
        acceptor.setDaemon(true);
        acceptor.start();
        logger.info("[CHRDK_SOCIAL] MAX webhook слушает " + bindAddress + ":" + actualPort
                + path + (tls ? " (HTTPS)" : " (HTTP, только для разработки)"));
        return true;
    }

    private ServerSocket createSocket() {
        try {
            InetAddress address = InetAddress.getByName(bindAddress);
            if (keystore != null && Files.isRegularFile(keystore)) {
                KeyStore store = KeyStore.getInstance("PKCS12");
                try (InputStream in = Files.newInputStream(keystore)) {
                    store.load(in, keystorePassword.toCharArray());
                }
                KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                factory.init(store, keystorePassword.toCharArray());
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(factory.getKeyManagers(), null, null);
                SSLServerSocketFactory socketFactory = context.getServerSocketFactory();
                SSLServerSocket ssl = (SSLServerSocket) socketFactory.createServerSocket(
                        configuredPort, 32, address);
                ssl.setNeedClientAuth(false);
                tls = true;
                return ssl;
            }
            if (requireTls) {
                logger.warning("[CHRDK_SOCIAL] MAX webhook требует HTTPS: положите PKCS12-keystore "
                        + (keystore == null ? "max-webhook.p12" : keystore.getFileName())
                        + " в папку плагина или включите max.polling.enabled.");
                return null;
            }
            tls = false;
            logger.warning("[CHRDK_SOCIAL] MAX webhook поднят по HTTP без TLS — только для локальной отладки.");
            return new ServerSocket(configuredPort, 32, address);
        } catch (Exception failure) {
            logger.log(Level.WARNING, "[CHRDK_SOCIAL] Не удалось открыть порт MAX webhook", failure);
            return null;
        }
    }

    public int port() {
        return actualPort;
    }

    public boolean isTls() {
        return tls;
    }

    public boolean isRunning() {
        return running && server != null && !server.isClosed();
    }

    private void acceptLoop() {
        while (running && server != null && !server.isClosed()) {
            try {
                Socket client = server.accept();
                client.setSoTimeout(10_000);
                workers.execute(() -> handleClient(client));
            } catch (Exception failure) {
                if (running) logger.fine("[CHRDK_SOCIAL] MAX webhook accept: " + failure.getClass().getSimpleName());
            }
        }
    }

    private void handleClient(Socket client) {
        try (Socket socket = client) {
            Request request = readRequest(socket.getInputStream());
            if (request == null) {
                respond(socket, 400, "bad request");
                return;
            }
            if (!"POST".equalsIgnoreCase(request.method()) || !path.equals(request.path())) {
                respond(socket, 404, "not found");
                return;
            }
            if (!secretMatches(request.providedSecret())) {
                respond(socket, 401, "unauthorized");
                return;
            }
            // Отвечаем сразу: MAX считает доставку неуспешной, если нет 200 за 30 секунд.
            respond(socket, 200, "ok");
            String body = request.body();
            workers.execute(() -> {
                try {
                    JsonObject update = JsonParser.parseString(body).getAsJsonObject();
                    dispatcher.accept(update);
                } catch (RuntimeException bad) {
                    logger.fine("[CHRDK_SOCIAL] MAX webhook: некорректный JSON обновления");
                }
            });
        } catch (Exception failure) {
            logger.fine("[CHRDK_SOCIAL] MAX webhook запрос отклонён: " + failure.getClass().getSimpleName());
        }
    }

    private boolean secretMatches(String provided) {
        if (secret.isEmpty()) return false;
        byte[] expected = secret.getBytes(StandardCharsets.UTF_8);
        byte[] actual = (provided == null ? "" : provided).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private static Request readRequest(InputStream in) throws Exception {
        ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
        int b;
        int state = 0; // 0 — ничего, 1 — было \r, 2 — было \r\n, 3 — было \r\n\r
        while ((b = in.read()) != -1) {
            headerBuffer.write(b);
            if (headerBuffer.size() > MAX_HEADERS) return null;
            byte current = (byte) b;
            if (current == '\r') {
                state = state == 2 ? 3 : 1;
            } else if (current == '\n' && state == 1) {
                state = 2;
            } else if (current == '\n' && state == 3) {
                break; // конец заголовков
            } else {
                state = 0;
            }
        }
        String rawHeaders = headerBuffer.toString(StandardCharsets.ISO_8859_1);
        String[] lines = rawHeaders.split("\r?\n");
        if (lines.length == 0) return null;
        String[] requestLine = lines[0].trim().split("\\s+");
        if (requestLine.length < 2) return null;
        int contentLength = 0;
        String providedSecret = "";
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon < 1) continue;
            String key = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            if (key.equals("content-length")) {
                try {
                    contentLength = Integer.parseInt(value);
                } catch (NumberFormatException bad) {
                    return null;
                }
            } else {
                for (String candidate : SECRET_HEADERS) {
                    if (key.equals(candidate)) providedSecret = value;
                }
            }
        }
        if (contentLength < 0 || contentLength > MAX_BODY) return null;
        byte[] bodyBytes = in.readNBytes(contentLength);
        if (bodyBytes.length != contentLength) return null;
        return new Request(requestLine[0], requestLine[1], providedSecret,
                new String(bodyBytes, StandardCharsets.UTF_8));
    }

    private static void respond(Socket socket, int status, String text) {
        try {
            OutputStream out = socket.getOutputStream();
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            String reason = status == 200 ? "OK" : status == 401 ? "Unauthorized"
                    : status == 404 ? "Not Found" : "Bad Request";
            String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                    + "Content-Type: text/plain; charset=UTF-8\r\n"
                    + "Content-Length: " + payload.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(payload);
            out.flush();
        } catch (Exception ignored) {
            // Клиент уже отключился; MAX повторит доставку.
        }
    }

    public synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
            // closing
        }
        if (acceptor != null) acceptor.interrupt();
        workers.shutdownNow();
    }

    private record Request(String method, String path, String providedSecret, String body) { }
}
