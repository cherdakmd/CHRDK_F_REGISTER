package ru.cherdakmd.chrdkstreams;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Метка стримера: чат и список игроков (TAB).
 *
 * <p>Мигание — периодическая перекраска имени в списке игроков
 * ({@code playerListName}); ник при этом жирный. В чате метка статичная:
 * историю сообщений «мигать» задним числом нельзя.</p>
 *
 * <p>Имя над головой в Minecraft берётся из команд scoreboard, а не из списка
 * игроков: ядро использует команды для приоритетной сортировки, поэтому
 * перекрашивать их из аддона нельзя — сломается порядок в TAB. Если нужно,
 * метку над головой добавляет ядро через свой формат или сторонний плагин
 * нейм-тегов.</p>
 *
 * <p>Если в ядре включён {@code tablist.player-name-format}, аддон не трогает
 * имя в TAB (ядро всё равно перезапишет): администратор может добавить
 * {@code %player_tagged%} в формат ядра. В чате метка работает в любом случае.</p>
 */
final class StreamerTagService {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final StreamPlugin plugin;
    private final StreamStore store;
    private final Set<UUID> managed = new HashSet<>();

    private volatile StreamConfig config;
    private volatile boolean manageTab;
    private BukkitTask task;
    private boolean blinkPhase;

    StreamerTagService(StreamPlugin plugin, StreamStore store, StreamConfig config) {
        this.plugin = plugin;
        this.store = store;
        this.config = config;
    }

    /** Применить настройки: старую задачу снимаем, имена возвращаем к обычным. */
    synchronized void update(StreamConfig newConfig, boolean manageTabNames) {
        this.config = newConfig;
        this.manageTab = manageTabNames;
        stopTask();
        resetNames();
        startTask();
    }

    synchronized void start() {
        startTask();
    }

    synchronized void stop() {
        stopTask();
        resetNames();
    }

    private void startTask() {
        StreamConfig cfg = config;
        if (task != null || !cfg.tags().enabled() || !manageTab) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick,
                1L, cfg.tags().blinkTicks());
    }

    private void stopTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Один кадр мигания: у стримеров меняется цвет метки, у остальных имя возвращается. */
    private void tick() {
        blinkPhase = !blinkPhase;
        StreamConfig cfg = config;
        if (!cfg.tags().enabled() || !cfg.tags().tab()) {
            resetNames();
            return;
        }
        String color = StreamerTags.blinkColor(blinkPhase, cfg.tags().colorA(), cfg.tags().colorB());
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isStreamer(player.getUniqueId())) {
                player.playerListName(component(player.getName(), color));
                managed.add(player.getUniqueId());
            } else if (managed.remove(player.getUniqueId())) {
                player.playerListName(null);
            }
        }
    }

    private void resetNames() {
        for (UUID uuid : new HashSet<>(managed)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.playerListName(null);
            }
        }
        managed.clear();
    }

    /**
     * Оформление ника для чата: провайдер ядра передаёт уже собранное имя.
     * Не стример — имя возвращается без изменений.
     */
    String decorate(String playerName, String decoratedName) {
        StreamConfig cfg = config;
        if (!cfg.tags().enabled() || !isStreamer(playerName)) {
            return decoratedName;
        }
        return StreamerTags.markup(decoratedName, playerName,
                cfg.tags().label(), cfg.tags().chatColor());
    }

    boolean isStreamer(UUID uuid) {
        return store.get(uuid).map(StreamBinding::verified).orElse(false);
    }

    boolean isStreamer(String playerName) {
        return store.byName(playerName).map(StreamBinding::verified).orElse(false);
    }

    private Component component(String playerName, String color) {
        StreamConfig cfg = config;
        String markup = StreamerTags.markup(playerName, playerName, cfg.tags().label(), color);
        return MINI.deserialize(markup);
    }
}
