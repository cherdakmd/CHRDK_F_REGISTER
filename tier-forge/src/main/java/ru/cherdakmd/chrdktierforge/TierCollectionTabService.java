package ru.cherdakmd.chrdktierforge;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Титулы коллекции в таб-листе (1.4.152).
 *
 * <p>Список игроков — чужой участок: в него пишут ядро, TAB, LuckPerms и
 * аддоны, и «кто последний, тот и прав». Поэтому аддон ведёт имена <b>только
 * тогда</b>, когда ядро их не трогает ({@code tablist.player-name-format}
 * пуст). Если ядро задаёт формат и в нём есть {@code %player_tagged%}, титул
 * попадает в таб сам — {@code %player_tagged%} уже прогоняется через реестр
 * оформления ников, куда аддон регистрирует свой провайдер. Если формат есть,
 * а подстановки нет — пишем предупреждение один раз: админу достаточно
 * добавить {@code %player_tagged%} в шаблон.</p>
 *
 * <p>Ник с титулом обновляется не чаще, чем раз в
 * {@code collection.titles.tab.refresh-ticks} тиков, и сразу — при смене
 * выбора, выдаче нового титула и входе игрока. Игрокам без титула имя не
 * подменяется: снимаем только то, что поставили сами.</p>
 */
public final class TierCollectionTabService implements Listener {

    /** Кто владеет именем в табе. */
    public enum Mode {
        /** Титулы или таб выключены — не вмешиваемся. */
        OFF,
        /** Ядро не ведёт имена — рисуем сами. */
        MODULE,
        /** Ядро ведёт имена и подставляет {@code %player_tagged%} — титул уже там. */
        CORE,
        /** Ядро ведёт имена без {@code %player_tagged%} — титула в табе не будет. */
        CORE_WITHOUT_TITLES
    }

    private final TierForgePlugin plugin;
    private final TierCollectionService collection;
    private final Set<UUID> managed = new HashSet<>();
    private BukkitTask task;
    private Mode lastMode = Mode.OFF;
    private boolean warnedMissingTag;

    public TierCollectionTabService(TierForgePlugin plugin, TierCollectionService collection) {
        this.plugin = plugin;
        this.collection = collection;
    }

    /* ------------------------------- чистая логика ------------------------------- */

    /**
     * Решает, кто рисует имя в табе. Отдельная функция без Bukkit — чтобы
     * правило «не воевать с ядром» проверялось юнит-тестом.
     *
     * @param titlesEnabled включены ли титулы в конфиге аддона
     * @param tabEnabled    включён ли таб-режим аддона
     * @param coreFormat    {@code tablist.player-name-format} ядра (может быть {@code null})
     */
    public static Mode decide(boolean titlesEnabled, boolean tabEnabled, String coreFormat) {
        if (!titlesEnabled || !tabEnabled) {
            return Mode.OFF;
        }
        if (coreFormat == null || coreFormat.isBlank()) {
            return Mode.MODULE;
        }
        return coreFormat.contains("%player_tagged%")
                ? Mode.CORE
                : Mode.CORE_WITHOUT_TITLES;
    }

    /* ---------------------------------- работа ---------------------------------- */

    /** Перезапускает таймер под текущие настройки и сразу применяет имена. */
    public void restart() {
        stop();
        TierCollectionConfig config = config();
        Mode mode = mode(config);
        applyMode(mode);
        if (mode != Mode.MODULE) {
            return;
        }
        int ticks = config.titles().tabRefreshTicks();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::applyAll, ticks, ticks);
        applyAll();
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        // Возвращаем имена, которые подменили сами, — иначе после выключения
        // плагина в табе остались бы «висящие» титулы.
        for (UUID id : Set.copyOf(managed)) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.playerListName(null);
            }
        }
        managed.clear();
    }

    /**
     * Вход: имя обновляем сразу, не дожидаясь тика таймера, иначе первые
     * секунды игрок видит себя без титула.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> refresh(player));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        managed.remove(event.getPlayer().getUniqueId());
    }

    /** Обновляет имя одного игрока — после выбора титула или новой находки. */
    public void refresh(Player player) {
        if (player == null || mode(config()) != Mode.MODULE) {
            return;
        }
        apply(player);
    }

    private void applyAll() {
        if (mode(config()) != Mode.MODULE) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            apply(player);
        }
    }

    private void apply(Player player) {
        String title = collection.titleFor(player.getUniqueId());
        // Титул — готовая MiniMessage-строка; пустой после снятия тегов значит
        // «титула нет»: имя такому игроку не подменяем вовсе.
        if (title == null || stripTags(title).isEmpty()) {
            if (managed.remove(player.getUniqueId())) {
                player.playerListName(null);
            }
            return;
        }
        String format = config().titles().tabFormat();
        Component name = plugin.messages().renderTemplate(format,
                "player", player.getName(),
                "title", Messages.trusted(title));
        player.playerListName(name);
        managed.add(player.getUniqueId());
    }

    private void applyMode(Mode mode) {
        if (mode == lastMode) {
            return;
        }
        lastMode = mode;
        if (mode == Mode.CORE) {
            plugin.getLogger().info("CHRDK_TierForge: имена в табе ведёт ядро "
                    + "(%player_tagged% включает титул) — аддон их не дублирует.");
        } else if (mode == Mode.CORE_WITHOUT_TITLES && !warnedMissingTag) {
            warnedMissingTag = true;
            plugin.getLogger().warning("CHRDK_TierForge: ядро задаёт tablist.player-name-format "
                    + "без %player_tagged% — титулы в табе не появятся. Добавьте "
                    + "%player_tagged% в шаблон или очистите его, чтобы титулы рисовал аддон.");
        }
    }

    private Mode mode(TierCollectionConfig config) {
        if (config == null) {
            return Mode.OFF;
        }
        return decide(config.titles().enabled(), config.titles().tab(), coreTabFormat());
    }

    /** Чужой конфиг читаем только на чтение: ядро может перезагрузить его в любой момент. */
    private String coreTabFormat() {
        Plugin core = Bukkit.getPluginManager().getPlugin("CHRDK_Core");
        if (core == null) {
            return "";
        }
        try {
            return core.getConfig().getString("tablist.player-name-format", "");
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private TierCollectionConfig config() {
        TierForgeConfig config = plugin.config();
        return config == null ? null : config.collection();
    }

    /** Убирает MiniMessage-разметку, чтобы отличить «титул есть» от «титул пуст». */
    static String stripTags(String miniMessage) {
        if (miniMessage == null || miniMessage.isEmpty()) {
            return "";
        }
        return miniMessage.replaceAll("<[^>]+>", "").trim();
    }
}
