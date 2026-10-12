package ru.cherdakmd.chrdkregister;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Таб-лист (TAB): периодическое обновление шапки/подвала списка игроков и формата
 * имени в списке. Шаблоны — в {@code config.yml} (секция {@code tablist}), разметка —
 * MiniMessage/&amp;-коды, подстановки: {@code %player% %online% %max% %tps% %ping%
 * %world% %pass_status% %pass_left% %pass_days%}.
 *
 * Значения подстановок экранируются ({@link Messages#escape(Object)}), поэтому
 * ник игрока не сломает разметку. Если {@code player-name-format} пуст — имя в
 * списке не трогаем, чтобы не конфликтовать с менеджерами префиксов (LuckPerms-TAB).
 */
public final class TabListService implements Listener {

    /** Команда «вверх таба»: клиент ставит игроков с командой выше остальных, а '!' сортируется первым. */
    static final String PIN_TEAM_NAME = "!chrdk-pin";

    /** Снимок настроек из {@code config.yml}. */
    record Settings(boolean enabled, int refreshTicks, List<String> header, List<String> footer,
                    String nameFormat, List<String> pinnedNames) {
        static Settings disabled() {
            return new Settings(false, 40, List.of(), List.of(), "", List.of());
        }
    }

    /** Значения подстановок одного игрока — ровно то, что знает юнит-тест. */
    record TabContext(String playerName, int online, int maxPlayers, double tps, int ping,
                      String world, String passStatus, String passLeft, long passDaysLeft) {

        /** Пары «ключ-значение» для Messages.fill: ключ → строковое значение. */
        Object[] placeholders() {
            return new Object[]{
                    "player", playerName,
                    "online", online,
                    "max", maxPlayers,
                    "tps", String.format(Locale.ROOT, "%.1f", Math.min(20.0, Math.max(0.0, tps))),
                    "ping", ping,
                    "world", world,
                    "pass_status", passStatus,
                    "pass_left", passLeft,
                    "pass_days", passDaysLeft};
        }
    }

    private final RegisterPlugin plugin;
    private final AccessManager accessManager;
    private Settings settings = Settings.disabled();
    private BukkitTask refreshTask;

    public TabListService(RegisterPlugin plugin, AccessManager accessManager) {
        this.plugin = plugin;
        this.accessManager = accessManager;
    }

    /** Перечитывает {@code tablist.*} из конфига, пересоздаёт задачу и применяет к онлайну. */
    public void reload() {
        var cfg = plugin.getConfig();
        String root = "tablist";
        this.settings = new Settings(
                cfg.getBoolean(root + ".enabled", true),
                Math.max(10, cfg.getInt(root + ".refresh-ticks", 40)),
                cfg.getStringList(root + ".header"),
                cfg.getStringList(root + ".footer"),
                cfg.getString(root + ".player-name-format", ""),
                cfg.getStringList(root + ".pinned-names"));
        reschedule();
        syncPinned();
        if (settings.enabled()) {
            applyAll();
        } else {
            resetAll();
        }
    }

    public void shutdown() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        clearPinned();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!settings.enabled()) {
            return;
        }
        // тик спустя: игрок уже в списке онлайн-игроков, проходка прогружена
        plugin.getServer().getScheduler().runTask(plugin, this::applyAll);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (!settings.enabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, this::applyAll);
    }

    private void reschedule() {
        shutdown();
        if (settings.enabled() && settings.refreshTicks() > 0) {
            refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::applyAll,
                    settings.refreshTicks(), settings.refreshTicks());
        }
    }

    private void applyAll() {
        ensurePinned();
        for (Player player : Bukkit.getOnlinePlayers()) {
            apply(player);
        }
    }

    private void resetAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.playerListName(null);
            player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        }
    }

    private void apply(Player player) {
        TabContext context = contextOf(player, System.currentTimeMillis());
        Messages messages = plugin.messages();
        Component header = joinLines(renderLines(messages, settings.header(), context));
        Component footer = joinLines(renderLines(messages, settings.footer(), context));
        player.sendPlayerListHeaderAndFooter(header, footer);
        if (!settings.nameFormat().isBlank()) {
            player.playerListName(renderName(messages, settings.nameFormat(), context));
        }
    }

    // ------------------------------ закрепление вверху списка -------------------

    /** Полная синхронизация закреплённых ников при reload: команда пересобирается. */
    private void syncPinned() {
        Scoreboard board = mainScoreboard();
        if (board == null) {
            return;
        }
        Team team = board.getTeam(PIN_TEAM_NAME);
        if (!settings.enabled() || settings.pinnedNames().isEmpty()) {
            if (team != null) {
                team.unregister();
            }
            return;
        }
        if (team == null) {
            team = board.registerNewTeam(PIN_TEAM_NAME);
        }
        // пересобираем участников: админ мог убрать ник из pinned-names на reload
        for (String entry : new ArrayList<>(team.getEntries())) {
            team.removeEntry(entry);
        }
        for (String name : settings.pinnedNames()) {
            if (name != null && !name.isBlank()) {
                team.addEntry(name.trim()); // работает и для оффлайн-игроков
            }
        }
    }

    /** Страховка на каждый refresh: если другой плагин выкинул запись — вернуть. */
    private void ensurePinned() {
        if (!settings.enabled() || settings.pinnedNames().isEmpty()) {
            return;
        }
        Scoreboard board = mainScoreboard();
        if (board == null) {
            return;
        }
        Team team = board.getTeam(PIN_TEAM_NAME);
        if (team == null) {
            team = board.registerNewTeam(PIN_TEAM_NAME);
        }
        for (String name : settings.pinnedNames()) {
            if (name != null && !name.isBlank() && !team.hasEntry(name.trim())) {
                team.addEntry(name.trim());
            }
        }
    }

    private void clearPinned() {
        Scoreboard board = mainScoreboard();
        if (board == null) {
            return;
        }
        Team team = board.getTeam(PIN_TEAM_NAME);
        if (team != null) {
            team.unregister();
        }
    }

    private static Scoreboard mainScoreboard() {
        return Bukkit.getScoreboardManager() == null
                ? null
                : Bukkit.getScoreboardManager().getMainScoreboard();
    }

    /** Есть ли ник в закрепе (регистронезависимо) — чистая логика для тестов. */
    static boolean isPinned(List<String> pinnedNames, String playerName) {
        if (pinnedNames == null || playerName == null) {
            return false;
        }
        for (String pinned : pinnedNames) {
            if (pinned != null && pinned.trim().equalsIgnoreCase(playerName.trim())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------ чистая логика (юнит-тесты) -------------------

    /** Несколько компонентов в один через перевод строки. Пустой список — пустой компонент. */
    static Component joinLines(List<Component> lines) {
        Component all = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                all = all.append(Component.newline());
            }
            all = all.append(lines.get(i));
        }
        return all;
    }

    static List<Component> renderLines(Messages messages, List<String> templates, TabContext context) {
        List<Component> out = new ArrayList<>(templates.size());
        for (String template : templates) {
            out.add(messages.renderTemplate(template, context.placeholders()));
        }
        return out;
    }

    static Component renderName(Messages messages, String format, TabContext context) {
        return messages.renderTemplate(format, context.placeholders());
    }

    // ------------------------------ сборка контекста -----------------------------

    private TabContext contextOf(Player player, long nowMillis) {
        RegistrationStore.PlayerAccount account =
                accessManager.store().getAccount(player.getName()).orElse(null);
        double tps;
        try {
            tps = Bukkit.getTPS()[0];
        } catch (Throwable ignored) {
            tps = 20.0;
        }
        return new TabContext(
                player.getName(),
                Bukkit.getOnlinePlayers().size(),
                Bukkit.getMaxPlayers(),
                tps,
                player.getPing(),
                player.getWorld().getName(),
                PlaceholderValues.passStatus(account, nowMillis),
                PlaceholderValues.passLeft(account, nowMillis),
                account != null && account.passUntil > 0
                        ? PlaceholderValues.wholeDaysLeft(account.passUntil, nowMillis)
                        : 0);
    }
}
