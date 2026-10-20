package ru.cherdakmd.chrdkstreams;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Опрос площадок и начисление проходки.
 *
 * <p>Работает в отдельном потоке: раз в {@code streams.poll-seconds} берёт все
 * привязки, спрашивает у площадок «идёт ли эфир», проверяет условия (подтверждён,
 * игрок онлайн, зрителей достаточно) и копит минуты. Когда минут набирается на
 * сутки — выдаёт их через API ядра.</p>
 *
 * <p>Защита от накрутки:</p>
 * <ul>
 *   <li>за один опрос начисляется не больше {@code max-credit-minutes};</li>
 *   <li>кредит идёт только за интервал между двумя <em>подтверждёнными</em>
 *       опросами: первый опрос после офлайна/паузы ничего не добавляет;</li>
 *   <li>дневной и общий потолки считаются в {@link StreamRewards}.</li>
 * </ul>
 */
final class StreamService {

    /** Источник в аудите ядра: по нему видно, за что выдана проходка. */
    static final String SOURCE = "streams";

    private static final DateTimeFormatter UNTIL_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final StreamPlugin plugin;
    private final StreamStore store;
    private final Logger logger;
    private final HttpClient http;

    /** Последний момент, когда эфир был подтверждён — копим только за этот интервал. */
    private final Map<UUID, Long> lastLive = new ConcurrentHashMap<>();
    private final AtomicBoolean polling = new AtomicBoolean(false);

    private volatile StreamConfig config;
    private volatile Map<StreamPlatform, StreamClient> clients = Map.of();
    private BukkitTask task;

    StreamService(StreamPlugin plugin, StreamStore store, StreamConfig config) {
        this.plugin = plugin;
        this.store = store;
        this.logger = plugin.getLogger();
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.clients = buildClients(config);
    }

    /* ------------------------------ жизненный цикл ------------------------------ */

    /** Применить новую конфигурацию: пересоздать клиентов и перезапустить таймер. */
    synchronized void update(StreamConfig newConfig) {
        this.config = newConfig;
        this.clients = buildClients(newConfig);
        this.lastLive.clear();
        restart();
    }

    synchronized void start() {
        restart();
    }

    synchronized void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        lastLive.clear();
    }

    private void restart() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        StreamConfig cfg = config;
        if (!cfg.usable()) {
            logger.info("[Streams] Опрос площадок не запущен: "
                    + (cfg.enabled() ? "нет настроенных платформ" : "программа выключена"));
            return;
        }
        long periodTicks = cfg.pollSeconds() * 20L;
        long firstDelay = Math.min(periodTicks, 20L * 30L);
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::pollSafely, firstDelay, periodTicks);
        logger.info("[Streams] Опрос запущен: " + StreamPlatform.ids(cfg.platforms()) + ", каждые "
                + cfg.pollSeconds() + " с, " + cfg.minutesPerDay() + " мин. = 1 сутки, "
                + "потолок " + cfg.maxDaysPerDay() + "/сутки.");
    }

    private Map<StreamPlatform, StreamClient> buildClients(StreamConfig cfg) {
        Map<StreamPlatform, StreamClient> result = new EnumMap<>(StreamPlatform.class);
        if (cfg.twitch().configured()) {
            result.put(StreamPlatform.TWITCH, new TwitchClient(http, logger, cfg.twitch()));
        }
        if (cfg.vkplay().configured()) {
            result.put(StreamPlatform.VKPLAY, new VkPlayClient(http, cfg.vkplay().apiUrl()));
        }
        return Map.copyOf(result);
    }

    /* -------------------------------- опрос -------------------------------- */

    private void pollSafely() {
        if (!polling.compareAndSet(false, true)) {
            return; // предыдущий опрос ещё не закончился — не наслаиваем
        }
        try {
            pollOnce();
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "[Streams] Ошибка опроса площадок", exception);
        } finally {
            polling.set(false);
        }
    }

    /** Один проход по всем привязкам. Пакетное сохранение — в конце. */
    void pollOnce() {
        StreamConfig cfg = config;
        Map<StreamPlatform, StreamClient> active = clients;
        if (!cfg.usable()) {
            return;
        }
        Collection<StreamBinding> snapshot = store.all();
        boolean dirty = false;
        for (StreamBinding binding : snapshot) {
            StreamClient client = active.get(binding.platform());
            if (client == null) {
                continue;
            }
            if (cfg.requireVerified() && !binding.verified()) {
                lastLive.remove(binding.uuid());
                continue;
            }
            Player online = Bukkit.getPlayer(binding.uuid());
            if (online == null && cfg.requireOnline()) {
                lastLive.remove(binding.uuid());
                continue;
            }
            LiveStream live;
            try {
                live = client.fetch(binding.channel());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | RuntimeException exception) {
                lastLive.remove(binding.uuid());
                logger.fine("[Streams] " + binding.platform().id() + "/" + binding.channel()
                        + ": " + exception.getMessage());
                continue;
            }
            if (!live.live() || live.viewers() < cfg.minViewers()) {
                lastLive.remove(binding.uuid());
                continue;
            }
            long now = System.currentTimeMillis();
            Long previous = lastLive.put(binding.uuid(), now);
            if (previous == null) {
                continue; // эфир только начался: первый интервал не считаем
            }
            long credit = Math.min(now - previous, cfg.maxCreditMillis());
            if (credit <= 0) {
                continue;
            }
            StreamRewards.Decision decision = StreamRewards.advance(
                    binding.state(), credit, now, cfg.rule());
            String name = online != null ? online.getName() : binding.playerName();
            if (decision.awarded() && !grant(binding, decision.awardDays(), online)) {
                // Награду выдать не удалось — состояние не сохраняем, повторим на следующем опросе.
                continue;
            }
            store.put(binding.withState(decision.state()).renamed(name));
            dirty = true;
            if (decision.awarded()) {
                announce(binding.uuid(), name, decision);
            }
        }
        if (dirty) {
            store.save();
        }
    }

    /** Выдаёт дни проходки через API ядра. {@code false} — не получилось, надо повторить. */
    private boolean grant(StreamBinding binding, int days, Player online) {
        ChrdkRegisterApi api = plugin.coreApi();
        if (api == null) {
            logger.warning("[Streams] Ядро CHRDK_Core недоступно — награда "
                    + binding.playerName() + " не выдана.");
            return false;
        }
        String name = online != null ? online.getName() : binding.playerName();
        try {
            api.grantPass(name, days, SOURCE);
            return true;
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "[Streams] Не удалось выдать " + days + " дн. игроку "
                    + name, exception);
            notifyError(binding.uuid(), exception.getMessage());
            return false;
        }
    }

    private void announce(UUID uuid, String name, StreamRewards.Decision decision) {
        StreamConfig cfg = config;
        if (!cfg.announce()) {
            return;
        }
        ChrdkRegisterApi api = plugin.coreApi();
        long until = api == null ? 0L : api.passUntil(name);
        int today = decision.state().awardedToday();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                return;
            }
            plugin.messages().send(player, "awarded",
                    "days", decision.awardDays(), "today", today, "max", cfg.maxDaysPerDay());
            if (until > 0) {
                plugin.messages().send(player, "awarded-until", "until", until(until));
            }
        });
    }

    private void notifyError(UUID uuid, String error) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                plugin.messages().send(player, "award-error", "error", error == null ? "" : error);
            }
        });
    }

    static String until(long millis) {
        return UNTIL_FORMAT.format(Instant.ofEpochMilli(millis));
    }

    /* ------------------------------- запросы из команд ------------------------------- */

    /**
     * Свежий снимок эфира — для {@code /stream check} и {@code /stream status}.
     *
     * @throws IOException платформа не ответила или не настроена
     */
    LiveStream fetchLive(StreamPlatform platform, String channel)
            throws IOException, InterruptedException {
        StreamClient client = clients.get(platform);
        if (client == null) {
            throw new IOException("платформа не настроена");
        }
        return client.fetch(channel);
    }

    /** Сбросить отметку «эфир подтверждён» — при смене или отвязке канала. */
    void forget(UUID uuid) {
        lastLive.remove(uuid);
    }

    StreamConfig config() {
        return config;
    }

    StreamStore store() {
        return store;
    }
}
