package ru.cherdakmd.chrdktreasures;

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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Звание кладоискателя рядом с ником: чат и таб-лист (1.4.166).
 *
 * <p>В чате звание добавляет {@code NameTagProvider} ядра — тот же путь, что у
 * титулов кузни, гильдий и метки стримера. Поэтому одна регистрация даёт ник в
 * общем чате, в личных сообщениях и в {@code %player_tagged%} таб-листа, если
 * администратор его использует.</p>
 *
 * <p>Таб-лист — чужой участок: имя в нём пишут ядро, CHRDK_TierForge,
 * CHRDK_Streams и сторонние TAB-плагины. Поэтому аддон рисует имя <b>только
 * тогда</b>, когда ядро этого не делает ({@code tablist.player-name-format}
 * пуст), и уступает, если в табе уже стоит чужое оформленное имя: титул кузни
 * или метка стримера важнее, чем наша метка. Тогда звание остаётся в чате, а
 * в таб его можно вернуть штатным путём — подстановкой {@code %player_tagged%}
 * в формате ядра: она собирает все оформления сразу, и никто ни с кем не
 * спорит за имя.</p>
 *
 * <p>Показывается ли звание — решает игрок: {@code /treasures badge
 * auto|on|off}. Порог {@code rank-tag.min-rank} не даёт «Новичку» попасть в
 * имя: звание должно быть заслугой, а не шумом.</p>
 */
public final class TreasureRankTagService implements Listener {

    /** Кто владеет именем в табе. */
    enum Mode {
        /** Звания или таб выключены — не вмешиваемся. */
        OFF,
        /** Ядро не ведёт имена — рисуем сами. */
        MODULE,
        /** Ядро ведёт имена и подставляет {@code %player_tagged%} — звание уже там. */
        CORE,
        /** Ядро ведёт имена без {@code %player_tagged%} — звания в табе не будет. */
        CORE_WITHOUT_TAG
    }

    private final TreasuresPlugin plugin;
    private final TreasureService service;
    /** Имя, которое поставил аддон: UUID → ровно то, что записали в таб. */
    private final Map<UUID, Component> applied = new HashMap<>();
    private BukkitTask task;
    private Mode lastMode = Mode.OFF;
    private boolean warnedMissingTag;

    public TreasureRankTagService(TreasuresPlugin plugin, TreasureService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /* ------------------------------- чистая логика ------------------------------- */

    /**
     * Решает, кто рисует имя в табе.
     *
     * @param enabled  включены ли звания в конфиге
     * @param tab      включён ли таб-режим аддона
     * @param coreFormat {@code tablist.player-name-format} ядра (может быть {@code null})
     */
    static Mode decide(boolean enabled, boolean tab, String coreFormat) {
        if (!enabled || !tab) {
            return Mode.OFF;
        }
        if (coreFormat == null || coreFormat.isBlank()) {
            return Mode.MODULE;
        }
        return coreFormat.contains("%player_tagged%") ? Mode.CORE : Mode.CORE_WITHOUT_TAG;
    }

    /* ---------------------------------- работа ---------------------------------- */

    /** Перезапускает таймер под текущие настройки и сразу применяет имена. */
    void restart() {
        stopTask();
        TreasuresConfig.RankTag settings = plugin.config().rankTag();
        Mode mode = decide(settings.enabled(), settings.tab(), coreTabFormat());
        applyMode(mode);
        if (mode != Mode.MODULE) {
            resetNames();
            return;
        }
        int ticks = settings.tabRefreshTicks();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::applyAll, ticks, ticks);
        applyAll();
    }

    /** Останавливает таймер и возвращает имена, которые подменили сами. */
    void stop() {
        stopTask();
        resetNames();
    }

    private void stopTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Обновляет имя одного игрока — после новой находки, звания или выбора. */
    void refresh(Player player) {
        if (player == null) {
            return;
        }
        if (mode() == Mode.MODULE) {
            apply(player);
        }
        // В режиме CORE ядро само перерисует таб на ближайшем тике;
        // в OFF имя трогать нечего.
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Тик спустя: игрок уже в списке, прогресс загружен.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (mode() == Mode.MODULE) {
                apply(event.getPlayer());
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        applied.remove(event.getPlayer().getUniqueId());
    }

    private void applyAll() {
        if (mode() != Mode.MODULE) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            apply(player);
        }
    }

    private void apply(Player player) {
        UUID id = player.getUniqueId();
        Component current = player.playerListName();
        Component ours = applied.get(id);
        // Bukkit отдаёт Component.text(ник), когда имя никто не задавал.
        // Всё остальное, что стоит не нами, принадлежит другому плагину
        // (титул кузни, метка стримера, сторонний TAB) — не воюем: чужое
        // оформление важнее, а своё звание игрок увидит в чате.
        boolean untouched = current == null || current.equals(Component.text(player.getName()));
        if (!untouched && (ours == null || !current.equals(ours))) {
            applied.remove(id);
            return;
        }
        String rank = visibleRankLabel(player);
        if (rank == null) {
            if (applied.remove(id) != null) {
                player.playerListName(null);
            }
            return;
        }
        String format = plugin.messages().raw("rank-tag-format");
        if (format == null || format.isBlank()) {
            return;
        }
        Component name = plugin.messages().renderTemplate(format,
                "name", player.getName(),
                "rank", Messages.trusted(rank));
        player.playerListName(name);
        applied.put(id, name);
    }

    private void resetNames() {
        for (UUID id : applied.keySet().toArray(new UUID[0])) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.playerListName(null);
            }
        }
        applied.clear();
    }

    /**
     * Накладывает звание на имя в чате — точка входа {@code NameTagProvider}.
     *
     * @return имя с званием либо {@code null}, если звание показывать не нужно
     */
    String decorate(String playerName, String decoratedName) {
        Player player = Bukkit.getPlayerExact(playerName);
        if (player == null) {
            return null;
        }
        return decorateName(player, decoratedName == null ? playerName : decoratedName);
    }

    /** Собранное имя: {@code decoratedName · звание} либо {@code null}. */
    String decorateName(Player player, String decoratedName) {
        String rank = visibleRankLabel(player);
        if (rank == null) {
            return null;
        }
        String format = plugin.messages().raw("rank-tag-format");
        if (format == null || format.isBlank()) {
            return null;
        }
        return format.replace("%name%", decoratedName == null ? player.getName() : decoratedName)
                .replace("%rank%", rank);
    }

    /**
     * Подпись звания для показа или {@code null}, если её не показывать.
     *
     * <p>Порог, выключатель и выбор игрока проверяются в одном месте: и чат, и
     * таб, и меню спрашивают одно и то же.</p>
     */
    String visibleRankLabel(Player player) {
        TreasuresConfig.RankTag settings = plugin.config().rankTag();
        TreasureRank rank = service.rank(player);
        TreasureRankTags.Choice choice = plugin.store().rankTag(player.getUniqueId().toString());
        if (!settings.shows(rank, choice)) {
            return null;
        }
        String label = plugin.messages().raw("rank." + rank.id());
        return label == null || Messages.stripFormatting(label).isBlank() ? null : label;
    }

    /** Подпись звания игрока без проверки показа — для меню и команды. */
    String rankLabel(Player player) {
        TreasureRank rank = service.rank(player);
        return rank == null ? "" : plugin.messages().raw("rank." + rank.id());
    }

    /** Какое звание игрок видит у себя в имени (для меню и команды). */
    String stateLabel(Player player) {
        TreasureRankTags.Choice choice = plugin.store().rankTag(player.getUniqueId().toString());
        return switch (choice) {
            case AUTO -> plugin.messages().plain("rank-tag-state-auto");
            case ON -> plugin.messages().plain("rank-tag-state-on");
            case OFF -> plugin.messages().plain("rank-tag-state-off");
        };
    }

    /** Меняет выбор игрока по кругу и обновляет имя. */
    TreasureRankTags.Choice cycle(Player player) {
        String key = player.getUniqueId().toString();
        TreasureRankTags.Choice next = plugin.store().rankTag(key).next();
        plugin.store().setRankTag(key, next);
        refresh(player);
        return next;
    }

    private Mode mode() {
        TreasuresConfig.RankTag settings = plugin.config().rankTag();
        return decide(settings.enabled(), settings.tab(), coreTabFormat());
    }

    private void applyMode(Mode mode) {
        if (mode == lastMode) {
            return;
        }
        lastMode = mode;
        if (mode == Mode.CORE) {
            plugin.getLogger().info("[CHRDK_Treasures] имена в табе ведёт ядро "
                    + "(в формате есть %player_tagged%) — звание уже там.");
        } else if (mode == Mode.CORE_WITHOUT_TAG && !warnedMissingTag) {
            warnedMissingTag = true;
            plugin.getLogger().warning("[CHRDK_Treasures] ядро задаёт "
                    + "tablist.player-name-format без %player_tagged% — звания в табе "
                    + "не появятся. Добавьте %player_tagged% в шаблон или очистите его, "
                    + "чтобы звание рисовал аддон.");
        }
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
}
