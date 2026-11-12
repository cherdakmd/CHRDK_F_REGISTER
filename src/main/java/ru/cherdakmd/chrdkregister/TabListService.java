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
 * %world% %pass_status% %pass_left% %pass_days%}, кадры {@code %tab_*%} и любые
 * внешние плейсхолдеры PlaceholderAPI ({@code %chrdk_*%} и других плагинов).
 * Социальный статус занимает одну строку {@code %chrdksocial_tab%}; старые
 * три плейсхолдера совмещаются с ним рантаймом {@link TabFooterNormalizer},
 * без перезаписи владельческого {@code config.yml}.
 *
 * Кадры {@code %tab_*%} маскируются на время PAPI-прохода: их анимированные
 * значения подставляет {@code Messages.fill}, а не внешний expansion.
 * Значения подстановок экранируются ({@link Messages#escape(Object)}), поэтому
 * ник игрока не сломает разметку. Если {@code player-name-format} пуст — имя в
 * списке не трогаем, кроме активной оплаченной клановой анимации: она обязательна.
 */
public final class TabListService implements Listener {

    /** Команда «вверх таба»: клиент ставит игроков с командой выше остальных, а '!' сортируется первым. */
    static final String PIN_TEAM_NAME = "!chrdk-pin";

    /** PAPI не должен перетирать кадры CHRDK: на время его прохода знаки % снимаем. */
    private static final java.util.regex.Pattern TAB_TOKEN =
            java.util.regex.Pattern.compile("%(tab_[a-z_]+)%");
    private static final char TAB_MASK = '\u0001';

    static String maskTabTokens(String template) {
        return TAB_TOKEN.matcher(template).replaceAll(TAB_MASK + "$1" + TAB_MASK);
    }

    static String unmaskTabTokens(String text) {
        return text.indexOf(TAB_MASK) < 0 ? text : text.replace(TAB_MASK, '%');
    }

    /**
     * Резолвит плейсхолдеры PlaceholderAPI в шаблонах TAB. Внутренние кадры
     * {@code %tab_*%} защищены маской: после PAPI они восстанавливаются и их
     * подставляет Messages.fill с цветной анимацией. Без PAPI или вне сервера
     * шаблон возвращается без изменений — это честный детерминированный fallback.
     */
    static String resolveExternalPlaceholders(String template, Player player) {
        if (template == null || template.indexOf('%') < 0) return template;
        if (player == null) return TabFooterNormalizer.hideUnresolvedSocialLine(template);
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                return TabFooterNormalizer.hideUnresolvedSocialLine(template);
            }
            String masked = maskTabTokens(template);
            String resolved = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, masked);
            if (resolved == null) return TabFooterNormalizer.hideUnresolvedSocialLine(template);
            String unmasked = unmaskTabTokens(resolved);
            // Expansion ещё не зарегистрирован: не показываем сырой %chrdksocial_tab%.
            return unmasked.contains(TabFooterNormalizer.SOCIAL_LINE)
                    ? TabFooterNormalizer.hideUnresolvedSocialLine(unmasked) : unmasked;
        } catch (Throwable ignored) {
            return TabFooterNormalizer.hideUnresolvedSocialLine(template);
        }
    }

    /** Снимок настроек из {@code config.yml}. */
    record Settings(boolean enabled, int refreshTicks, List<String> header, List<String> footer,
                    String nameFormat, List<String> pinnedNames) {
        static Settings disabled() {
            return new Settings(false, 40, List.of(), List.of(), "", List.of());
        }
    }

    /** Значения подстановок одного игрока — ровно то, что знает юнит-тест. */
    record TabContext(String playerName, int online, int maxPlayers, double tps, int ping,
                      String world, String passStatus, String passLeft, long passDaysLeft,
                      PresentationContext presentation, String taggedName, String rankTag,
                      TabDashboard.Frame dashboard, String warnText, String warnsPublic) {
        /** Контекст без счётчика варнов (юнит-тесты и кадры без модерации). */
        TabContext(String playerName, int online, int maxPlayers, double tps, int ping, String world,
                   String passStatus, String passLeft, long passDaysLeft, PresentationContext presentation,
                   String taggedName, String rankTag, TabDashboard.Frame dashboard) {
            this(playerName, online, maxPlayers, tps, ping, world, passStatus, passLeft, passDaysLeft,
                    presentation, taggedName, rankTag, dashboard,
                    "0/" + ModerationLedger.DEFAULT_WARN_LIMIT, "");
        }
        TabContext(String playerName, int online, int maxPlayers, double tps, int ping, String world,
                   String passStatus, String passLeft, long passDaysLeft, PresentationContext presentation,
                   String taggedName, String rankTag) {
            this(playerName, online, maxPlayers, tps, ping, world, passStatus, passLeft, passDaysLeft,
                    presentation, taggedName, rankTag, TabDashboard.Frame.empty());
        }
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
                    "tps", String.format(Locale.ROOT, "%.1f", Double.isFinite(tps) ? Math.min(20.0, Math.max(0.0, tps)) : 0.0),
                    "ping", ping,
                    "world", world,
                    "pass_status", passStatus,
                    "pass_left", passLeft,
                    "pass_days", passDaysLeft,
                    // Ник с метками аддонов и хвост с названием ранга — доверенная
                    // разметка, поэтому Trusted, а не строка.
                    "player_tagged", Messages.trusted(taggedName),
                    "rank_tag", Messages.trusted(rankTag),
                    // Личный счётчик активных варнов и порог автобана.
                    "tab_warns", warnText,
                    // Публичный список нарушителей: один и тот же для всех игроков.
                    "tab_warns_public", warnsPublic};
            // Кадр tab_* идёт сразу после base: чётность legacy-пар не может сдвинуть его ключи.
            Object[] animatedExtra = dashboard.placeholders();
            Object[] legacyExtra = presentation.placeholders();
            Object[] extra = java.util.Arrays.copyOf(animatedExtra, animatedExtra.length + legacyExtra.length);
            System.arraycopy(legacyExtra, 0, extra, animatedExtra.length, legacyExtra.length);
            Object[] result = java.util.Arrays.copyOf(base, base.length + extra.length);
            System.arraycopy(extra, 0, result, base.length, extra.length);
            return result;
        }
    }

    private final RegisterPlugin plugin;
    private final AccessManager accessManager;
    private final Runtime runtime;
    /** Узкий порт для детерминированного прогона реального lifecycle без Minecraft-сервера. */
    interface Runtime {
        java.util.Collection<? extends Player> online();
        Messages messages();
        org.bukkit.configuration.file.FileConfiguration config();
        AccessManager.PlayerStage stage(Player player);
        TabContext context(Player player, long now, boolean withName);
        long now();
        BukkitTask timer(Runnable callback, int period);
        BukkitTask later(Runnable callback, long delay);
        Scoreboard board();
    }
    private final class GameRuntime implements Runtime {
        public java.util.Collection<? extends Player> online() { return Bukkit.getOnlinePlayers(); }
        public Messages messages() { return plugin.messages(); }
        public org.bukkit.configuration.file.FileConfiguration config() { return plugin.getConfig(); }
        public AccessManager.PlayerStage stage(Player player) { return accessManager.getStage(player); }
        public TabContext context(Player player, long now, boolean withName) { return contextOf(player, now, withName); }
        public long now() { return System.currentTimeMillis(); }
        public BukkitTask timer(Runnable callback, int period) { return Bukkit.getScheduler().runTaskTimer(plugin, callback, period, period); }
        public BukkitTask later(Runnable callback, long delay) { return Bukkit.getScheduler().runTaskLater(plugin, callback, delay); }
        public Scoreboard board() { return Bukkit.getScoreboardManager() == null ? null : Bukkit.getScoreboardManager().getMainScoreboard(); }
    }
    private Settings settings = Settings.disabled();
    private BukkitTask refreshTask;
    private BukkitTask animationTask;
    private BukkitTask rosterTask;
    private boolean headerNeedsName;
    private final java.util.Map<java.util.UUID, AccessManager.PlayerStage> presentedStages = new java.util.HashMap<>();
    private java.util.function.Predicate<String> animationPredicate = name -> false;
    private final java.util.Set<java.util.UUID> animatedNames = new java.util.HashSet<>();

    public void setAnimationPredicate(java.util.function.Predicate<String> predicate) {
        animationPredicate = predicate == null ? name -> false : predicate;
    }
    static int animationTicks(int ticks) { return Math.clamp(ticks, 5, 20); }
    static int refreshTicks(int ticks) { return ticks <= 0 ? 0 : Math.max(10, ticks); }
    static boolean animationEnabled(Settings settings) {
        return settings.enabled();
    }
    private record Headers(Component header, Component footer) { }
    private final java.util.Map<java.util.UUID, Headers> headerFrames = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, TabFrameState> nameFrames = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, BukkitTask> audits = new java.util.HashMap<>();
    private long pulse;
    private boolean dashboardAnimation;
    private final java.util.Set<java.util.UUID> managedNames = new java.util.HashSet<>();
    private final java.util.Set<java.util.UUID> managedHeaders = new java.util.HashSet<>();
    /** Оформление ника из ядра (тег каталога + метки аддонов) для {@code %player_tagged%}. */
    private volatile NameDecorator nameDecorator = Messages::escape;
    /** 1.4.183: личные переключатели надписей; {@code null} — всё видно. */
    private volatile LabelPreferences labelPreferences;

    public TabListService(RegisterPlugin plugin, AccessManager accessManager) {
        this.plugin = plugin;
        this.accessManager = accessManager;
        this.runtime = new GameRuntime();
    }
    TabListService(Runtime runtime) {
        this.plugin = null;
        this.accessManager = null;
        this.runtime = java.util.Objects.requireNonNull(runtime);
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
        var cfg = runtime.config();
        String root = "tablist";
        this.settings = new Settings(
                cfg.getBoolean(root + ".enabled", true),
                refreshTicks(cfg.getInt(root + ".refresh-ticks", 40)),
                cfg.getStringList(root + ".header"),
                TabFooterNormalizer.normalizeFooter(cfg.getStringList(root + ".footer")),
                cfg.getString(root + ".player-name-format", ""),
                // tablist.show-rank-name читает RegisterPlugin → ChatService.setRankNameInTab;
                // второй parse в Settings был мёртвым полем и удалён.
                cfg.getStringList(root + ".pinned-names"));
        headerNeedsName = java.util.stream.Stream.concat(settings.header().stream(), settings.footer().stream())
                .anyMatch(line -> line.contains("%player_tagged%"));
        dashboardAnimation = cfg.getBoolean(root + ".dashboard-animation", true);
        reschedule();
        syncPinned();
        if (settings.enabled()) {
            applyAll();
        } else {
            resetAll();
        }
    }

    public void shutdown() {
        settings = Settings.disabled();
        cancelTasks();
        resetAll();
        clearPinned();
    }

    private void cancelTasks() {
        if (animationTask != null) { animationTask.cancel(); animationTask = null; }
        if (refreshTask != null) { refreshTask.cancel(); refreshTask = null; }
        if (rosterTask != null) { rosterTask.cancel(); rosterTask = null; }
        audits.values().forEach(BukkitTask::cancel); audits.clear();
    }
    private void scheduleRosterRefresh() {
        if (!settings.enabled() || rosterTask != null) return;
        rosterTask = runtime.later(() -> { rosterTask = null; applyAll(); }, 1L);
    }
    @EventHandler
    public void onJoin(PlayerJoinEvent event) { scheduleRosterRefresh(); }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        var id = event.getPlayer().getUniqueId();
        animatedNames.remove(id); nameFrames.remove(id); headerFrames.remove(id); presentedStages.remove(id);
        BukkitTask audit = audits.remove(id); if (audit != null) audit.cancel();
        managedNames.remove(id); managedHeaders.remove(id);
        scheduleRosterRefresh();
    }

    private void reschedule() {
        cancelTasks();
        // Сначала освобождаем ещё принадлежащие нам кадры; чужую перезапись не стираем.
        resetAll(); pulse = 0;
        clearPinned();
        if (settings.enabled() && settings.refreshTicks() > 0)
            refreshTask = runtime.timer(this::applyAll, settings.refreshTicks());
        if (animationEnabled(settings)) {
            int period = animationTicks(runtime.config().getInt("tablist.animation-refresh-ticks", 5));
            animationTask = runtime.timer(this::animateNames, period);
        }
    }

    /** Один общий heartbeat. Смена стадии входа обновляет приватные поля даже при refresh=0. */
    private void animateNames() {
        if (!settings.enabled()) return;
        long now = runtime.now(); pulse++;
        for (Player player : runtime.online()) {
            boolean active = animationPredicate.test(player.getName());
            boolean previous = animatedNames.contains(player.getUniqueId());
            var stage = runtime.stage(player);
            boolean changedStage = stage != presentedStages.get(player.getUniqueId());
            update(player, now, active || previous || changedStage || pulse % 8 == 0,
                    changedStage || dashboardAnimation && pulse % 2 == 0, active, stage);
        }
    }

    private void applyAll() {
        if (!settings.enabled()) return;
        ensurePinned();
        for (Player player : runtime.online()) {
            apply(player);
        }
    }

    private void resetAll() {
        for (Player player : runtime.online()) {
            releaseName(player);
            Headers previous = headerFrames.get(player.getUniqueId());
            if (managedHeaders.contains(player.getUniqueId()) && previous != null) {
                Headers actual = actualHeaders(player);
                // Header/footer могут иметь разных владельцев: освобождаем каждую часть отдельно.
                Headers released = new Headers(previous.header().equals(actual.header()) ? Component.empty() : actual.header(),
                        previous.footer().equals(actual.footer()) ? Component.empty() : actual.footer());
                if (!released.equals(actual)) player.sendPlayerListHeaderAndFooter(released.header(), released.footer());
            }
        }
        animatedNames.clear(); nameFrames.clear(); headerFrames.clear(); presentedStages.clear();
        managedNames.clear(); managedHeaders.clear();
    }
    private void apply(Player player) {
        update(player, runtime.now(), true, true, animationPredicate.test(player.getName()), runtime.stage(player));
    }
    private void update(Player player, long now, boolean name, boolean header, boolean active, AccessManager.PlayerStage stage) {
        String format = effectiveNameFormat(settings.nameFormat(), active);
        boolean nameContext = name && format != null && !format.isBlank() && !format.equals("%player_tagged%");
        boolean headerContext = header && visible(player, LabelPreferences.Label.HEADER)
                && (!settings.header().isEmpty() || !settings.footer().isEmpty());
        TabContext context = nameContext || headerContext ? runtime.context(player, now,
                nameContext && format.contains("%player_tagged%") || headerContext && headerNeedsName) : null;
        if (name) applyName(player, active, context, format);
        if (header) applyHeaders(player, context, stage);
    }
    private static Headers actualHeaders(Player player) {
        return new Headers(java.util.Objects.requireNonNullElse(player.playerListHeader(), Component.empty()),
                java.util.Objects.requireNonNullElse(player.playerListFooter(), Component.empty()));
    }
    private void applyHeaders(Player player, TabContext context, AccessManager.PlayerStage stage) {
        boolean show = visible(player, LabelPreferences.Label.HEADER);
        Messages messages = runtime.messages();
        Headers next = new Headers(show && !settings.header().isEmpty() ? joinLines(renderLines(messages, settings.header(), context, player)) : Component.empty(),
                show && !settings.footer().isEmpty() ? joinLines(renderLines(messages, settings.footer(), context, player)) : Component.empty());
        if (!next.equals(headerFrames.put(player.getUniqueId(), next)) || !next.equals(actualHeaders(player)))
            player.sendPlayerListHeaderAndFooter(next.header(), next.footer());
        managedHeaders.add(player.getUniqueId());
        presentedStages.put(player.getUniqueId(), stage);
    }
    static String effectiveNameFormat(String configured, boolean activeClanAnimation) {
        // Внешняя обёртка-gradient или формат без player_tagged не могут стереть оплаченный кадр.
        return activeClanAnimation ? "%player_tagged%" : nameFormat(configured, true);
    }
    private void applyName(Player player, boolean active, TabContext context, String format) {
        // Общий refresh/join тоже фиксирует состояние: эффект может закончиться ДО первого heartbeat.
        if (active) animatedNames.add(player.getUniqueId()); else animatedNames.remove(player.getUniqueId());
        if (format == null || format.isBlank()) releaseName(player);
        else deliverName(player, format.equals("%player_tagged%")
                ? runtime.messages().markup(nameDecorator.decorate(player.getName()))
                : renderName(runtime.messages(), format, context, player));
    }
    private void releaseName(Player player) {
        var id = player.getUniqueId();
        TabFrameState previous = nameFrames.remove(id);
        if (managedNames.remove(id) && previous != null && !previous.needsDelivery(player.playerListName())) player.playerListName(null);
    }
    private void deliverName(Player player, Component name) {
        Component actual = player.playerListName();
        TabFrameState next = TabFrameState.next(nameFrames.get(player.getUniqueId()), name, actual);
        nameFrames.put(player.getUniqueId(), next);
        if (next.needsDelivery(actual)) player.playerListName(name);
        managedNames.add(player.getUniqueId());
    }

    /** Консоль/admin: фактические серверные кадры за 30 тиков, не обещание клиентского FPS. */
    void audit(org.bukkit.command.CommandSender sender, Player target) {
        Messages messages = runtime.messages();
        if (!sender.hasPermission("chrdkregister.admin")) { messages.send(sender, "common.no-permission"); return; }
        if (target == null || !target.isOnline()) { messages.send(sender, "tab.audit-offline"); return; }
        var id = target.getUniqueId();
        if (audits.containsKey(id) || audits.size() >= 16) { messages.send(sender, "tab.audit-busy"); return; }
        if (settings.enabled()) apply(target);
        TabFrameState before = nameFrames.get(id);
        messages.send(sender, "tab.audit-start", "player", target.getName());
        audits.put(id, runtime.later(() -> {
            audits.remove(id);
            if (!sender.hasPermission("chrdkregister.admin")) return;
            if (!target.isOnline()) { runtime.messages().send(sender, "tab.audit-offline"); return; }
            String state = TabFrameState.audit(settings.enabled(), animationPredicate.test(target.getName()),
                    before, nameFrames.get(id), target.playerListName());
            String stateKey = "tab.audit-" + state;
            runtime.messages().send(sender, "tab.audit-result", "player", target.getName(),
                    "state", runtime.messages().plain(stateKey));
        }, 30L));
    }

    record CacheSizes(int names, int headers, int stages, int animated, int audits) { }
    CacheSizes cacheSizes() { return new CacheSizes(nameFrames.size(), headerFrames.size(), presentedStages.size(), animatedNames.size(), audits.size()); }

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
                String exact = runtime.online().stream().map(Player::getName)
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
        for (Player player : runtime.online()) {
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

    private Scoreboard mainScoreboard() { return runtime.board(); }

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
        return renderLines(messages, templates, context, null);
    }

    static List<Component> renderLines(Messages messages, List<String> templates, TabContext context, Player player) {
        if (templates.isEmpty()) return List.of();
        List<Component> out = new ArrayList<>(templates.size());
        Object[] placeholders = context.placeholders();
        for (String template : templates) {
            // «Вы в игре…» под строкой «в игре │ 2FA» — исторический дубль.
            if (TabFooterNormalizer.isAuthHintOnlyLine(template)
                    && TabFooterNormalizer.isRedundantAuthenticatedHint(context.presentation())) {
                continue;
            }
            String resolved = resolveExternalPlaceholders(template, player);
            if (resolved != null && resolved.contains(TabFooterNormalizer.HIDE_LINE)) continue;
            out.add(messages.renderTemplate(resolved, placeholders));
        }
        return out;
    }

    static Component renderName(Messages messages, String format, TabContext context) {
        return renderName(messages, format, context, null);
    }

    static Component renderName(Messages messages, String format, TabContext context, Player player) {
        return messages.renderTemplate(resolveExternalPlaceholders(format, player), context.placeholders());
    }

    /** Старый отдельный %rank_tag% не должен дублировать ранг и обходить лимит. */
    static String nameFormat(String format, boolean showRankName) {
        return format == null ? null : format.replace("%rank_tag%", "").strip();
    }

    // ------------------------------ сборка контекста -----------------------------

    private TabContext contextOf(Player player, long nowMillis, boolean withName) {
        RegistrationStore.PlayerAccount account =
                accessManager.store().getAccount(player.getName()).orElse(null);
        double tps;
        try {
            tps = Bukkit.getTPS()[0];
        } catch (Throwable ignored) {
            tps = Double.NaN;
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
                withName ? nameDecorator.decorate(player.getName()) : Messages.escape(player.getName()),
                "", TabDashboard.frame(plugin.messages(), nowMillis,
                        accessManager.getStage(player) == AccessManager.PlayerStage.AUTHENTICATED,
                        accessManager.getStage(player) == AccessManager.PlayerStage.AUTHENTICATED && plugin.bank() != null && plugin.bank().enabled()
                                ? plugin.bank().balance(player) : -1,
                        accessManager.getStage(player) == AccessManager.PlayerStage.AUTHENTICATED ? accessManager.store().rewardBoostRemaining(player.getName()) : 0,
                        tps, Bukkit.getAverageTickTime(), player.getPing(), worldName(player)),
                plugin.moderation() == null
                        ? "0/" + ModerationLedger.DEFAULT_WARN_LIMIT
                        : plugin.moderation().warnText(player.getName()),
                plugin.moderation() == null ? "" : plugin.moderation().warnsPublicText());

    }

    private String worldName(Player player) {
        return plugin.messages().plain(switch (player.getWorld().getEnvironment()) {
            case NORMAL -> "tab.world-normal"; case NETHER -> "tab.world-nether"; case THE_END -> "tab.world-end";
            default -> "tab.world-other";
        });
    }

    private String navigation(Player player) {
        List<String> links = new ArrayList<>();
        if (accessManager.config().serverMenu().enabled() && player.hasPermission("chrdkregister.menu")) links.add("/menu");
        if (player.hasPermission("chrdkregister.bal")) links.add("/bal");
        if (player.hasPermission("chrdkregister.labels")) links.add("/labels");
        for (String[] addon : new String[][]{{"CHRDK_DiamondShop", "chrdkshop.use", "/shop"}, {"CHRDK_DailyRewards", "chrdkdaily.use", "/daily"},
                {"CHRDK_Orders", "chrdkorders.use", "/orders"}}) {
            if (Bukkit.getPluginManager().isPluginEnabled(addon[0]) && player.hasPermission(addon[1])) links.add(addon[2]);
        }
        return String.join(" • ", links);
    }
}
