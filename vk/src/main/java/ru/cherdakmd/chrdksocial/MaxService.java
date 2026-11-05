package ru.cherdakmd.chrdksocial;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import ru.cherdakmd.chrdkvk.VkPlugin;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Подсистема MAX внутри того же плагина, что и ВК: один JAR, один реестр кодов,
 * один конфиг. Webhook поднимается на отдельном порту хостинга; long polling —
 * fallback на время, пока нет HTTPS-сертификата.
 */
public final class MaxService implements MaxBotRouter.Backend {

    private static final long MIN_SEND_INTERVAL_MS = 550L; // лимит MAX: 2 сообщения/с в чат

    private final VkPlugin plugin;
    private final SocialService social;
    private volatile SocialConfig config;
    private final Map<Long, Long> lastOutbound = new ConcurrentHashMap<>();
    private final Map<Long, Long> bridgeCooldown = new ConcurrentHashMap<>();

    private volatile MaxApiClient client;
    private volatile MaxBotRouter router;
    private volatile MaxWebhookServer webhook;
    private volatile MaxLongPollWorker polling;

    public MaxService(VkPlugin plugin, SocialService social, SocialConfig config) {
        this.plugin = plugin;
        this.social = social;
        this.config = config;
        this.router = new MaxBotRouter(this);
    }

    public void start() {
        applyConfig(config);
    }

    /** Пересобирает транспорт после /social reload без раскачивания состояния привязок. */
    public synchronized void applyConfig(SocialConfig next) {
        this.config = next;
        stopTransport();
        if (!next.maxApiConfigured()) {
            plugin.getLogger().info("[CHRDK_SOCIAL] MAX Bot API не настроен: задайте max.access-token "
                    + "и chat_id из событий bot_added/bot_started.");
            return;
        }
        this.client = new MaxApiClient(next.maxApiBaseUrl(), next.maxAccessToken(),
                new MaxApiClient.HttpTransport());
        boolean webhookStarted = false;
        if (next.webhookEnabled() && next.webhookConfigured()) {
            webhook = new MaxWebhookServer(next.webhookBind(), next.webhookPort(),
                    next.webhookPath(), next.webhookSecret(),
                    plugin.getDataFolder().toPath().resolve(next.keystoreFile()),
                    next.keystorePassword(), next.requireTls(),
                    update -> dispatch(update, System.currentTimeMillis()), plugin.getLogger());
            webhookStarted = webhook.start();
            if (webhookStarted) subscribe(next);
            else webhook = null;
        }
        if (!webhookStarted && next.pollingEnabled()) {
            polling = new MaxLongPollWorker(client, next.pollingWaitSeconds(),
                    next.pollingReconnectSeconds(), next.pollingLimit(), this::dispatch, plugin.getLogger());
            polling.start();
            plugin.getLogger().info("[CHRDK_SOCIAL] MAX: включён Long Polling "
                    + "(webhook недоступен; для production нужен HTTPS).");
        }
        if (!webhookStarted && !next.pollingEnabled()) {
            plugin.getLogger().warning("[CHRDK_SOCIAL] Нет активного транспорта MAX: коды из MAX не принимаются.");
        }
    }

    private void subscribe(SocialConfig settings) {
        Thread thread = new Thread(() -> {
            try {
                boolean ok = client.setSubscription(settings.webhookPublicUrl(),
                        settings.webhookSecret(), settings.updateTypes());
                plugin.getLogger().info("[CHRDK_SOCIAL] MAX webhook подписка: " + (ok ? "OK" : "отклонена"));
            } catch (Exception failure) {
                plugin.getLogger().log(Level.WARNING,
                        "[CHRDK_SOCIAL] Не удалось зарегистрировать подписку MAX webhook", failure);
            }
        }, "CHRDK-MAX-Subscribe");
        thread.setDaemon(true);
        thread.start();
    }

    private void dispatch(JsonObject update, long now) {
        MaxBotRouter active = router;
        if (active != null) active.handle(update, now);
    }

    public boolean webhookActive() {
        MaxWebhookServer server = webhook;
        return server != null && server.isRunning();
    }

    public boolean pollingActive() {
        MaxLongPollWorker worker = polling;
        return worker != null && worker.isConnected();
    }

    public int webhookPort() {
        MaxWebhookServer server = webhook;
        return server == null ? 0 : server.port();
    }

    public boolean webhookTls() {
        MaxWebhookServer server = webhook;
        return server != null && server.isTls();
    }

    public void shutdown() {
        stopTransport();
    }

    private void stopTransport() {
        if (webhook != null) {
            webhook.stop();
            webhook = null;
        }
        if (polling != null) {
            polling.close();
            polling = null;
        }
    }

    /* ------------------------------ исходящие ------------------------------ */

    public boolean sendToChat(long chatId, String text) {
        MaxApiClient active = client;
        if (active == null || chatId <= 0L || text == null || text.isBlank()) return false;
        long now = System.currentTimeMillis();
        Long previous = lastOutbound.get(chatId);
        if (previous != null && now - previous < MIN_SEND_INTERVAL_MS) return false;
        lastOutbound.put(chatId, now);
        try {
            active.sendToChat(chatId, text);
            return true;
        } catch (Exception failure) {
            plugin.getLogger().fine("[CHRDK_SOCIAL] MAX send: " + failure.getClass().getSimpleName());
            return false;
        }
    }

    public boolean sendToUser(long userId, String text) {
        MaxApiClient active = client;
        if (active == null || userId <= 0L || text == null || text.isBlank()) return false;
        try {
            active.sendToUser(userId, text);
            return true;
        } catch (Exception failure) {
            plugin.getLogger().fine("[CHRDK_SOCIAL] MAX DM: " + failure.getClass().getSimpleName());
            return false;
        }
    }

    public boolean sendToBridge(String line) {
        SocialConfig settings = config;
        if (!settings.bridgeEnabled() || !settings.bridgeMcToMax()) return false;
        long chat = bridgeChatId();
        if (chat <= 0L) return false;
        int limit = settings.bridgeMaxLength();
        String safe = line.length() > limit ? line.substring(0, limit) : line;
        return sendToChat(chat, safe);
    }

    /* ------------------------------ MaxBotRouter.Backend ------------------------------ */

    @Override
    public String onCode(String senderKey, long userId, String code, long nowMillis) {
        return social.handleIncomingCode(code, SocialService.PROVIDER_MAX, userId,
                Long.toString(userId), senderKey, nowMillis, true);
    }

    @Override
    public void onChatDiscovered(long chatId, String chatType, long nowMillis) {
        social.store().recordChat(Long.toString(chatId), nowMillis);
        social.audit().write("social.max-chat-discovered", "", SocialService.PROVIDER_MAX,
                "chat=" + chatId + ", type=" + chatType);
    }

    @Override
    public void onBridgeMessage(long chatId, long userId, String senderName, String text, long nowMillis) {
        SocialConfig settings = config;
        ChrdkRegisterApi api = plugin.coreApi();
        if (api == null || !settings.bridgeEnabled() || !settings.bridgeMaxToMc()) return;
        int cooldown = settings.bridgeCooldownSeconds() * 1_000;
        Long previous = bridgeCooldown.get(userId);
        if (cooldown > 0 && previous != null && nowMillis - previous < cooldown) return;
        bridgeCooldown.put(userId, nowMillis);
        Optional<String> owner = social.store().ownerOf(SocialService.PROVIDER_MAX, userId);
        String linked = owner.orElse("");
        if (settings.bridgeRequireLinked() && linked.isEmpty()) {
            sendToUser(userId, "Напишите в игре /link и отправьте код сюда, чтобы писать в чат сервера.");
            return;
        }
        int limit = settings.bridgeMaxLength();
        String safe = text.length() > limit ? text.substring(0, limit) : text;
        Bukkit.getScheduler().runTask(plugin, () -> api.deliverExternalGlobalChat(
                "max", settings.bridgeBadge(), linked, senderName, safe));
    }

    @Override
    public String onCallback(String senderKey, long userId, String payload, long nowMillis) {
        String command = payload == null ? "" : payload.trim().toLowerCase(java.util.Locale.ROOT);
        if (command.equals("start") || command.equals("/start")) {
            return """
                    👋 CHERDAK: вход на сервер через MAX или ВКонтакте.
                    • Зайдите на сервер, в чате появится одноразовый код.
                    • Отправьте код сюда одним сообщением — ограничения снимутся без релога.
                    • /status — статус привязки, /link — подсказка.
                    🔒 Не отправляйте боту пароль или код 2FA: они здесь не меняются.
                    """;
        }
        if (command.equals("link") || command.equals("/link") || command.equals("код")) {
            return "Код выдаётся в игре командой /link (или сразу при входе). Отправьте его одним сообщением.";
        }
        if (command.equals("status") || command.equals("/status")) {
            Optional<String> owner = social.store().ownerOf(SocialService.PROVIDER_MAX, userId);
            return owner.map(name -> "Привязан аккаунт: " + name + "\n" + social.status(name))
                    .orElse("Аккаунт ещё не привязан. Зайдите на сервер и отправьте код из чата.");
        }
        return "Неизвестная команда. Доступно: /start, /link, /status.";
    }

    @Override
    public void sendDirectReply(long userId, String text) {
        sendToUser(userId, text);
    }

    @Override
    public long bridgeChatId() {
        long stored = social.store().chatId("bridge");
        return stored > 0L ? stored : config.maxChatId();
    }
}
