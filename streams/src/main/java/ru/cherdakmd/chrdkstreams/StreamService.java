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
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Опрос площадок и начисление ранга.
 *
 * <p>Работает в отдельном потоке: раз в {@code streams.poll-seconds} берёт все
 * привязки, спрашивает у площадок «идёт ли эфир», проверяет условия (подтверждён,
 * игрок онлайн, зрителей достаточно) и копит минуты. Когда минут набирается на
 * час буста — выдаёт его через API ядра.</p>
 *
 * <p>Защита от накрутки:</p>
 * <ul>
 *   <li>за один опрос начисляется не больше {@code max-credit-minutes};</li>
 *   <li>кредит идёт только за интервал между двумя <em>подтверждёнными</em>
 *       опросами: первый опрос после офлайна/паузы ничего не добавляет;</li>
 *   <li>дневной и общий потолки считаются в {@link StreamRewards}.</li>
 * </ul>
 *
 * <p>С 1.4.122 подтверждение канала не обязательно запускать вручную: если
 * включён {@code streams.verify-on-poll}, опрос сам замечает код в названии
 * эфира и подтверждает канал. Там же заполняется {@link ProbeCache} — по нему
 * {@code /stream status} отвечает без лишних запросов к площадкам.</p>
 */
final class StreamService {

    /** Источник в аудите ядра: по нему видно, за что выдана ранг. */
    static final String SOURCE = "streams";

    private static final DateTimeFormatter UNTIL_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final StreamPlugin plugin;
    private final StreamStore store;
    private final Logger logger;
    private final HttpClient http;

    /** Последний момент, когда эфир был подтверждён — копим только за этот интервал. */
    private final Map<UUID, Long> lastLive = new ConcurrentHashMap<>();
    /** Последние ответы площадок: /stream status не должен ждать сеть каждый раз. */
    private final ProbeCache cache = new ProbeCache();
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
        cache.clear();
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
                + cfg.pollSeconds() + " с, " + cfg.minutesPerHour() + " мин. = 1 час, "
                + "потолок " + cfg.maxHoursPerDay() + " ч./сутки.");
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
            if (!binding.linked()) {
                continue;
            }
            // Без подтверждения канал не участвует в наградах, но при verify-on-poll
            // его всё равно опрашиваем: код в названии эфира подтвердит канал сам.
            if (cfg.requireVerified() && !binding.verified() && !cfg.verifyOnPoll()) {
                lastLive.remove(binding.uuid());
                continue;
            }
            Player online = Bukkit.getPlayer(binding.uuid());
            if (online == null && cfg.requireOnline()) {
                lastLive.remove(binding.uuid());
                continue;
            }
            // Эфир считается, если стримит хотя бы один канал; зрители суммируются,
            // а минуты кредитуются один раз на игрока — прогресс общий для площадок.
            StreamProbe probe = StreamProbe.OFFLINE;
            StreamBinding working = binding;
            List<StreamBinding.Channel> verifiedNow = new ArrayList<>();
            for (StreamBinding.Channel channel : binding.channels()) {
                boolean verified = !cfg.requireVerified() || channel.verified();
                StreamClient client = active.get(channel.platform());
                if (client == null || (!verified && !cfg.verifyOnPoll())) {
                    continue;
                }
                LiveStream live;
                try {
                    live = client.fetch(channel.channel());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (IOException | RuntimeException exception) {
                    logger.fine("[Streams] " + channel.label() + ": " + exception.getMessage());
                    continue;
                }
                long fetchedAt = System.currentTimeMillis();
                cache.put(binding.uuid(), channel.platform(), live, fetchedAt);
                if (!verified && live.live()
                        && StreamVerification.matches(live.title(), channel.code())) {
                    working = working.markVerified(channel.platform(), channel.channel(), fetchedAt);
                    verifiedNow.add(channel);
                    verified = true;
                }
                if (verified) {
                    probe = probe.plus(live);
                }
            }
            if (!verifiedNow.isEmpty()) {
                store.put(working);
                dirty = true;
                for (StreamBinding.Channel channel : verifiedNow) {
                    logger.info("[Streams] Канал подтверждён автоматически: "
                            + binding.playerName() + " → " + channel.label());
                    notifyVerified(binding.uuid(), channel);
                }
            }
            if (!probe.passes(cfg.minViewers())) {
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
            if (decision.awarded() && !grant(binding, decision.awardHours(), online)) {
                // Награду выдать не удалось — состояние не сохраняем, повторим на следующем опросе.
                continue;
            }
            // Привязку могли поменять, пока шёл сетевой запрос: не затираем новый канал.
            StreamBinding current = store.get(binding.uuid()).orElse(working);
            if (!current.channels().equals(working.channels())) {
                continue;
            }
            store.put(current.withState(decision.state()).renamed(name));
            dirty = true;
            if (decision.awarded()) {
                announce(binding.uuid(), name, decision);
            }
        }
        if (dirty) {
            store.save();
        }
    }

    /** Выдаёт часы буста через API ядра. {@code false} — не получилось, надо повторить. */
    private boolean grant(StreamBinding binding, int hours, Player online) {
        ChrdkRegisterApi api = plugin.coreApi();
        if (api == null) {
            logger.warning("[Streams] Ядро CHRDK_Core недоступно — награда "
                    + binding.playerName() + " не выдана.");
            return false;
        }
        String name = online != null ? online.getName() : binding.playerName();
        try {
            api.grantRewardBoostHours(name, hours, SOURCE);
            return true;
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "[Streams] Не удалось выдать " + hours + " ч. игроку "
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
                    "hours", decision.awardHours(), "today", today, "max", cfg.maxHoursPerDay());
            if (until > 0) {
                plugin.messages().send(player, "awarded-until", "until", until(until));
            }
        });
    }

    /** Сообщить стримеру, что канал подтвердился сам (отправка — на главной нити). */
    private void notifyVerified(UUID uuid, StreamBinding.Channel channel) {
        String platform = channel.platform().displayName();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                plugin.messages().send(player, "auto-verified",
                        "platform", platform, "channel", channel.channel());
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
     * Свежий снимок эфира одного канала — для {@code /stream check} и
     * {@code /stream status}.
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

    /**
     * Первый живой эфир среди каналов привязки — для зрительского чек-ина.
     * Если ни один канал не в эфире, возвращается {@link LiveStream#offline()};
     * ошибка пробрасывается только когда ни одна площадка не ответила.
     */
    LiveStream fetchFirstLive(StreamBinding binding)
            throws IOException, InterruptedException {
        IOException lastError = null;
        boolean answered = false;
        // Зрительский чек-ин засчитывается только по каналам, прошедшим проверку кодом.
        List<StreamBinding.Channel> channels = config.requireVerified()
                ? binding.verifiedChannels()
                : binding.channels();
        for (StreamBinding.Channel channel : channels) {
            StreamClient client = clients.get(channel.platform());
            if (client == null) {
                continue;
            }
            try {
                LiveStream live = client.fetch(channel.channel());
                answered = true;
                if (live.live()) {
                    return live;
                }
            } catch (IOException exception) {
                lastError = exception;
            }
        }
        if (!answered && lastError != null) {
            throw lastError;
        }
        return LiveStream.offline();
    }

    /** Сбросить отметку «эфир подтверждён» — при смене или отвязке канала. */
    void forget(UUID uuid) {
        lastLive.remove(uuid);
        cache.forget(uuid);
    }

    /** Свежий ответ площадки из кэша последнего опроса; пусто — данных нет. */
    Optional<LiveStream> cached(UUID uuid, StreamPlatform platform, long nowMillis) {
        return cache.fresh(uuid, platform, nowMillis, config.statusCacheMillis())
                .map(ProbeCache.Entry::stream);
    }

    /** Сумма зрителей по свежим записям кэша — строка «сейчас смотрят». */
    StreamProbe cachedProbe(UUID uuid, long nowMillis) {
        return cache.aggregate(uuid, nowMillis, config.statusCacheMillis());
    }

    StreamConfig config() {
        return config;
    }

    StreamStore store() {
        return store;
    }
}
