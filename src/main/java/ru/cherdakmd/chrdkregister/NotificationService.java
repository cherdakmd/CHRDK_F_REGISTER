package ru.cherdakmd.chrdkregister;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends important events to a Discord webhook (plain HTTPS, no external dependencies).
 *
 * <p>Messages are queued and delivered from a single background thread. Identical events are throttled so a
 * brute-force storm cannot flood the channel, and failures never propagate into gameplay code.</p>
 */
public final class NotificationService {
    private static final long THROTTLE_MILLIS = 3_000L;

    /** Payload colors per event severity. */
    private static final int COLOR_GOOD = 0x57F287;
    private static final int COLOR_WARN = 0xFEE75C;
    private static final int COLOR_BAD = 0xED4245;
    private static final int COLOR_INFO = 0x5865F2;

    private final Supplier<PluginConfig> configSupplier;
    private final Logger logger;
    private final HttpClient http;
    private final ExecutorService worker;
    private final Map<String, Long> lastSentAt = new ConcurrentHashMap<>();
    private final Map<String, Integer> dropped = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    public NotificationService(Supplier<PluginConfig> configSupplier, Logger logger) {
        this.configSupplier = configSupplier;
        this.logger = logger;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "CHRDK-notifications");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Queues a notification.
     *
     * @param event event key from {@code notifications.events}, e.g. {@code pass.granted}
     * @param title Discord embed title
     * @param description body text (already localized)
     * @param severity one of {@code good}, {@code warn}, {@code bad}, {@code info}
     */
    public void notify(String event, String title, String description, String severity) {
        PluginConfig config = configSupplier.get();
        if (!config.discordEnabled() || !config.notifyOn(event)) return;
        long now = System.currentTimeMillis();
        Long previous = lastSentAt.get(event);
        if (previous != null && now - previous < THROTTLE_MILLIS) {
            dropped.merge(event, 1, Integer::sum);
            return;
        }
        lastSentAt.put(event, now);
        int suppressed = dropped.getOrDefault(event, 0);
        dropped.remove(event);
        String body = suppressed > 0 ? description + "\n(пропущено событий: " + suppressed + ")" : description;
        String webhook = config.discordWebhookUrl();
        worker.execute(() -> send(webhook, title, body, severity, config.notificationMentions()));
    }

    /** Convenience helper mapping audit event names to notification severities. */
    public static String severityFor(String event) {
        if (event == null) return "info";
        return switch (event) {
            case "pass.granted", "donation.approved" -> "good";
            case "bruteforce.alert", "pass.revoked", "totp.reset", "password.reset", "multialt.alert" -> "warn";
            case "donatepay.error", "storage.error", "donatepay.down" -> "bad";
            case "donatepay.up" -> "good";
            default -> "info";
        };
    }

    private void send(String webhookUrl, String title, String description, String severity, java.util.List<String> mentions) {
        try {
            JsonObject embed = new JsonObject();
            embed.addProperty("title", title == null ? "CHRDK_Core" : title);
            embed.addProperty("description", description == null ? "" : description);
            embed.addProperty("color", colorFor(severity));
            embed.addProperty("timestamp", Instant.now().toString());

            JsonObject payload = new JsonObject();
            JsonArray embeds = new JsonArray();
            embeds.add(embed);
            payload.add("embeds", embeds);
            if (mentions != null && !mentions.isEmpty()) {
                StringBuilder content = new StringBuilder();
                for (String mention : mentions) {
                    if (mention == null || mention.isBlank()) continue;
                    if (!content.isEmpty()) content.append(' ');
                    content.append(mention);
                }
                if (!content.isEmpty()) {
                    payload.addProperty("content", content.toString());
                    JsonObject allowed = new JsonObject();
                    JsonArray parse = new JsonArray();
                    parse.add("roles");
                    allowed.add("parse", parse);
                    payload.add("allowed_mentions", allowed);
                }
            }

            HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "CHRDK_Core")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                // Rate limited: retry once after the advertised delay.
                long retryAfter = 1_000L;
                String body = response.body();
                if (body != null && body.contains("retry_after")) {
                    try {
                        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
                        if (json.has("retry_after")) {
                            retryAfter = (long) (json.get("retry_after").getAsDouble() * 1000.0) + 250L;
                        }
                    } catch (RuntimeException ignored) {
                        // Keep the default delay.
                    }
                }
                Thread.sleep(Math.min(retryAfter, 5_000L));
                http.send(request, HttpResponse.BodyHandlers.ofString());
            } else if (response.statusCode() < 200 || response.statusCode() >= 300) {
                logger.warning("Discord webhook returned HTTP " + response.statusCode()
                        + " — notification dropped.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            logger.log(Level.FINE, "Could not deliver a Discord notification", exception);
        }
    }

    private static int colorFor(String severity) {
        return switch (severity == null ? "info" : severity.toLowerCase(java.util.Locale.ROOT)) {
            case "good" -> COLOR_GOOD;
            case "warn" -> COLOR_WARN;
            case "bad" -> COLOR_BAD;
            default -> COLOR_INFO;
        };
    }

    /** Size of the pending queue (diagnostics only). */
    public int queueSize() {
        return ((java.util.concurrent.ThreadPoolExecutor) worker).getQueue().size();
    }

    public void shutdown() {
        running = false;
        worker.shutdown();
        try {
            if (!worker.awaitTermination(3, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
    }

    /** True while the dispatcher accepts new notifications. */
    public boolean running() {
        return running;
    }
}
