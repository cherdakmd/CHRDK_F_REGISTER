package ru.cherdakmd.chrdkstreams;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Награды зрителям за просмотр.
 *
 * <p>Правила программы:</p>
 * <ol>
 *   <li>зритель подаёт заявку ({@code /stream watch <стример>});</li>
 *   <li>стример подтверждает её, проверив по панели канала, что человек
 *       подписан на его каналы ({@code /stream viewer accept <ник>});</li>
 *   <li>стример включает код ({@code /stream viewers on}) и показывает его в
 *       эфире; код меняется каждые {@code code-window-minutes};</li>
 *   <li>зритель вводит код ({@code /stream code <код>}) — плагин проверяет,
 *       что эфир действительно идёт, и засчитывает время с прошлого чек-ина, но
 *       не больше окна кода;</li>
 *   <li>минуты копятся в дни проходки по отдельным, более строгим потолкам,
 *       чем у стримера.</li>
 * </ol>
 *
 * <p>Проверить фолловера автоматически нельзя: Twitch не отдаёт список
 * подписчиков без входа каждого зрителя, у VK Play Live такого API нет. Поэтому
 * подписку подтверждает стример, а просмотр доказывает код в эфире.</p>
 */
final class ViewerService {

    /** Источник в аудите ядра: начинается с {@code streams} — подпись «наградой стримерской программы». */
    static final String SOURCE = "streams:watch";

    private final StreamPlugin plugin;
    private final StreamStore store;
    private final ViewerStore viewers;
    private final StreamService service;

    /** Защита от двойного чек-ина, пока идёт запрос к площадке. */
    private final Set<UUID> checking = ConcurrentHashMap.newKeySet();

    private volatile StreamConfig config;

    ViewerService(StreamPlugin plugin, StreamStore store, ViewerStore viewers,
                  StreamService service, StreamConfig config) {
        this.plugin = plugin;
        this.store = store;
        this.viewers = viewers;
        this.service = service;
        this.config = config;
    }

    void start() {
        checking.clear();
    }

    void stop() {
        checking.clear();
    }

    void update(StreamConfig newConfig) {
        this.config = newConfig;
    }

    /* ------------------------------ стример ------------------------------ */

    /** Включить или выключить награды за просмотр у своего канала. */
    void enable(Player streamer, boolean enabled) {
        if (!ready(streamer)) {
            return;
        }
        UUID uuid = streamer.getUniqueId();
        if (!enabled) {
            int attached = viewers.viewersOf(uuid).size();
            viewers.removeSettings(uuid);
            saveAsync();
            plugin.messages().send(streamer, "viewers-disabled", "count", attached);
            return;
        }
        long now = System.currentTimeMillis();
        ViewerSettings settings = new ViewerSettings(uuid, streamer.getName(), true,
                ViewerCodes.generate(), now + config.viewers().codeWindowMillis());
        viewers.putSettings(settings);
        saveAsync();
        plugin.messages().send(streamer, "viewers-enabled", "code", settings.code());
        plugin.messages().send(streamer, "viewers-howto",
                "minutes", config.viewers().codeWindowMinutes());
        if (config.viewers().requireApproval()) {
            plugin.messages().send(streamer, "viewers-approval-on");
        } else {
            plugin.messages().send(streamer, "viewers-approval-off");
        }
    }

    /** Панель стримера: код, сколько зрителей и кто ждёт подтверждения. */
    void panel(Player streamer) {
        if (!ready(streamer)) {
            return;
        }
        UUID uuid = streamer.getUniqueId();
        Optional<ViewerSettings> settings = viewers.settings(uuid);
        if (settings.isEmpty() || !settings.get().enabled()) {
            plugin.messages().send(streamer, "viewers-off-hint", "label", "stream");
            return;
        }
        ViewerSettings current = rotate(settings.get());
        List<ViewerBinding> accepted = viewers.viewersOf(uuid);
        List<ViewerBinding> pending = viewers.pendingOf(uuid);
        plugin.messages().send(streamer, "viewers-panel-header",
                "accepted", accepted.size(), "pending", pending.size());
        plugin.messages().send(streamer, "viewers-code",
                "code", current.code(), "minutes", minutesLeft(current.codeUntil()));
        if (pending.isEmpty()) {
            plugin.messages().send(streamer, "viewers-pending-empty");
            return;
        }
        plugin.messages().send(streamer, "viewers-pending-header");
        int shown = 0;
        for (ViewerBinding request : pending) {
            if (shown++ >= 10) {
                break;
            }
            plugin.messages().send(streamer, "viewers-pending-line",
                    "player", request.playerName(), "label", "stream");
        }
    }

    /** Подтвердить зрителя: стример проверил, что тот подписан на его каналы. */
    void accept(Player streamer, String viewerName) {
        if (!ready(streamer)) {
            return;
        }
        Optional<ViewerBinding> pending = viewers.pendingByName(streamer.getUniqueId(), viewerName);
        if (pending.isEmpty()) {
            plugin.messages().send(streamer, "viewer-none", "player", viewerName);
            return;
        }
        ViewerBinding accepted = pending.get().accept();
        viewers.putViewer(accepted);
        saveAsync();
        plugin.messages().send(streamer, "viewer-accepted", "player", accepted.playerName());
        tell(accepted.uuid(), "watch-approved", "streamer", streamer.getName());
        tell(accepted.uuid(), "watch-accepted",
                "streamer", streamer.getName(), "label", "stream");
    }

    /** Отклонить заявку: она удаляется, прогресс зрителя остаётся. */
    void deny(Player streamer, String viewerName) {
        if (!ready(streamer)) {
            return;
        }
        Optional<ViewerBinding> pending = viewers.pendingByName(streamer.getUniqueId(), viewerName);
        if (pending.isEmpty()) {
            plugin.messages().send(streamer, "viewer-none", "player", viewerName);
            return;
        }
        viewers.removeViewer(pending.get().uuid());
        saveAsync();
        plugin.messages().send(streamer, "viewer-denied", "player", pending.get().playerName());
        tell(pending.get().uuid(), "watch-denied", "streamer", streamer.getName());
    }

    /* ------------------------------ зритель ------------------------------ */

    /** Подать заявку на просмотр канала стримера. */
    void apply(Player viewer, String streamerName) {
        if (!enabled()) {
            plugin.messages().send(viewer, "watch-disabled");
            return;
        }
        Optional<StreamBinding> target = store.byName(streamerName)
                .filter(StreamBinding::verified);
        if (target.isEmpty()) {
            plugin.messages().send(viewer, "watch-no-streamer", "player", streamerName);
            return;
        }
        if (target.get().uuid().equals(viewer.getUniqueId())) {
            plugin.messages().send(viewer, "watch-self");
            return;
        }
        Optional<ViewerSettings> settings = viewers.settings(target.get().uuid());
        if (settings.isEmpty() || !settings.get().enabled()) {
            plugin.messages().send(viewer, "watch-streamer-off",
                    "streamer", target.get().playerName());
            return;
        }
        UUID uuid = viewer.getUniqueId();
        Optional<ViewerBinding> existing = viewers.viewer(uuid);
        if (existing.isPresent() && existing.get().streamer().equals(target.get().uuid())) {
            if (existing.get().accepted()) {
                plugin.messages().send(viewer, "watch-already",
                        "streamer", target.get().playerName());
            } else {
                plugin.messages().send(viewer, "watch-pending",
                        "streamer", target.get().playerName());
            }
            return;
        }
        boolean acceptedNow = !config.viewers().requireApproval();
        long now = System.currentTimeMillis();
        ViewerBinding binding = existing
                .map(old -> old.moveTo(target.get().uuid(), target.get().playerName(),
                        acceptedNow, now).renamed(viewer.getName()))
                .orElseGet(() -> new ViewerBinding(uuid, viewer.getName(), target.get().uuid(),
                        target.get().playerName(), acceptedNow, now, acceptedNow ? now : 0L));
        viewers.putViewer(binding);
        saveAsync();
        if (existing.isPresent()) {
            plugin.messages().send(viewer, "watch-replaced",
                    "streamer", existing.get().streamerName());
        }
        if (acceptedNow) {
            plugin.messages().send(viewer, "watch-accepted",
                    "streamer", target.get().playerName(), "label", "stream");
        } else {
            plugin.messages().send(viewer, "watch-requested",
                    "streamer", target.get().playerName());
            Player streamer = Bukkit.getPlayer(target.get().uuid());
            if (streamer != null) {
                plugin.messages().send(streamer, "viewer-request",
                        "player", viewer.getName(), "label", "stream");
            }
        }
    }

    /** Ввести код из эфира: доказательство просмотра и начисление минут. */
    void checkIn(Player viewer, String rawCode) {
        if (!enabled()) {
            plugin.messages().send(viewer, "watch-disabled");
            return;
        }
        Optional<ViewerBinding> found = viewers.viewer(viewer.getUniqueId());
        if (found.isEmpty()) {
            plugin.messages().send(viewer, "watch-none-hint", "label", "stream");
            return;
        }
        ViewerBinding binding = found.get();
        if (!binding.accepted()) {
            plugin.messages().send(viewer, "watch-pending", "streamer", binding.streamerName());
            return;
        }
        Optional<ViewerSettings> settings = viewers.settings(binding.streamer());
        if (settings.isEmpty() || !settings.get().enabled()) {
            plugin.messages().send(viewer, "watch-streamer-off",
                    "streamer", binding.streamerName());
            return;
        }
        ViewerSettings current = rotate(settings.get());
        if (!ViewerCodes.matches(rawCode, current.code())) {
            plugin.messages().send(viewer, "watch-code-wrong");
            return;
        }
        long now = System.currentTimeMillis();
        long since = binding.lastCheckIn() <= 0L ? 0L : now - binding.lastCheckIn();
        if (since > 0L && since < config.viewers().checkinMinMillis()) {
            long minutes = Math.max(1L,
                    (config.viewers().checkinMinMillis() - since + 59_999L) / 60_000L);
            plugin.messages().send(viewer, "watch-too-soon",
                    "minutes", minutes, "window", config.viewers().checkinMinMinutes());
            return;
        }
        Optional<StreamBinding> channel = store.get(binding.streamer())
                .filter(StreamBinding::verified);
        if (channel.isEmpty() || !config.available(channel.get().platform())) {
            plugin.messages().send(viewer, "watch-streamer-off",
                    "streamer", binding.streamerName());
            return;
        }
        UUID uuid = viewer.getUniqueId();
        String name = viewer.getName();
        StreamPlatform platform = channel.get().platform();
        String channelName = channel.get().channel();
        if (!checking.add(uuid)) {
            plugin.messages().send(viewer, "watch-busy");
            return;
        }
        plugin.messages().send(viewer, "watch-checking");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                LiveStream live = service.fetchLive(platform, channelName);
                if (!live.live()) {
                    tell(uuid, "watch-offline");
                    return;
                }
                credit(uuid, name, since, now);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException exception) {
                tell(uuid, "watch-error", "error", exception.getMessage());
            } finally {
                checking.remove(uuid);
            }
        });
    }

    /** Начислить минуты за подтверждённый чек-ин. Вызывается асинхронно. */
    private void credit(UUID uuid, String name, long since, long now) {
        Optional<ViewerBinding> found = viewers.viewer(uuid);
        if (found.isEmpty()) {
            return;
        }
        ViewerBinding binding = found.get();
        long credit = ViewerCodes.creditMillis(since, config.viewers().codeWindowMillis());
        StreamRewards.Decision decision = StreamRewards.advance(
                viewers.progress(uuid), credit, now, config.viewers().rule());
        if (decision.awarded() && !grant(name, decision.awardDays())) {
            // Награда не выдана — прогресс не сохраняем, повторим на следующем коде.
            return;
        }
        viewers.putProgress(uuid, decision.state());
        viewers.putViewer(binding.checkedIn(now).renamed(name));
        saveAsync();
        long minutes = credit / StreamRewards.MINUTE_MILLIS;
        if (minutes <= 0L) {
            tell(uuid, "watch-code-accepted");
        } else {
            tell(uuid, "watch-tick",
                    "minutes", minutes,
                    "left", StreamRewards.minutesToNextDay(decision.state(),
                            config.viewers().rule()),
                    "today", decision.state().awardedToday(),
                    "max", config.viewers().maxDaysPerDay());
        }
        if (decision.awarded()) {
            announce(uuid, name, decision);
        }
    }

    /** Статус зрителя: кого смотрит, сколько накоплено, что делать дальше. */
    void watching(Player viewer) {
        Optional<ViewerBinding> found = viewers.viewer(viewer.getUniqueId());
        if (found.isEmpty()) {
            plugin.messages().send(viewer, "watch-none-hint", "label", "stream");
            return;
        }
        ViewerBinding binding = found.get();
        StreamRewards.State state = viewers.progress(viewer.getUniqueId());
        plugin.messages().send(viewer, "watch-status-header", "player", viewer.getName());
        plugin.messages().send(viewer, "watch-status-streamer",
                "streamer", binding.streamerName(),
                "state", Messages.trusted(plugin.messages().raw(binding.accepted()
                        ? "watch-state-accepted" : "watch-state-pending")));
        plugin.messages().send(viewer, "watch-status-progress",
                "minutes", StreamRewards.pendingMinutes(state, config.viewers().rule()),
                "needed", config.viewers().minutesPerDay(),
                "today", state.awardedToday(),
                "max", config.viewers().maxDaysPerDay());
        plugin.messages().send(viewer, "watch-status-total", "days", state.totalDays());
        if (!binding.accepted()) {
            plugin.messages().send(viewer, "watch-pending", "streamer", binding.streamerName());
            return;
        }
        Optional<ViewerSettings> settings = viewers.settings(binding.streamer());
        if (settings.isEmpty() || !settings.get().enabled()) {
            plugin.messages().send(viewer, "watch-status-off");
            return;
        }
        plugin.messages().send(viewer, "watch-status-code", "label", "stream");
        plugin.messages().send(viewer, "watch-status-window",
                "window", config.viewers().codeWindowMinutes());
    }

    /** Отписаться от стримера. Прогресс и потолки остаются. */
    void leave(Player viewer) {
        Optional<ViewerBinding> removed = viewers.viewer(viewer.getUniqueId());
        if (removed.isEmpty()) {
            plugin.messages().send(viewer, "watch-none-hint", "label", "stream");
            return;
        }
        viewers.removeViewer(viewer.getUniqueId());
        saveAsync();
        plugin.messages().send(viewer, "watch-left", "streamer", removed.get().streamerName());
    }

    /* ------------------------------ помощники ------------------------------ */

    /** Имена в заявках — для автодополнения. */
    List<String> pendingNames(UUID streamer) {
        List<String> names = new ArrayList<>();
        for (ViewerBinding binding : viewers.pendingOf(streamer)) {
            names.add(binding.playerName());
        }
        return List.copyOf(names);
    }

    int streamerCount() {
        return viewers.streamerCount();
    }

    int viewerCount() {
        return viewers.viewerCount();
    }

    private boolean ready(Player streamer) {
        if (!config.enabled()) {
            plugin.messages().send(streamer, "disabled");
            return false;
        }
        if (!config.viewers().enabled()) {
            plugin.messages().send(streamer, "watch-disabled");
            return false;
        }
        if (store.get(streamer.getUniqueId()).filter(StreamBinding::verified).isEmpty()) {
            plugin.messages().send(streamer, "viewers-no-channel", "label", "stream");
            return false;
        }
        return true;
    }

    private boolean enabled() {
        return config.enabled() && config.viewers().enabled();
    }

    /** Выдать новый код, если окно истекло; сохранение — асинхронно. */
    private ViewerSettings rotate(ViewerSettings settings) {
        long now = System.currentTimeMillis();
        if (!settings.code().isBlank() && !ViewerCodes.expired(settings.codeUntil(), now)) {
            return settings;
        }
        ViewerSettings updated = settings.withCode(ViewerCodes.generate(),
                now + config.viewers().codeWindowMillis());
        viewers.putSettings(updated);
        saveAsync();
        return updated;
    }

    private long minutesLeft(long codeUntil) {
        long remaining = ViewerCodes.remainingMillis(codeUntil, System.currentTimeMillis());
        return Math.max(1L, (remaining + 59_999L) / 60_000L);
    }

    private boolean grant(String name, int days) {
        ChrdkRegisterApi api = plugin.coreApi();
        if (api == null) {
            plugin.getLogger().warning("[Streams] Ядро CHRDK_Core недоступно — награда зрителю "
                    + name + " не выдана.");
            return false;
        }
        try {
            api.grantPass(name, days, SOURCE);
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "[Streams] Не удалось выдать " + days
                    + " дн. зрителю " + name, exception);
            String error = exception.getMessage() == null ? "" : exception.getMessage();
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayerExact(name);
                if (online != null) {
                    plugin.messages().send(online, "watch-award-error", "error", error);
                }
            });
            return false;
        }
    }

    private void announce(UUID uuid, String name, StreamRewards.Decision decision) {
        ChrdkRegisterApi api = plugin.coreApi();
        long until = api == null ? 0L : api.passUntil(name);
        tell(uuid, "watch-awarded",
                "days", decision.awardDays(),
                "today", decision.state().awardedToday(),
                "max", config.viewers().maxDaysPerDay());
        if (until > 0L) {
            tell(uuid, "watch-awarded-until", "until", StreamService.until(until));
        }
    }

    /** Сообщение игроку из любого потока: отправка — на основном. */
    private void tell(UUID uuid, String key, Object... placeholders) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                plugin.messages().send(player, key, placeholders);
            }
        });
    }

    private void saveAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, viewers::save);
    }
}
