package ru.cherdakmd.chrdkstreams;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
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
 * <p>Цепочка такая:</p>
 * <ol>
 *   <li>стример включает просмотр ({@code /stream viewers on}) — плагин выдаёт
 *       код {@code WATCH-XXXX}, который стример показывает на экране эфира;</li>
 *   <li>зритель подаёт заявку ({@code /stream watch <стример>}); стример
 *       подтверждает её, если человек подписан на его каналы (проверка
 *       фолловера вручную — официального API для неё нет);</li>
 *   <li>зритель вводит код из эфира ({@code /stream code <код>}) — это и есть
 *       доказательство, что он смотрит сейчас, а не «зашёл однажды»;</li>
 *   <li>за каждый ввод начисляются минуты, но не больше окна кода и не чаще
 *       {@code checkin-min-minutes}; минуты копятся в сутки проходки по тем же
 *       правилам и потолкам, что у стримера.</li>
 * </ol>
 *
 * <p>Код меняется каждые {@code code-window-minutes}: если зритель перестал
 * смотреть, он пропустит смену и перестанет получать минуты.</p>
 */
final class ViewerService {

    /** Источник в аудите: начинается с {@code streams}, поэтому подпись — «наградой стримерской программы». */
    static final String SOURCE = "streams:watch";
    private static final String LABEL = "stream";

    private final StreamPlugin plugin;
    private final ViewerStore store;
    private final StreamStore streams;
    private final StreamService streamService;

    /** Защита от двойного чек-ина одного зрителя (команда + сеть). */
    private final Set<UUID> checking = ConcurrentHashMap.newKeySet();

    private volatile StreamConfig config;
    private BukkitTask rotateTask;

    ViewerService(StreamPlugin plugin, ViewerStore store, StreamStore streams,
                  StreamService streamService, StreamConfig config) {
        this.plugin = plugin;
        this.store = store;
        this.streams = streams;
        this.streamService = streamService;
        this.config = config;
    }

    /* ------------------------------ жизненный цикл ------------------------------ */

    synchronized void start() {
        stopTask();
        rotateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::rotateCodes, 20L, 20L);
    }

    synchronized void stop() {
        stopTask();
        checking.clear();
    }

    synchronized void update(StreamConfig newConfig) {
        this.config = newConfig;
    }

    private void stopTask() {
        if (rotateTask != null) {
            rotateTask.cancel();
            rotateTask = null;
        }
    }

    /** Раз в секунду: у включённых стримеров истекает окно кода — выдаём новый. */
    private void rotateCodes() {
        StreamConfig cfg = config;
        if (!cfg.enabled() || !cfg.viewers().enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        long window = cfg.viewers().codeWindowMillis();
        boolean changed = false;
        for (ViewerStore.StreamerState state : store.allStreamers()) {
            if (!state.enabled()) {
                continue;
            }
            if (!state.code().isBlank() && !ViewerCodes.expired(now, state.codeUntil())) {
                continue;
            }
            store.putStreamer(state.withCode(ViewerCodes.generate(), now + window));
            changed = true;
        }
        if (changed) {
            store.save();
        }
    }

    /* ------------------------------ настройки стримера ------------------------------ */

    /** Включить/выключить просмотр у своего канала. */
    void enable(Player streamer, boolean enabled) {
        StreamConfig cfg = config;
        if (!cfg.enabled() || !cfg.viewers().enabled()) {
            plugin.messages().send(streamer, "watch-disabled");
            return;
        }
        if (streams.get(streamer.getUniqueId()).filter(StreamBinding::verified).isEmpty()) {
            plugin.messages().send(streamer, "viewers-no-channel");
            return;
        }
        if (!enabled) {
            int removed = store.removeStreamer(streamer.getUniqueId());
            store.save();
            plugin.messages().send(streamer, "viewers-disabled", "count", removed);
            return;
        }
        long now = System.currentTimeMillis();
        ViewerStore.StreamerState state = new ViewerStore.StreamerState(
                streamer.getUniqueId(), streamer.getName(), true,
                ViewerCodes.generate(), now + cfg.viewers().codeWindowMillis());
        store.putStreamer(state);
        store.save();
        plugin.messages().send(streamer, "viewers-enabled", "code", state.code());
        plugin.messages().send(streamer, "viewers-howto",
                "minutes", cfg.viewers().codeWindowMinutes());
        plugin.messages().send(streamer,
                cfg.viewers().requireApproval() ? "viewers-approval-on" : "viewers-approval-off");
    }

    /** Панель стримера: код, счётчики и заявки на подтверждение. */
    void panel(Player streamer) {
        StreamConfig cfg = config;
        if (!cfg.enabled() || !cfg.viewers().enabled()) {
            plugin.messages().send(streamer, "watch-disabled");
            return;
        }
        if (streams.get(streamer.getUniqueId()).filter(StreamBinding::verified).isEmpty()) {
            plugin.messages().send(streamer, "viewers-no-channel");
            return;
        }
        Optional<ViewerStore.StreamerState> state = store.streamer(streamer.getUniqueId());
        if (state.isEmpty() || !state.get().enabled()) {
            plugin.messages().send(streamer, "viewers-off-hint", "label", LABEL);
            return;
        }
        List<ViewerBinding> viewers = new ArrayList<>(store.viewersOf(streamer.getUniqueId()));
        List<ViewerBinding> pending = new ArrayList<>(store.pendingOf(streamer.getUniqueId()));
        int accepted = viewers.size() - pending.size();
        plugin.messages().send(streamer, "viewers-panel-header",
                "accepted", accepted, "pending", pending.size());
        long leftMinutes = Math.max(0L,
                (state.get().codeUntil() - System.currentTimeMillis() + 59_999L) / 60_000L);
        plugin.messages().send(streamer, "viewers-code",
                "code", state.get().code(), "minutes", leftMinutes);
        if (pending.isEmpty()) {
            plugin.messages().send(streamer, "viewers-pending-empty");
        } else {
            plugin.messages().send(streamer, "viewers-pending-header");
            int shown = 0;
            for (ViewerBinding request : pending) {
                if (shown++ >= 10) {
                    break;
                }
                plugin.messages().send(streamer, "viewers-pending-line",
                        "player", request.name(), "label", LABEL);
            }
        }
    }

    /* ------------------------------ заявка зрителя ------------------------------ */

    /** Зритель просит доступ к просмотру канала. */
    void apply(Player viewer, String streamerName) {
        StreamConfig cfg = config;
        if (!cfg.enabled() || !cfg.viewers().enabled()) {
            plugin.messages().send(viewer, "watch-disabled");
            return;
        }
        Optional<StreamBinding> target = streams.byName(streamerName)
                .filter(StreamBinding::verified);
        if (target.isEmpty()) {
            plugin.messages().send(viewer, "watch-no-streamer", "player", streamerName);
            return;
        }
        if (target.get().uuid().equals(viewer.getUniqueId())) {
            plugin.messages().send(viewer, "watch-self");
            return;
        }
        Optional<ViewerStore.StreamerState> state = store.streamer(target.get().uuid());
        if (state.isEmpty() || !state.get().enabled()) {
            plugin.messages().send(viewer, "watch-streamer-off");
            return;
        }
        Optional<ViewerBinding> existing = store.viewer(viewer.getUniqueId());
        if (existing.isPresent() && existing.get().streamer().equals(target.get().uuid())
                && existing.get().accepted()) {
            plugin.messages().send(viewer, "watch-already", "streamer", target.get().playerName());
            return;
        }
        boolean acceptedNow = !cfg.viewers().requireApproval();
        long now = System.currentTimeMillis();
        ViewerBinding binding = new ViewerBinding(viewer.getUniqueId(), viewer.getName(),
                target.get().uuid(), target.get().playerName(), acceptedNow, now,
                acceptedNow ? now : 0L);
        store.putViewer(binding);
        store.save();
        if (existing.isPresent() && !existing.get().streamer().equals(target.get().uuid())) {
            plugin.messages().send(viewer, "watch-replaced",
                    "streamer", existing.get().streamerName());
        }
        if (acceptedNow) {
            plugin.messages().send(viewer, "watch-accepted",
                    "streamer", target.get().playerName(), "label", LABEL);
        } else {
            plugin.messages().send(viewer, "watch-requested",
                    "streamer", target.get().playerName());
            Player onlineStreamer = Bukkit.getPlayer(target.get().uuid());
            if (onlineStreamer != null) {
                plugin.messages().send(onlineStreamer, "viewer-request",
                        "player", viewer.getName(), "label", LABEL);
            }
        }
    }

    /* ------------------------------ подтверждение стримером ------------------------------ */

    void accept(Player streamer, String viewerName) {
        if (!viewerModeReady(streamer)) {
            return;
        }
        Optional<ViewerBinding> pending = store.pendingByName(streamer.getUniqueId(), viewerName);
        if (pending.isEmpty()) {
            plugin.messages().send(streamer, "viewer-none", "player", viewerName);
            return;
        }
        ViewerBinding accepted = pending.get().accept(System.currentTimeMillis());
        store.putViewer(accepted);
        store.save();
        plugin.messages().send(streamer, "viewer-accepted", "player", accepted.name());
        Player onlineViewer = Bukkit.getPlayer(accepted.uuid());
        if (onlineViewer != null) {
            plugin.messages().send(onlineViewer, "watch-approved", "streamer", streamer.getName());
            plugin.messages().send(onlineViewer, "watch-accepted",
                    "streamer", streamer.getName(), "label", LABEL);
        }
    }

    void deny(Player streamer, String viewerName) {
        if (!viewerModeReady(streamer)) {
            return;
        }
        Optional<ViewerBinding> pending = store.pendingByName(streamer.getUniqueId(), viewerName);
        if (pending.isEmpty()) {
            plugin.messages().send(streamer, "viewer-none", "player", viewerName);
            return;
        }
        store.removeViewer(pending.get().uuid());
        store.save();
        plugin.messages().send(streamer, "viewer-denied", "player", pending.get().name());
        Player onlineViewer = Bukkit.getPlayer(pending.get().uuid());
        if (onlineViewer != null) {
            plugin.messages().send(onlineViewer, "watch-denied", "streamer", streamer.getName());
        }
    }

    private boolean viewerModeReady(Player streamer) {
        StreamConfig cfg = config;
        if (!cfg.enabled() || !cfg.viewers().enabled()) {
            plugin.messages().send(streamer, "watch-disabled");
            return false;
        }
        Optional<ViewerStore.StreamerState> state = store.streamer(streamer.getUniqueId());
        if (state.isEmpty() || !state.get().enabled()) {
            plugin.messages().send(streamer, "viewers-off-hint", "label", LABEL);
            return false;
        }
        return true;
    }

    /* ------------------------------ чек-ин ------------------------------ */

    /** Зритель ввёл код из эфира. */
    void checkIn(Player viewer, String rawCode) {
        StreamConfig cfg = config;
        if (!cfg.enabled() || !cfg.viewers().enabled()) {
            plugin.messages().send(viewer, "watch-disabled");
            return;
        }
        UUID uuid = viewer.getUniqueId();
        Optional<ViewerBinding> found = store.viewer(uuid);
        if (found.isEmpty()) {
            plugin.messages().send(viewer, "watch-none-hint", "label", LABEL);
            return;
        }
        ViewerBinding binding = found.get();
        if (!binding.accepted()) {
            plugin.messages().send(viewer, "watch-pending", "streamer", binding.streamerName());
            return;
        }
        Optional<ViewerStore.StreamerState> state = store.streamer(binding.streamer());
        if (state.isEmpty() || !state.get().enabled()) {
            plugin.messages().send(viewer, "watch-streamer-off");
            return;
        }
        if (!ViewerCodes.matches(rawCode, state.get().code())) {
            plugin.messages().send(viewer, "watch-code-wrong");
            return;
        }
        long now = System.currentTimeMillis();
        long since = now - Math.max(0L, binding.lastCheckIn());
        if (since < cfg.viewers().checkinMinMillis()) {
            long left = (cfg.viewers().checkinMinMillis() - since + 59_999L) / 60_000L;
            plugin.messages().send(viewer, "watch-too-soon",
                    "minutes", Math.max(1L, left),
                    "window", cfg.viewers().checkinMinMinutes());
            return;
        }
        Optional<StreamBinding> streamer = streams.get(binding.streamer())
                .filter(StreamBinding::verified);
        if (streamer.isEmpty()) {
            plugin.messages().send(viewer, "watch-no-streamer", "player", binding.streamerName());
            return;
        }
        if (!checking.add(uuid)) {
            plugin.messages().send(viewer, "watch-too-soon",
                    "minutes", 1L, "window", cfg.viewers().checkinMinMinutes());
            return;
        }
        String name = viewer.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                LiveStream live = streamService.fetchLive(streamer.get().platform(),
                        streamer.get().channel());
                if (!live.live()) {
                    plugin.messages().send(viewer, "watch-offline");
                    return;
                }
                ViewerBinding current = store.viewer(uuid).orElse(null);
                if (current == null || !current.accepted()) {
                    plugin.messages().send(viewer, "watch-none-hint", "label", LABEL);
                    return;
                }
                long credit = ViewerCodes.creditMillis(now, current.lastCheckIn(),
                        cfg.viewers().codeWindowMillis());
                if (credit <= 0) {
                    return;
                }
                StreamRewards.Decision decision = StreamRewards.advance(
                        store.progress(uuid), credit, now, cfg.viewers().rule());
                if (decision.awarded() && !grant(name, decision.awardDays())) {
                    return; // состояние не сохраняем — повторим на следующем коде
                }
                store.putProgress(uuid, decision.state());
                store.putViewer(current.checkedIn(now).renamed(name));
                store.save();
                plugin.messages().send(viewer, "watch-tick",
                        "minutes", Math.max(1L, credit / StreamRewards.MINUTE_MILLIS),
                        "left", StreamRewards.minutesToNextDay(decision.state(), cfg.viewers().rule()),
                        "today", decision.state().awardedToday(),
                        "max", cfg.viewers().maxDaysPerDay());
                if (decision.awarded()) {
                    announce(viewer, decision);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException exception) {
                plugin.messages().send(viewer, "watch-error", "error", exception.getMessage());
            } finally {
                checking.remove(uuid);
            }
        });
    }

    private boolean grant(String name, int days) {
        ChrdkRegisterApi api = plugin.coreApi();
        if (api == null) {
            plugin.getLogger().warning("[Streams] Ядро недоступно — награда зрителю "
                    + name + " не выдана.");
            return false;
        }
        try {
            api.grantPass(name, days, SOURCE);
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "[Streams] Не удалось выдать " + days + " дн. зрителю " + name, exception);
            Player online = Bukkit.getPlayerExact(name);
            if (online != null) {
                plugin.messages().send(online, "watch-award-error",
                        "error", exception.getMessage() == null ? "" : exception.getMessage());
            }
            return false;
        }
    }

    private void announce(Player viewer, StreamRewards.Decision decision) {
        ChrdkRegisterApi api = plugin.coreApi();
        String name = viewer.getName();
        long until = api == null ? 0L : api.passUntil(name);
        plugin.messages().send(viewer, "watch-awarded",
                "days", decision.awardDays(),
                "today", decision.state().awardedToday(),
                "max", config.viewers().maxDaysPerDay());
        if (until > 0) {
            plugin.messages().send(viewer, "watch-awarded-until",
                    "until", StreamService.until(until));
        }
    }

    /* ------------------------------ статус зрителя ------------------------------ */

    void watching(Player viewer) {
        StreamConfig cfg = config;
        Optional<ViewerBinding> found = store.viewer(viewer.getUniqueId());
        if (found.isEmpty()) {
            plugin.messages().send(viewer, "watch-none-hint", "label", LABEL);
            return;
        }
        ViewerBinding binding = found.get();
        StreamRewards.State state = store.progress(viewer.getUniqueId());
        plugin.messages().send(viewer, "watch-status-header", "player", viewer.getName());
        plugin.messages().send(viewer, "watch-status-streamer",
                "streamer", binding.streamerName(),
                "state", Messages.trusted(plugin.messages().raw(binding.accepted()
                        ? "watch-state-accepted" : "watch-state-pending")));
        plugin.messages().send(viewer, "watch-status-progress",
                "minutes", StreamRewards.pendingMinutes(state, cfg.viewers().rule()),
                "needed", cfg.viewers().minutesPerDay(),
                "today", state.awardedToday(),
                "max", cfg.viewers().maxDaysPerDay());
        plugin.messages().send(viewer, "watch-status-total", "days", state.totalDays());
        if (!binding.accepted()) {
            plugin.messages().send(viewer, "watch-pending", "streamer", binding.streamerName());
            return;
        }
        Optional<ViewerStore.StreamerState> streamerState = store.streamer(binding.streamer());
        if (streamerState.isEmpty() || !streamerState.get().enabled()) {
            plugin.messages().send(viewer, "watch-status-off");
            return;
        }
        plugin.messages().send(viewer, "watch-status-code", "label", LABEL);
        plugin.messages().send(viewer, "watch-status-window",
                "window", cfg.viewers().codeWindowMinutes());
    }

    void leave(Player viewer) {
        Optional<ViewerBinding> removed = store.removeViewer(viewer.getUniqueId());
        if (removed.isEmpty()) {
            plugin.messages().send(viewer, "watch-none-hint", "label", LABEL);
            return;
        }
        store.save();
        plugin.messages().send(viewer, "watch-left", "streamer", removed.get().streamerName());
    }

    /* ------------------------------ вспомогательное ------------------------------ */

    /** Имена заявок стримера — для автодополнения {@code /stream viewer accept}. */
    List<String> pendingNames(UUID streamer) {
        List<String> names = new ArrayList<>();
        for (ViewerBinding binding : store.pendingOf(streamer)) {
            names.add(binding.name());
        }
        return List.copyOf(names);
    }

    int viewerCount() {
        return store.viewerCount();
    }

    int streamerCount() {
        int count = 0;
        for (ViewerStore.StreamerState state : store.allStreamers()) {
            if (state.enabled()) {
                count++;
            }
        }
        return count;
    }
}
