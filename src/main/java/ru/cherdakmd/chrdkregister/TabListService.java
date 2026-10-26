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
                    String nameFormat, List<String> pinnedNames, boolean showRankName) {
        Settings {
            // Пустой формат — не трогаем чужие префиксы; название ранга без
            // формата никчему.
            showRankName = showRankName && nameFormat != null && !nameFormat.isBlank();
        }

        static Settings disabled() {
            return new Settings(false, 40, List.of(), List.of(), "", List.of(), false);
        }
    }

    /** Значения подстановок одного игрока — ровно то, что знает юнит-тест. */
    record TabContext(String playerName, int online, int maxPlayers, double tps, int ping,
                      String world, String passStatus, String passLeft, long passDaysLeft,
                      PresentationContext presentation, String taggedName, String rankTag) {
        TabContext(String playerName, int online, int maxPlayers, double tps, int ping, String world,
                   String passStatus, String passLeft, long passDaysLeft, PresentationContext presentation,
                   String taggedName) {
            this(playerName, online, maxPlayers, tps, ping, world, passStatus, passLeft, passDaysLeft,
                    presentation, taggedName, "");
        }

        TabContext(String playerName, int online, int maxPlayers, double tps, int ping, String world,
                   String passStatus, String passLeft, long passDaysLeft, PresentationContext presentation) {
            this(playerName, online, maxPlayers, tps, ping, world, passStatus, passLeft, passDaysLeft,
                    presentation, playerName, "");
        }

        TabContext(String playerName, int online, int maxPlayers, double tps, int ping, String world,
                   String passStatus, String passLeft, long passDaysLeft) {
            this(playerName, online, maxPlayers, tps, ping, world, passStatus, passLeft, passDaysLeft,
                    new PresentationContext("", "", "", "", ""), playerName, "");
        }

        /** Пары «ключ-значение» для Messages.fill: ключ → строковое значение. */
        Object[] placeholders() {
            Object[] base = new Object[]{
                    "player", playerName,
                    "online", online,
                    "max", maxPlayers,
                    "tps", String.format(Locale.ROOT, "%.1f", Math.min(20.0, Math.max(0.0, tps))),
                    "ping", ping,
                    "world", world,
                    "pass_status", passStatus,
                    "pass_left", passLeft,
                    "pass_days", passDaysLeft,
                    // Ник с метками аддонов и хвост с названием ранга — доверенная
                    // разметка, поэтому Trusted, а не строка.
                    "player_tagged", Messages.trusted(taggedName),
                    "rank_tag", Messages.trusted(rankTag)};
            Object[] extra = presentation.placeholders();
            Object[] result = java.util.Arrays.copyOf(base, base.length + extra.length);
            System.arraycopy(extra, 0, result, base.length, extra.length);
            return result;
        }
    }

    private final RegisterPlugin plugin;
    private final AccessManager accessManager;
    private Settings settings = Settings.disabled();
    private BukkitTask refreshTask;
    private final java.util.Set<java.util.UUID> managedNames = new java.util.HashSet<>();
    private final java.util.Set<java.util.UUID> managedHeaders = new java.util.HashSet<>();
    /** Оформление ника из ядра (тег каталога + метки аддонов) для {@code %player_tagged%}. */
    private volatile NameDecorator nameDecorator = Messages::escape;
    /** 1.4.183: личные переключатели надписей; {@code null} — всё видно. */
    private volatile LabelPreferences labelPreferences;

    public TabListService(RegisterPlugin plugin, AccessManager accessManager) {
        this.plugin = plugin;
        this.accessManager = accessManager;
    }

    /**
     * Оформление ника для {@code %player_tagged%}.
     *
     * <p>С 1.4.186 декоратор читает личные переключатели сам (по каждому
     * источнику отдельно): таб-листу остаётся только подставить результат.
     * Так настройки надписей живут в одном месте, а не дублируются в двух
     * вызовах с флагами.</p>
     */
    @FunctionalInterface
    public interface NameDecorator {
        String decorate(String playerName);
    }

    /** Подключает оформление ников (вызывается один раз при включении ядра). */
    public void setNameDecorator(NameDecorator decorator) {
        this.nameDecorator = decorator == null ? name -> name : decorator;
    }

    /** Подключает личные переключатели надписей (1.4.183). */
    public void setLabelPreferences(LabelPreferences preferences) {
        this.labelPreferences = preferences;
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
                cfg.getStringList(root + ".pinned-names"),
                cfg.getBoolean(root + ".show-rank-name", true));
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
        resetAll();
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
        managedNames.remove(event.getPlayer().getUniqueId());
        managedHeaders.remove(event.getPlayer().getUniqueId());
        if (!settings.enabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, this::applyAll);
    }

    private void reschedule() {
        if (refreshTask != null) { refreshTask.cancel(); refreshTask = null; }
        clearPinned();
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
            if (managedNames.remove(player.getUniqueId())) player.playerListName(null);
            if (managedHeaders.remove(player.getUniqueId())) player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        }
    }

    private void apply(Player player) {
        boolean showHeader = visible(player, LabelPreferences.Label.HEADER);
        TabContext context = contextOf(player, System.currentTimeMillis());
        Messages messages = plugin.messages();
        // 1.4.183: игрок может спрятать шапку и подвал — тогда список остаётся
        // чистым, а не «с дырками» в полэкрана.
        Component header = showHeader
                ? joinLines(renderLines(messages, settings.header(), context)) : Component.empty();
        Component footer = showHeader
                ? joinLines(renderLines(messages, settings.footer(), context)) : Component.empty();
        player.sendPlayerListHeaderAndFooter(header, footer);
        managedHeaders.add(player.getUniqueId());
        if (!settings.nameFormat().isBlank()) {
            player.playerListName(renderName(messages,
                    nameFormat(settings.nameFormat(), settings.showRankName()), context));
            managedNames.add(player.getUniqueId());
        } else if (managedNames.remove(player.getUniqueId())) player.playerListName(null);
    }

    /** Видна ли надпись у этого игрока: без хранилища — всё видно. */
    private boolean visible(Player player, LabelPreferences.Label label) {
        LabelPreferences preferences = labelPreferences;
        return preferences == null || preferences.visible(player.getName(), label);
    }

    /**
     * Перерисовывает таб всем (1.4.183).
     *
     * <p>Имя в списке общее: если игрок спрятал ранг, это должны увидеть все, а
     * не только он. Поэтому после переключения в {@code /labels} обновляется
     * весь онлайн, а не один зритель.</p>
     */
    public void refreshAll() {
        if (!settings.enabled()) {
            return;
        }
        applyAll();
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
                String exact = Bukkit.getOnlinePlayers().stream().map(Player::getName)
                        .filter(n -> n.equalsIgnoreCase(name.trim())).findFirst().orElse(name.trim());
                pin(board, team, exact);
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
        for (Player player : Bukkit.getOnlinePlayers()) {
            String name = player.getName();
            if (isPinned(settings.pinnedNames(), name) && !team.hasEntry(name)) {
                pin(board, team, name);
            }
        }
    }

    /**
     * Ставит игрока в команду закрепа (1.4.183).
     *
     * <p>Закреп обязан быть выше любого звания, а команда у игрока может быть
     * только одна: если он уже стоит в нашей же служебной команде (префикс
     * {@code !} — закреп ядра и сортировка CHRDK_Ranks), забираем его оттуда.
     * Чужие команды (LuckPerms, TAB) не трогаем: там свои правила, и ломать их
     * ради порядка в списке нельзя.</p>
     */
    private static void pin(Scoreboard board, Team team, String name) {
        Team current = board == null ? null : board.getEntryTeam(name);
        if (current != null && current.equals(team)) {
            return;
        }
        if (current != null) {
            if (!current.getName().startsWith("!")) {
                return;
            }
            current.removeEntry(name);
        }
        team.addEntry(name);
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

    /** Старый отдельный %rank_tag% не должен дублировать ранг и обходить лимит. */
    static String nameFormat(String format, boolean showRankName) {
        return format == null ? null : format.replace("%rank_tag%", "").strip();
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
                        : 0,
                PresentationContext.of(account, accessManager.getStage(player), nowMillis,
                        accessManager.config().trialEnabled() && accessManager.store().isTrialEligible(player.getName(), player.getUniqueId()),
                        accessManager.config().trialDays(), plugin.messages(), navigation(player)),
                nameDecorator.decorate(player.getName()),
                "");
    }

    private String navigation(Player player) {
        List<String> links = new ArrayList<>();
        if (accessManager.config().serverMenu().enabled() && player.hasPermission("chrdkregister.menu")) links.add("/menu");
        if (player.hasPermission("chrdkregister.labels")) links.add("/labels");
        for (String[] addon : new String[][]{{"CHRDK_DailyRewards", "chrdkdaily.use", "/daily"},
                {"CHRDK_Auction", "chrdkauction.use", "/ah"}, {"CHRDK_Lottery", "chrdklottery.use", "/lottery"}}) {
            if (Bukkit.getPluginManager().isPluginEnabled(addon[0]) && player.hasPermission(addon[1])) links.add(addon[2]);
        }
        return String.join(" • ", links);
    }
}
