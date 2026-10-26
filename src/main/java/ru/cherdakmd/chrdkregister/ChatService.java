package ru.cherdakmd.chrdkregister;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Built-in chat system: local/global channels, mentions, anti-spam protection,
 * private messages with social spy, per-player ignore lists, and rotating automatic messages.
 */
public final class ChatService implements Listener {
    public static final String PERMISSION_COLOR = "chrdkregister.chat.color";
    public static final String PERMISSION_BYPASS = "chrdkregister.chat.bypass";
    public static final String PERMISSION_SOCIAL_SPY = "chrdkregister.chat.socialspy";
    public static final String PERMISSION_CLEAR = "chrdkregister.chat.clear";
    public static final String PERMISSION_BROADCAST = "chrdkregister.chat.broadcast";
    public static final String PERMISSION_AUTO_MESSAGE = "chrdkregister.automessage.receive";
    public static final String PERMISSION_MUTE = "chrdkregister.chat.mute";
    public static final String PERMISSION_HISTORY = "chrdkregister.chat.history";

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String MESSAGE_PLACEHOLDER = "%message%";
    /** Ключ префикса объявления в messages.yml. */
    private static final String ANNOUNCEMENT_PREFIX_KEY = "chat.broadcast-prefix";
    private static final int MIN_CAPS_LETTERS = 6;
    private static final int MAX_MENTION_LENGTH = 16;
    private static final int CHAT_CLEAR_LINES = 100;

    private final JavaPlugin plugin;
    private final AccessManager accessManager;
    private final Logger logger;

    private volatile ChatConfig config;
    private volatile Messages messages;
    private final Map<UUID, ChatConfig.Channel> channels = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMessageAt = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastMessageText = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> lastPrivatePartners = new ConcurrentHashMap<>();
    /** Оформление ника из каталога товаров (цветной ник, плашка мецената). */
    private volatile java.util.function.Function<String, String> tagResolver = name -> "";
    /** Оформление ников от аддонов (метка стримера и т.п.). */
    private final NameTags nameTags;
    /** 1.4.183: что игрок показывает в своём нике; {@code null} — всё видно. */
    private volatile LabelPreferences labelPreferences;
    private final Map<UUID, Set<String>> ignoreCache = new ConcurrentHashMap<>();
    private final Set<UUID> socialSpy = ConcurrentHashMap.newKeySet();

    private BukkitTask autoMessageTask;
    private int autoMessageIndex;
    private String lastAutoMessage = "";
    /** Последние отправленные автосообщения: случайный выбор не повторяет их подряд. */
    private final java.util.Deque<String> recentAutoMessages = new java.util.ArrayDeque<>();
    /** Дополнительные подстановки от других сервисов ядра (баланс банка и т.п.). */
    private volatile java.util.function.BiConsumer<Player, java.util.Map<String, Object>> placeholderProvider;
    private volatile long lastGlobalMessageAt;

    public ChatService(JavaPlugin plugin, AccessManager accessManager, ChatConfig config,
                       Messages messages, Logger logger) {
        this.plugin = plugin;
        this.accessManager = accessManager;
        this.config = config;
        this.messages = messages;
        this.logger = logger;
        this.nameTags = new NameTags(logger);
    }

    /**
     * Подключает источник оформления ников: ник → MiniMessage-тег (пусто — без оформления).
     * Вызывается один раз при включении плагина и переживает {@code /pass reload}.
     */
    public void setTagResolver(java.util.function.Function<String, String> resolver) {
        this.tagResolver = resolver == null ? name -> "" : resolver;
    }

    /** Ник с оформлением товара и метками аддонов: {@code <gold>Nick</gold> [СТРИМ]}. */
    public String decoratedName(String playerName) {
        return decoratedName(playerName,
                labelVisible(playerName, LabelPreferences.Category.RANK),
                labelVisible(playerName, LabelPreferences.Category.BADGES));
    }

    /**
     * Ник с явно заданными флагами надписей (1.4.183).
     *
     * <p>Таб-лист передаёт флаги сам: он уже знает личные переключатели игрока.
     * Остальной код (чат, личные сообщения) пользуется вариантом без флагов —
     * он читает те же настройки из {@link LabelPreferences}.</p>
     *
     * @param showRank   показывать название и знак донатного ранга
     * @param showBadges показывать метки аддонов (класс, гильдия, стример, звание)
     */
    public String decoratedName(String playerName, boolean showRank, boolean showBadges) {
        java.util.function.Function<String, String> resolver = tagResolver;
        String tag = resolver == null ? "" : resolver.apply(playerName);
        String base = ShopEntitlements.decorate(playerName, tag);
        String withRank = showRank
                ? decorateRank(playerName, base, tag != null && !tag.isBlank())
                : base;
        return showBadges ? nameTags.decorate(playerName, withRank) : withRank;
    }

    /** Личный переключатель надписей: без хранилища — всё видно (1.4.183). */
    private boolean labelVisible(String playerName, LabelPreferences.Category category) {
        LabelPreferences preferences = labelPreferences;
        return preferences == null || preferences.visible(playerName, category);
    }

    /** Подключает личные переключатели надписей (1.4.183). */
    public void setLabelPreferences(LabelPreferences preferences) {
        this.labelPreferences = preferences;
    }

    /**
     * Оформление ника донатным рангом (1.4.167): знак перед ником и цвет ступени.
     *
     * <p>Оплаченный ранее тег каталога ({@code nick-color}, {@code sponsor}) главнее
     * цвета ранга, пока не истёк: купленное вперёд не отбираем. Знак ранга при этом
     * остаётся — он и есть визитная карточка ступени.</p>
     */
    private String decorateRank(String playerName, String name, boolean hasLegacyTag) {
        RankSettings ranks = accessManager.config().ranks();
        if (ranks == null || !ranks.enabled()) {
            return name;
        }
        int level = accessManager.store().rankLevel(playerName, System.currentTimeMillis());
        RankTier tier = level <= 0 ? null : ranks.tier(level);
        if (tier == null) {
            return name;
        }
        String decorated = hasLegacyTag ? name : tier.decorate(name);
        String prefix = tier.badge().isBlank() ? "" : tier.badge() + " ";
        if (ranks.showNameInChat()) {
            return prefix + decorated + " <dark_gray>·</dark_gray> " + tier.decorate(tier.name());
        }
        return prefix + decorated;
    }

    /** Реестр оформления ников — его получает реализация публичного API. */
    NameTags nameTags() {
        return nameTags;
    }

    private String decorated(String playerName) {
        return decoratedName(playerName);
    }

    /** Заменяет тексты из {@code messages.yml} (используется при {@code /pass reload}). */
    public void setMessages(Messages messages) {
        if (messages != null) {
            this.messages = messages;
        }
    }

    public ChatConfig config() {
        return config;
    }

    private NotificationService notifications() {
        return accessManager.notifications();
    }


    public synchronized void start() {
        stopTasks();
        this.autoMessageIndex = 0;
        scheduleAutoMessages();
    }

    public synchronized void shutdown() {
        stopTasks();
        channels.clear();
        lastMessageAt.clear();
        lastMessageText.clear();
        lastPrivatePartners.clear();
        ignoreCache.clear();
        socialSpy.clear();
    }

    public synchronized void reload(ChatConfig newConfig) {
        this.config = newConfig;
        stopTasks();
        scheduleAutoMessages();
    }

    private void stopTasks() {
        if (autoMessageTask != null) {
            autoMessageTask.cancel();
            autoMessageTask = null;
        }
    }

    /* ------------------------------------------------------------------ *
     *  Chat messages (local / global)
     * ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        ChatConfig cfg = this.config;
        if (!cfg.enabled()) return;

        Player player = event.getPlayer();
        if (accessManager.getStage(player) != AccessManager.PlayerStage.AUTHENTICATED) {
            // Players without a pass or without login are blocked by PlayerRestrictionListener.
            return;
        }

        RegistrationStore store = store();
        long now = System.currentTimeMillis();
        RegistrationStore.MuteEntry mute = store.getMute(player.getName(), now).orElse(null);
        if (mute != null) {
            event.setCancelled(true);
            messages.send(player, "mute.cannot-write",
                    "duration", AccessManager.describeRemaining(mute.until - now),
                    "reason", mute.reason == null || mute.reason.isBlank()
                            ? "" : messages.mini("mute.notice-reason", "reason", mute.reason));
            return;
        }

        String raw = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (raw.isEmpty()) {
            event.setCancelled(true);
            return;
        }

        String quickPrefix = cfg.quickGlobalPrefix();
        boolean forcedGlobal = !quickPrefix.isEmpty() && raw.startsWith(quickPrefix);
        String text = forcedGlobal ? raw.substring(quickPrefix.length()).trim() : raw;
        if (text.isEmpty()) {
            text = raw;
        }
        ChatConfig.Channel channel = forcedGlobal ? ChatConfig.Channel.GLOBAL : currentChannel(player);

        boolean bypass = player.hasPermission(PERMISSION_BYPASS);
        UUID uuid = player.getUniqueId();

        if (!bypass && cfg.maxMentions() > 0 && countMentions(text) > cfg.maxMentions()) {
            event.setCancelled(true);
            messages.send(player, "chat.too-many-mentions", "max_mentions", cfg.maxMentions());
            return;
        }

        if (!bypass && cfg.slowModeSeconds() > 0 && channel == ChatConfig.Channel.GLOBAL) {
            long elapsed = now - lastGlobalMessageAt;
            long waitMillis = cfg.slowModeSeconds() * 1000L - elapsed;
            if (lastGlobalMessageAt > 0 && waitMillis > 0) {
                event.setCancelled(true);
                messages.send(player, "chat.slow-mode-wait", "wait_millis", Math.max(1, (waitMillis + 999) / 1000));
                return;
            }
        }

        String filtered = applyFilter(player, text, cfg, bypass);
        if (filtered == null) {
            event.setCancelled(true);
            return;
        }
        text = filtered;

        if (!bypass && text.length() > cfg.maxMessageLength()) {
            event.setCancelled(true);
            messages.send(player, "chat.too-long", "max_message_length", cfg.maxMessageLength());
            return;
        }

        if (!bypass && cfg.antiCaps()) {
            String lowered = applyCapsRule(text, cfg.capsPercent());
            if (!lowered.equals(text)) {
                text = lowered;
                messages.send(player, "chat.caps-fixed");
            }
        }

        if (!bypass && cfg.blockDuplicates() && cfg.duplicateWindowMillis() > 0) {
            String previous = lastMessageText.get(uuid);
            long previousAt = lastMessageAt.getOrDefault(uuid, 0L);
            if (previous != null && now - previousAt <= cfg.duplicateWindowMillis() && isDuplicate(previous, text)) {
                event.setCancelled(true);
                messages.send(player, "chat.duplicate");
                return;
            }
        }

        if (!bypass && cfg.cooldownMillis() > 0) {
            long last = lastMessageAt.getOrDefault(uuid, 0L);
            long remaining = cfg.cooldownMillis() - (now - last);
            if (last > 0 && remaining > 0) {
                event.setCancelled(true);
                messages.send(player, "chat.cooldown", "remaining", formatSeconds(remaining));
                return;
            }
        }

        lastMessageAt.put(uuid, now);
        lastMessageText.put(uuid, text);

        Set<String> viewerNames = new HashSet<>();
        List<Player> mentionCandidates = new ArrayList<>();
        for (Audience audience : event.viewers()) {
            if (audience instanceof Player viewer) {
                viewerNames.add(viewer.getName().toLowerCase(Locale.ROOT));
                if (!viewer.getUniqueId().equals(uuid)) {
                    mentionCandidates.add(viewer);
                }
            }
        }

        boolean colorAllowed = cfg.allowColorCodes() && player.hasPermission(PERMISSION_COLOR);
        String format = channel == ChatConfig.Channel.GLOBAL ? cfg.globalFormat() : cfg.localFormat();
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("%player%", decorated(player.getName()));
        placeholders.put("%world%", player.getWorld().getName());
        String[] segments = splitFormat(format, placeholders);
        NamedTextColor bodyColor = extractTrailingColor(segments[0], NamedTextColor.WHITE);
        Component body = buildMessageBody(
                text,
                colorAllowed,
                cfg,
                cfg.mentionHighlight(),
                bodyColor,
                candidate -> viewerNames.contains(candidate.toLowerCase(Locale.ROOT)),
                messages
        );
        Component line = legacy(segments[0]).append(body).append(legacy(segments[1]));

        event.renderer((source, sourceDisplayName, message, viewer) -> line);
        event.viewers().removeIf(audience -> !canSee(audience, player, channel, cfg));

        if (channel == ChatConfig.Channel.GLOBAL) {
            lastGlobalMessageAt = now;
        }
        if (cfg.chatLogEnabled()) {
            accessManager.audit().chat(channel == ChatConfig.Channel.GLOBAL ? "global" : "local",
                    player.getName(), null, text);
        }
        notifyMentions(player, text, mentionCandidates, cfg);
    }

    /** Counts {@code @nickname} mentions in a message. */
    static int countMentions(String text) {
        if (text == null || text.isEmpty()) return 0;
        int count = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) != '@') continue;
            if (index + 1 >= text.length() || !isNameCharacter(text.charAt(index + 1))) continue;
            count++;
        }
        return count;
    }

    /**
     * Applies the moderation filter.
     *
     * @return the (possibly rewritten) text, or {@code null} when the message must be blocked
     */
    private String applyFilter(Player player, String text, ChatConfig cfg, boolean bypass) {
        ChatConfig.ChatFilter filter = cfg.filter();
        if (bypass || filter == null || !filter.enabled() || text.isEmpty()) return text;
        String lowered = text.toLowerCase(Locale.ROOT);
        if (filter.isWhitelisted(lowered)) return text;

        boolean matched = false;
        for (String word : filter.words()) {
            if (!word.isBlank() && lowered.contains(word)) {
                matched = true;
                break;
            }
        }
        if (!matched) {
            for (var pattern : filter.patterns()) {
                if (pattern.matcher(text).find()) {
                    matched = true;
                    break;
                }
            }
        }
        if (!matched) return text;

        if (cfg.filterNotifyAdmins()) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.hasPermission(PERMISSION_MUTE)) {
                    messages.send(online, "chat.filter-admin-note", "player", player.getName());
                }
            }
        }
        accessManager.audit().log(AuditLog.CHAT_FILTER, player.getName(), "", null,
                AuditLog.details("message", text.length() > 120 ? text.substring(0, 120) + "…" : text,
                        "mode", filter.blockMode() ? "block" : "replace"));
        if (filter.blockMode()) {
            messages.send(player, "chat.filter-blocked");
            return null;
        }
        return maskMatches(text, filter);
    }

    private static String maskMatches(String text, ChatConfig.ChatFilter filter) {
        String result = text;
        for (String word : filter.words()) {
            if (word.isBlank()) continue;
            result = replaceIgnoreCase(result, word, "*".repeat(Math.max(3, word.length())));
        }
        for (var pattern : filter.patterns()) {
            result = pattern.matcher(result).replaceAll("***");
        }
        return result;
    }

    private static String replaceIgnoreCase(String source, String needle, String replacement) {
        StringBuilder result = new StringBuilder();
        String lowered = source.toLowerCase(Locale.ROOT);
        String loweredNeedle = needle.toLowerCase(Locale.ROOT);
        int index = 0;
        while (true) {
            int found = lowered.indexOf(loweredNeedle, index);
            if (found < 0) {
                result.append(source, index, source.length());
                break;
            }
            result.append(source, index, found).append(replacement);
            index = found + needle.length();
        }
        return result.toString();
    }

    private boolean canSee(Audience audience, Player sender, ChatConfig.Channel channel, ChatConfig cfg) {
        if (!(audience instanceof Player viewer)) return true;
        if (viewer.getUniqueId().equals(sender.getUniqueId())) return true;
        if (isIgnoring(viewer, sender.getName())) return false;
        if (channel == ChatConfig.Channel.GLOBAL) return true;

        boolean sameWorld = viewer.getWorld().equals(sender.getWorld());
        if (!sameWorld) return cfg.localCrossWorld();
        double radius = cfg.localRadius();
        if (radius <= 0) return true;
        double distanceSquared = viewer.getLocation().distanceSquared(sender.getLocation());
        return distanceSquared <= radius * radius;
    }

    private void notifyMentions(Player sender, String text, List<Player> candidates, ChatConfig cfg) {
        if (candidates.isEmpty()) return;
        if (!cfg.mentionHighlight() && !cfg.mentionSound()) return;

        List<Player> mentioned = new ArrayList<>();
        for (Player candidate : candidates) {
            if (hasMention(text, candidate.getName()) && !isIgnoring(candidate, sender.getName())) {
                mentioned.add(candidate);
            }
        }
        if (mentioned.isEmpty()) return;

        String senderName = sender.getName();
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player viewer : mentioned) {
                if (!viewer.isOnline()) continue;
                if (cfg.mentionHighlight()) {
                    viewer.sendActionBar(messages.get("chat.mention-notify", "sender_name", senderName));
                }
                if (cfg.mentionSound()) {
                    viewer.playSound(viewer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.5f);
                }
            }
        });
    }

    /* ------------------------------------------------------------------ *
     *  Join / quit notifications and state cleanup
     * ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        ChatConfig cfg = this.config;
        if (!cfg.enabled() || !cfg.joinQuitMessages()) return;
        Player player = event.getPlayer();
        Component line = legacy(applyPlaceholders(cfg.joinMessage(), Map.of("%player%", player.getName())));
        Bukkit.getScheduler().runTask(plugin, () -> broadcastToAuthenticated(line, player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        ChatConfig cfg = this.config;
        if (cfg.enabled() && cfg.joinQuitMessages()) {
            Component line = legacy(applyPlaceholders(cfg.quitMessage(), Map.of("%player%", player.getName())));
            Bukkit.getScheduler().runTask(plugin, () -> broadcastToAuthenticated(line, null));
        }

        UUID uuid = player.getUniqueId();
        channels.remove(uuid);
        lastMessageAt.remove(uuid);
        lastMessageText.remove(uuid);
        lastPrivatePartners.remove(uuid);
        ignoreCache.remove(uuid);
        socialSpy.remove(uuid);
    }

    private void broadcastToAuthenticated(Component line, Player excluded) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (excluded != null && online.getUniqueId().equals(excluded.getUniqueId())) continue;
            if (accessManager.getStage(online) != AccessManager.PlayerStage.AUTHENTICATED) continue;
            online.sendMessage(line);
        }
    }

    /* ------------------------------------------------------------------ *
     *  Automatic messages
     * ------------------------------------------------------------------ */

    /**
     * Дополнительные подстановки для автосообщений: сервис получает игрока и
     * карту подстановок и может добавить свои (например, {@code balance}).
     */
    public void setPlaceholderProvider(java.util.function.BiConsumer<Player, java.util.Map<String, Object>> provider) {
        this.placeholderProvider = provider;
    }

    private void scheduleAutoMessages() {
        ChatConfig cfg = this.config;
        if (!cfg.enabled() || !cfg.autoMessagesEnabled() || cfg.autoMessageEntries().isEmpty()) return;
        long intervalTicks = Math.max(20L * 30L, cfg.autoMessagesIntervalSeconds() * 20L);
        autoMessageTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::sendNextAutoMessage, intervalTicks, intervalTicks);
        logger.info("Automatic chat messages enabled: every " + cfg.autoMessagesIntervalSeconds()
                + " second(s), " + cfg.autoMessageEntries().size() + " message(s).");
    }

    /** Сколько автосообщений настроено (для {@code /pass automessages list}). */
    public int autoMessageCount() {
        return config.autoMessageEntries().size();
    }

    /** Настроенные автосообщения в порядке конфига. */
    public List<AutoMessage> autoMessageEntries() {
        return config.autoMessageEntries();
    }

    /**
     * Принудительно отправляет следующее автосообщение всей аудитории.
     *
     * @return сколько игроков его получили (0 — некому или нет подходящих текстов)
     */
    public int broadcastAutoMessageNow() {
        return sendNextAutoMessage();
    }

    /**
     * Отправляет конкретное сообщение одному игроку в обход условий — это
     * предпросмотр для администратора, а не рассылка.
     *
     * @param index номер в списке, начиная с 1
     * @return {@code true}, если сообщение найдено и отправлено
     */
    public boolean previewAutoMessage(Player player, int index) {
        List<AutoMessage> entries = config.autoMessageEntries();
        if (player == null || index < 1 || index > entries.size()) {
            return false;
        }
        deliver(entries.get(index - 1), player, Bukkit.getOnlinePlayers().size());
        return true;
    }

    private int sendNextAutoMessage() {
        ChatConfig cfg = this.config;
        if (!cfg.enabled() || !cfg.autoMessagesEnabled()) return 0;
        List<? extends Player> audience = Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission(PERMISSION_AUTO_MESSAGE)
                        && accessManager.getStage(p) == AccessManager.PlayerStage.AUTHENTICATED).toList();
        if (audience.isEmpty() || audience.size() < cfg.autoMessagesMinimumPlayers()) return 0;
        int online = audience.size();
        List<AutoMessage> candidates = cfg.autoMessageEntries().stream()
                .filter(entry -> audience.stream().anyMatch(p -> eligible(entry, p, online))).toList();
        if (candidates.isEmpty()) return 0;
        AutoMessage chosen = pick(candidates);
        lastAutoMessage = chosen.text();
        rememberSent(chosen.text());
        int delivered = 0;
        for (Player player : audience) {
            if (!eligible(chosen, player, online)) continue;
            deliver(chosen, player, online);
            delivered++;
        }
        return delivered;
    }

    /** Выбор сообщения: по кругу или случайно, без повторов подряд. */
    private AutoMessage pick(List<AutoMessage> candidates) {
        if (!config.autoMessagesRandomOrder()) {
            AutoMessage chosen = candidates.get(Math.floorMod(autoMessageIndex++, candidates.size()));
            // Список кандидатов мог сузиться (игрок без аддона) — не повторяем предыдущее.
            if (candidates.size() > 1 && chosen.text().equals(lastAutoMessage)) {
                chosen = candidates.get(Math.floorMod(autoMessageIndex++, candidates.size()));
            }
            return chosen;
        }
        List<AutoMessage> fresh = candidates.stream()
                .filter(entry -> !recentAutoMessages.contains(entry.text())).toList();
        List<AutoMessage> pool = fresh.isEmpty() ? candidates : fresh;
        List<AutoMessage> different = pool.stream()
                .filter(entry -> !entry.text().equals(lastAutoMessage)).toList();
        if (!different.isEmpty()) {
            pool = different;
        }
        return pool.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(pool.size()));
    }

    /** Хранит до пяти последних текстов — этого хватает, чтобы не повторяться. */
    private void rememberSent(String text) {
        recentAutoMessages.addLast(text);
        while (recentAutoMessages.size() > 5) {
            recentAutoMessages.removeFirst();
        }
    }

    /** Подстановки автосообщения: базовые + от других сервисов (баланс банка). */
    private void deliver(AutoMessage message, Player player, int online) {
        ChatConfig cfg = this.config;
        var account = accessManager.store().getAccount(player.getName()).orElse(null);
        long now = System.currentTimeMillis();
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("online", online);
        values.put("max", Bukkit.getMaxPlayers());
        values.put("player", player.getName());
        values.put("world", player.getWorld().getName());
        values.put("pass_left", PlaceholderValues.passLeft(account, now));
        values.put("pass_days", account == null ? 0 : PlaceholderValues.wholeDaysLeft(account.passUntil, now));
        values.put("trial_days", accessManager.config().trialDays());
        values.put("pass_type", passType(account, now));
        var provider = placeholderProvider;
        if (provider != null) {
            try {
                provider.accept(player, values);
            } catch (RuntimeException exception) {
                logger.warning("Автосообщение: поставщик подстановок упал: " + exception.getMessage());
            }
        }
        Object[] placeholders = new Object[values.size() * 2];
        int index = 0;
        for (java.util.Map.Entry<String, Object> entry : values.entrySet()) {
            placeholders[index++] = entry.getKey();
            placeholders[index++] = entry.getValue();
        }
        player.sendMessage(messages.renderTemplate(cfg.autoMessagesPrefix() + message.text(), placeholders));
    }

    /** Подпись состояния проходки для {@code %pass_type%}. */
    private String passType(RegistrationStore.PlayerAccount account, long now) {
        if (account == null || !account.hasActivePass(now)) {
            return messages.plain("presentation.no-pass");
        }
        boolean trial = "trial".equals(account.lastGrantSource) && account.passUntil == account.trialUntil;
        return messages.plain(trial ? "presentation.trial" : "presentation.pass");
    }

    private boolean eligible(AutoMessage entry, Player player, int online) {
        var account = accessManager.store().getAccount(player.getName()).orElse(null);
        long now = System.currentTimeMillis();
        boolean hasPass = account != null && account.hasActivePass(now);
        boolean trial = hasPass && "trial".equals(account.lastGrantSource)
                && account.trialUntil == account.passUntil;
        boolean paid = hasPass && !trial;
        boolean unsecured = accessManager.config().totpEnabled() && (account == null || !account.isTotpEnabled());
        return entry.eligible(name -> Bukkit.getPluginManager().isPluginEnabled(name), player::hasPermission,
                trial, unsecured, hasPass, paid, online);
    }

    /* ------------------------------------------------------------------ *
     *  Chat channels (local / global)
     * ------------------------------------------------------------------ */

    public ChatConfig.Channel currentChannel(Player player) {
        return channels.getOrDefault(player.getUniqueId(), this.config.defaultChannel());
    }

    public void setChannel(Player player, ChatConfig.Channel channel) {
        if (channel == this.config.defaultChannel()) {
            channels.remove(player.getUniqueId());
        } else {
            channels.put(player.getUniqueId(), channel);
        }
    }

    /* ------------------------------------------------------------------ *
     *  /chat command
     * ------------------------------------------------------------------ */

    public boolean handleChatCommand(CommandSender sender, String label, String[] args) {
        ChatConfig cfg = this.config;
        if (!cfg.enabled()) {
            messages.send(sender, "chat.disabled");
            return true;
        }

        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sendChatHelp(sender, label);
                return true;
            }
            ChatConfig.Channel next = currentChannel(player).toggled();
            setChannel(player, next);
            messages.send(player, "chat.mode-switched", "channel", ChatConfig.describeChannel(next));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "global", "g", "глобальный", "общий" -> {
                if (!requirePlayer(sender)) return true;
                Player player = (Player) sender;
                setChannel(player, ChatConfig.Channel.GLOBAL);
                messages.send(player, "chat.mode-global",
                        "channel", ChatConfig.describeChannel(ChatConfig.Channel.GLOBAL));
                return true;
            }
            case "local", "l", "локальный", "местный" -> {
                if (!requirePlayer(sender)) return true;
                Player player = (Player) sender;
                setChannel(player, ChatConfig.Channel.LOCAL);
                messages.send(player, "chat.mode-local",
                        "channel", ChatConfig.describeChannel(ChatConfig.Channel.LOCAL));
                return true;
            }
            case "status", "info", "статус" -> {
                if (!requirePlayer(sender)) return true;
                Player player = (Player) sender;
                messages.sendList(player, "chat.status",
                        "channel", ChatConfig.describeChannel(currentChannel(player)),
                        "radius", cfg.localRadius(),
                        "prefix", cfg.quickGlobalPrefix());
                return true;
            }
            case "clear", "очистить" -> {
                if (!sender.hasPermission(PERMISSION_CLEAR)) {
                    messages.send(sender, "chat.no-permission-clear");
                    return true;
                }
                if (args.length >= 2) {
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        messages.send(sender, "error.player-offline", "player", args[1]);
                        return true;
                    }
                    clearChat(target);
                    messages.send(sender, "chat.cleared-player", "target", target.getName());
                } else {
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        clearChat(online);
                    }
                    messages.send(sender, "chat.cleared-all");
                }
                return true;
            }
            case "broadcast", "bc", "объявление" -> {
                if (!sender.hasPermission(PERMISSION_BROADCAST)) {
                    messages.send(sender, "chat.no-permission-broadcast");
                    return true;
                }
                if (args.length < 2) {
                    messages.send(sender, "chat.usage-broadcast", "label", label);
                    return true;
                }
                String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
                broadcastAnnouncement(sender, text);
                return true;
            }
            case "slow", "медленный" -> {
                if (!sender.hasPermission(PERMISSION_MUTE)) {
                    messages.send(sender, "chat.no-permission-slow");
                    return true;
                }
                if (args.length < 2) {
                    messages.send(sender, "chat.usage-slow", "label", label);
                    return true;
                }
                if ("off".equalsIgnoreCase(args[1]) || "выкл".equalsIgnoreCase(args[1]) || "0".equals(args[1])) {
                    ChatConfig updated = config.withSlowModeSeconds(0);
                    reload(updated);
                    messages.send(sender, "chat.slow-off");
                    return true;
                }
                int seconds;
                try {
                    seconds = Integer.parseInt(args[1]);
                } catch (NumberFormatException exception) {
                    messages.send(sender, "chat.slow-bad-value");
                    return true;
                }
                ChatConfig updated = config.withSlowModeSeconds(Math.clamp(seconds, 1, 3600));
                reload(updated);
                messages.send(sender, "chat.slow-on", "slow_mode_seconds", updated.slowModeSeconds());
                accessManager.audit().log(AuditLog.ADMIN_COMMAND, sender.getName(), "", null,
                        AuditLog.details("action", "slow-mode", "seconds", updated.slowModeSeconds()));
                return true;
            }
            case "history", "история" -> {
                if (!sender.hasPermission(PERMISSION_HISTORY)) {
                    messages.send(sender, "chat.no-permission-history");
                    return true;
                }
                if (args.length < 2) {
                    messages.send(sender, "chat.usage-history", "label", label);
                    return true;
                }
                int limit = 20;
                if (args.length >= 3) {
                    try {
                        limit = Math.clamp(Integer.parseInt(args[2]), 1, 200);
                    } catch (NumberFormatException ignored) {
                        limit = 20;
                    }
                }
                List<String> lines = readChatHistory(args[1], limit);
                if (lines.isEmpty()) {
                    messages.send(sender, "chat.history-empty");
                    return true;
                }
                messages.send(sender, "chat.history-header", "args", args[1]);
                for (String line : lines) {
                    sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
                }
                return true;
            }
            case "help", "?", "помощь" -> {
                sendChatHelp(sender, label);
                return true;
            }
            default -> {
                if (!requirePlayer(sender)) return true;
                Player player = (Player) sender;
                String text = String.join(" ", args).trim();
                if (text.isEmpty()) {
                    sendChatHelp(sender, label);
                    return true;
                }
                // Re-send the text as a normal chat message so the usual channel rules apply.
                player.chat(text);
                return true;
            }
        }
    }

    /**
     * Handles {@code /mute <nick> <duration> [reason]}, {@code /unmute <nick>} and {@code /mutes}.
     */
    public boolean handleMuteCommand(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION_MUTE)) {
            messages.send(sender, "mute.no-permission");
            return true;
        }
        if (args.length == 0) {
            messages.send(sender, "mute.usage", "label", label);
            return true;
        }
        String targetName = args[0];
        List<RegistrationStore.MuteEntry> mutes = store().listMutes(System.currentTimeMillis(), true);
        boolean listed = false;
        for (RegistrationStore.MuteEntry entry : mutes) {
            messages.send(sender, "mute.list-entry",
                    "player", entry.playerName,
                    "remaining", AccessManager.describeRemaining(entry.remainingMillis(System.currentTimeMillis())),
                    "reason", entry.reason.isBlank() ? messages.mini("mute.no-reason") : entry.reason);
            listed = true;
        }
        if (!listed) {
            messages.send(sender, "mute.list-empty", "label", label);
        }
        return true;
    }

    /** Applies a chat mute. Duration accepts {@code 10m}, {@code 2h}, {@code 1d} or a plain number of minutes. */
    public boolean handleMuteApply(CommandSender sender, String targetName, String durationRaw, String reason) {
        if (!sender.hasPermission(PERMISSION_MUTE)) {
            messages.send(sender, "mute.no-permission");
            return true;
        }
        long minutes = parseDurationMinutes(durationRaw);
        if (minutes <= 0) {
            messages.send(sender, "mute.bad-duration");
            return true;
        }
        long until = System.currentTimeMillis() + minutes * 60_000L;
        String target = resolveKnownPlayerName(targetName);
        store().setMute(target, until, reason, sender.getName());
        accessManager.audit().log(AuditLog.CHAT_MUTE, sender.getName(), target, null,
                AuditLog.details("minutes", minutes, "reason", reason));
        notifications().notify("chat.mute", messages.plain("mute.notify-title"),
                messages.raw("mute.notify-body",
                        "admin", sender instanceof Player player ? player.getName() : messages.plain("common.console"),
                        "target", target,
                        "duration", AccessManager.describeRemaining(minutes * 60_000L),
                        "reason", reason == null || reason.isBlank() ? messages.plain("mute.no-reason") : reason),
                "warn");
        messages.send(sender, "mute.applied",
                "target", target,
                "duration", AccessManager.describeRemaining(minutes * 60_000L));
        Player online = Bukkit.getPlayerExact(target);
        if (online != null) {
            messages.send(online, "mute.notice",
                    "duration", AccessManager.describeRemaining(minutes * 60_000L),
                    "reason", reason == null || reason.isBlank()
                            ? ""
                            : messages.mini("mute.notice-reason", "reason", reason));
        }
        return true;
    }

    /** Removes a chat mute. */
    public boolean handleUnmuteCommand(CommandSender sender, String targetName) {
        if (!sender.hasPermission(PERMISSION_MUTE)) {
            messages.send(sender, "mute.no-permission");
            return true;
        }
        if (targetName == null || targetName.isBlank()) {
            messages.send(sender, "mute.usage-unmute");
            return true;
        }
        boolean cleared = store().clearMute(resolveKnownPlayerName(targetName));
        if (cleared) {
            accessManager.audit().log(AuditLog.CHAT_UNMUTE, sender.getName(), targetName, null);
            messages.send(sender, "mute.removed", "target_name", targetName);
        } else {
            messages.send(sender, "mute.not-muted", "target_name", targetName);
        }
        return true;
    }

    /** Returns the active mute of a player, if any (used by private messages). */
    public RegistrationStore.MuteEntry activeMute(String playerName) {
        return store().getMute(playerName, System.currentTimeMillis()).orElse(null);
    }

    /** Reads the last {@code limit} chat log lines mentioning a nickname or IP fragment. */
    public List<String> readChatHistory(String filter, int limit) {
        java.nio.file.Path chatDirectory = accessManager.audit() == null
                ? null
                : plugin.getDataFolder().toPath().resolve("logs").resolve("chat");
        if (chatDirectory == null || java.nio.file.Files.notExists(chatDirectory)) return List.of();
        String needle = filter.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        try (var stream = java.nio.file.Files.list(chatDirectory)) {
            List<java.nio.file.Path> files = stream
                    .filter(path -> path.getFileName().toString().endsWith(".log"))
                    .sorted(java.util.Comparator.comparing((java.nio.file.Path path) ->
                            path.getFileName().toString()).reversed())
                    .toList();
            for (java.nio.file.Path file : files) {
                List<String> lines = java.nio.file.Files.readAllLines(file);
                for (int index = lines.size() - 1; index >= 0; index--) {
                    String line = lines.get(index);
                    if (!line.toLowerCase(Locale.ROOT).contains(needle)) continue;
                    result.add(line);
                    if (result.size() >= limit) return result;
                }
            }
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Could not read the chat history", exception);
        }
        return result;
    }

    private static long parseDurationMinutes(String raw) {
        if (raw == null || raw.isBlank()) return -1;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        long multiplier = 1;
        if (value.endsWith("m") || value.endsWith("м")) {
            value = value.substring(0, value.length() - 1);
            multiplier = 1;
        } else if (value.endsWith("h") || value.endsWith("ч")) {
            value = value.substring(0, value.length() - 1);
            multiplier = 60;
        } else if (value.endsWith("d") || value.endsWith("д")) {
            value = value.substring(0, value.length() - 1);
            multiplier = 1_440;
        }
        try {
            long amount = Long.parseLong(value.trim());
            return amount <= 0 ? -1 : amount * multiplier;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /** Resolves the exact case of a known nickname; falls back to the raw input. */
    private String resolveKnownPlayerName(String raw) {
        Player online = Bukkit.getPlayerExact(raw);
        if (online != null) return online.getName();
        for (String name : store().getAllAccountNames()) {
            if (name.equalsIgnoreCase(raw)) return name;
        }
        return raw;
    }

    private boolean requirePlayer(CommandSender sender) {
        if (sender instanceof Player) return true;
        messages.send(sender, "error.players-only");
        return false;
    }

    private void sendChatHelp(CommandSender sender, String label) {
        if (sender.hasPermission(PERMISSION_MUTE)) {
            sender.sendMessage(messages.get("chat.help-slow", "label", label)
                    .append(messages.get("chat.help-slow-desc")));
            sender.sendMessage(messages.get("chat.help-history", "label", label)
                    .append(messages.get("chat.help-history-desc")));
        }
        ChatConfig cfg = this.config;
        messages.send(sender, "chat.help-header");
        sender.sendMessage(messages.get("chat.help-mode", "label", label)
                .append(messages.get("chat.help-mode-desc")));
        sender.sendMessage(messages.get("chat.help-send", "label", label)
                .append(messages.get("chat.help-send-desc")));
        sender.sendMessage(messages.get("chat.help-quick", "quick_global_prefix", cfg.quickGlobalPrefix())
                .append(messages.get("chat.help-quick-desc")));
        sender.sendMessage(messages.get("chat.help-msg")
                .append(messages.get("chat.help-msg-desc")));
        sender.sendMessage(messages.get("chat.help-ignore")
                .append(messages.get("chat.help-ignore-desc")));
        if (sender.hasPermission(PERMISSION_SOCIAL_SPY)) {
            sender.sendMessage(messages.get("chat.help-spy")
                    .append(messages.get("chat.help-spy-desc")));
        }
        if (sender.hasPermission(PERMISSION_CLEAR)) {
            sender.sendMessage(messages.get("chat.help-clear", "label", label)
                    .append(messages.get("chat.help-clear-desc")));
        }
        if (sender.hasPermission(PERMISSION_BROADCAST)) {
            sender.sendMessage(messages.get("chat.help-broadcast", "label", label)
                    .append(messages.get("chat.help-broadcast-desc")));
        }
    }

    public void clearChat(Audience audience) {
        for (int i = 0; i < CHAT_CLEAR_LINES; i++) {
            audience.sendMessage(Component.empty());
        }
    }

    public void broadcastAnnouncement(CommandSender source, String rawText) {
        accessManager.audit().log(AuditLog.CHAT_BROADCAST,
                source instanceof Player player ? player.getName() : "console", "", null,
                AuditLog.details("text", rawText.length() > 200 ? rawText.substring(0, 200) + "…" : rawText));
        boolean colorAllowed = !(source instanceof Player player) || player.hasPermission(PERMISSION_COLOR);
        Component body = colorAllowed ? legacy(rawText) : Component.text(rawText);
        Component line = legacy(messages.template(ANNOUNCEMENT_PREFIX_KEY)).append(body);
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(line);
        }
        logger.info(source.getName() + " sent a chat announcement: " + rawText);
    }

    /* ------------------------------------------------------------------ *
     *  Private messages
     * ------------------------------------------------------------------ */

    public boolean handlePrivateMessageCommand(CommandSender sender, String label, String[] args) {
        ChatConfig cfg = this.config;
        if (!cfg.enabled() || !cfg.privateMessagesEnabled()) {
            messages.send(sender, "private-messages.disabled");
            return true;
        }
        if (args.length < 2) {
            messages.send(sender, "private-messages.usage", "label", label);
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            messages.send(sender, "error.player-offline", "player", args[0]);
            return true;
        }
        if (sender instanceof Player senderPlayer) {
            if (!senderPlayer.isOnline()) return true;
            if (accessManager.getStage(senderPlayer) != AccessManager.PlayerStage.AUTHENTICATED) {
                messages.send(sender, "chat.not-authenticated");
                return true;
            }
            RegistrationStore.MuteEntry mute = activeMute(senderPlayer.getName());
            if (mute != null && !senderPlayer.hasPermission(PERMISSION_BYPASS)) {
                messages.send(sender, "mute.cannot-write",
                        "duration", AccessManager.describeRemaining(mute.until - System.currentTimeMillis()),
                        "reason", mute.reason.isBlank()
                                ? ""
                                : messages.mini("mute.notice-reason", "reason", mute.reason));
                return true;
            }
            if (target.getUniqueId().equals(senderPlayer.getUniqueId())) {
                messages.send(sender, "private-messages.self");
                return true;
            }
        }

        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        if (message.isEmpty()) {
            messages.send(sender, "private-messages.empty");
            return true;
        }
        if (message.length() > cfg.maxMessageLength() * 2) {
            messages.send(sender, "private-messages.too-long",
                    "max_message_length", cfg.maxMessageLength() * 2);
            return true;
        }

        String senderName = sender instanceof Player senderPlayer ? senderPlayer.getName() : "Сервер";
        if (cfg.chatLogEnabled() && cfg.chatLogIncludePrivate()) {
            accessManager.audit().chat("msg", senderName, target.getName(), message);
        }
        if (store().isIgnoring(target.getName(), senderName)) {
            messages.send(sender, "private-messages.ignored", "target", target.getName());
            return true;
        }

        boolean colorAllowed = cfg.allowColorCodes()
                && (!(sender instanceof Player senderPlayer) || senderPlayer.hasPermission(PERMISSION_COLOR));
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("%sender%", sender instanceof Player ? decorated(senderName) : senderName);
        placeholders.put("%target%", target.getName());

        String[] receive = splitFormat(cfg.privateReceiveFormat(), placeholders);
        NamedTextColor bodyColor = extractTrailingColor(receive[0], NamedTextColor.WHITE);
        Component body = buildMessageBody(message, colorAllowed, cfg, false, bodyColor, name -> false, messages);
        target.sendMessage(legacy(receive[0]).append(body).append(legacy(receive[1])));
        if (cfg.privateMessageSound()) {
            target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.6f);
        }

        String[] send = splitFormat(cfg.privateSendFormat(), placeholders);
        sender.sendMessage(legacy(send[0]).append(body).append(legacy(send[1])));

        if (sender instanceof Player senderPlayer) {
            lastPrivatePartners.put(senderPlayer.getUniqueId(), target.getUniqueId());
            lastPrivatePartners.put(target.getUniqueId(), senderPlayer.getUniqueId());
        }

        if (!socialSpy.isEmpty()) {
            Component spyLine = null;
            for (UUID spyId : socialSpy) {
                Player spy = Bukkit.getPlayer(spyId);
                if (spy == null || !spy.isOnline() || !spy.hasPermission(PERMISSION_SOCIAL_SPY)) continue;
                if (target.getUniqueId().equals(spyId)) continue;
                if (sender instanceof Player senderPlayer && senderPlayer.getUniqueId().equals(spyId)) continue;
                if (spyLine == null) {
                    String[] spyFormat = splitFormat(cfg.socialSpyFormat(), placeholders);
                    spyLine = legacy(spyFormat[0]).append(body).append(legacy(spyFormat[1]));
                }
                spy.sendMessage(spyLine);
            }
        }
        return true;
    }

    public boolean handleReplyCommand(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.players-only");
            return true;
        }
        if (args.length < 1) {
            messages.send(sender, "private-messages.usage-reply", "label", label);
            return true;
        }
        UUID partnerId = lastPrivatePartners.get(player.getUniqueId());
        if (partnerId == null) {
            messages.send(player, "private-messages.no-partner");
            return true;
        }
        Player target = Bukkit.getPlayer(partnerId);
        if (target == null || !target.isOnline()) {
            messages.send(player, "private-messages.partner-offline");
            return true;
        }
        String[] forwarded = new String[args.length + 1];
        forwarded[0] = target.getName();
        System.arraycopy(args, 0, forwarded, 1, args.length);
        return handlePrivateMessageCommand(sender, "msg", forwarded);
    }

    public boolean handleSocialSpyCommand(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.players-only");
            return true;
        }
        if (!player.hasPermission(PERMISSION_SOCIAL_SPY)) {
            messages.send(player, "chat.no-permission-socialspy");
            return true;
        }

        boolean enabled = socialSpy.contains(player.getUniqueId());
        if (args.length >= 1) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("on") || sub.equals("вкл") || sub.equals("true")) {
                enabled = true;
            } else if (sub.equals("off") || sub.equals("выкл") || sub.equals("false")) {
                enabled = false;
            }
        } else {
            enabled = !enabled;
        }

        if (enabled) {
            socialSpy.add(player.getUniqueId());
        } else {
            socialSpy.remove(player.getUniqueId());
        }
        messages.send(player, "socialspy.state",
                "state", Messages.trusted(messages.raw(enabled ? "common.on" : "common.off")));
        return true;
    }

    /* ------------------------------------------------------------------ *
     *  Ignore list
     * ------------------------------------------------------------------ */

    public boolean handleIgnoreCommand(CommandSender sender, String label, String[] args, boolean forcedRemove) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.players-only");
            return true;
        }
        if (args.length == 0) {
            sendIgnoreHelp(player, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if ("list".equals(sub) || "список".equals(sub)) {
            Set<String> ignored = store().getIgnoredDisplayNames(player.getName());
            if (ignored.isEmpty()) {
                messages.send(player, "ignore.list-empty");
            } else {
                messages.send(player, "ignore.list",
                        "size", ignored.size(), "players", String.join(", ", ignored));
            }
            return true;
        }

        boolean remove = forcedRemove;
        String targetName;
        if ("add".equals(sub) || "remove".equals(sub) || "del".equals(sub)
                || "delete".equals(sub) || "unignore".equals(sub)) {
            if (args.length < 2) {
                sendIgnoreHelp(player, label);
                return true;
            }
            targetName = args[1];
            if (!"add".equals(sub)) {
                remove = true;
            }
        } else {
            targetName = args[0];
        }

        if (!AccessManager.isValidMinecraftName(targetName)) {
            messages.send(player, "error.invalid-nickname", "target_name", targetName);
            return true;
        }
        if (targetName.equalsIgnoreCase(player.getName())) {
            messages.send(player, "ignore.self");
            return true;
        }

        if (remove) {
            if (removeIgnore(player, targetName)) {
                messages.send(player, "ignore.removed", "target_name", targetName);
            } else {
                messages.send(player, "ignore.not-listed", "target_name", targetName);
            }
        } else if (addIgnore(player, targetName)) {
            messages.send(player, "ignore.added", "target_name", targetName);
        } else {
            messages.send(player, "ignore.already", "target_name", targetName);
        }
        return true;
    }

    private void sendIgnoreHelp(Player player, String label) {
        messages.send(player, "ignore.help-header");
        player.sendMessage(messages.get("ignore.help-add", "label", label)
                .append(messages.get("ignore.help-add-desc")));
        player.sendMessage(messages.get("ignore.help-remove", "label", label)
                .append(messages.get("ignore.help-remove-desc")));
        player.sendMessage(messages.get("ignore.help-list", "label", label)
                .append(messages.get("ignore.help-list-desc")));
    }

    public Set<String> ignoredNames(Player player) {
        return loadIgnores(player);
    }

    private Set<String> loadIgnores(Player player) {
        return ignoreCache.computeIfAbsent(player.getUniqueId(), ignored -> {
            Set<String> loaded = ConcurrentHashMap.newKeySet();
            loaded.addAll(store().getIgnoredPlayers(player.getName()));
            return loaded;
        });
    }

    public boolean isIgnoring(Player viewer, String senderName) {
        return loadIgnores(viewer).contains(RegistrationStore.canonicalName(senderName));
    }

    private boolean addIgnore(Player player, String targetName) {
        if (!store().addIgnoredPlayer(player.getName(), targetName)) return false;
        loadIgnores(player).add(RegistrationStore.canonicalName(targetName));
        return true;
    }

    private boolean removeIgnore(Player player, String targetName) {
        if (!store().removeIgnoredPlayer(player.getName(), targetName)) return false;
        loadIgnores(player).remove(RegistrationStore.canonicalName(targetName));
        return true;
    }

    private RegistrationStore store() {
        return accessManager.store();
    }

    /* ------------------------------------------------------------------ *
     *  Formatting helpers (covered by unit tests)
     * ------------------------------------------------------------------ */

    /**
     * Рендерит формат чата: поддерживается MiniMessage ({@code <gradient>}, {@code <hover>}, {@code <click>}),
     * а старые {@code &}-коды автоматически переводятся в MiniMessage.
     */
    public static Component legacy(String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        return Messages.format(raw);
    }

    public static String plainText(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    static String[] splitFormat(String format, Map<String, String> placeholders) {
        String resolved = format == null ? MESSAGE_PLACEHOLDER : format;
        int markerIndex = resolved.indexOf(MESSAGE_PLACEHOLDER);
        String head = markerIndex >= 0 ? resolved.substring(0, markerIndex) : resolved;
        String tail = markerIndex >= 0 ? resolved.substring(markerIndex + MESSAGE_PLACEHOLDER.length()) : "";
        return new String[]{applyPlaceholders(head, placeholders), applyPlaceholders(tail, placeholders)};
    }

    static String applyPlaceholders(String input, Map<String, String> placeholders) {
        if (input == null || input.isEmpty()) return input == null ? "" : input;
        if (placeholders == null || placeholders.isEmpty()) return input;
        String result = input;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    static NamedTextColor extractTrailingColor(String raw, NamedTextColor fallback) {
        if (raw == null || raw.isEmpty()) return fallback;
        char found = 0;
        for (int i = 0; i + 1 < raw.length(); i++) {
            char marker = raw.charAt(i);
            if (marker != '&' && marker != '\u00a7') continue;
            char code = Character.toLowerCase(raw.charAt(i + 1));
            if (isColorCode(code) || code == 'r') {
                found = code;
                i++;
            }
        }
        return found == 0 ? fallback : namedColor(found, fallback);
    }

    private static boolean isColorCode(char code) {
        return (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f');
    }

    static NamedTextColor namedColor(char code, NamedTextColor fallback) {
        return switch (code) {
            case '0' -> NamedTextColor.BLACK;
            case '1' -> NamedTextColor.DARK_BLUE;
            case '2' -> NamedTextColor.DARK_GREEN;
            case '3' -> NamedTextColor.DARK_AQUA;
            case '4' -> NamedTextColor.DARK_RED;
            case '5' -> NamedTextColor.DARK_PURPLE;
            case '6' -> NamedTextColor.GOLD;
            case '7' -> NamedTextColor.GRAY;
            case '8' -> NamedTextColor.DARK_GRAY;
            case '9' -> NamedTextColor.BLUE;
            case 'a' -> NamedTextColor.GREEN;
            case 'b' -> NamedTextColor.AQUA;
            case 'c' -> NamedTextColor.RED;
            case 'd' -> NamedTextColor.LIGHT_PURPLE;
            case 'e' -> NamedTextColor.YELLOW;
            case 'f', 'r' -> NamedTextColor.WHITE;
            default -> fallback;
        };
    }

    /** Lowercases a message when the share of upper case letters reaches the configured threshold. */
    static String applyCapsRule(String message, int capsPercent) {
        if (message == null || message.isEmpty()) return message;
        int letters = 0;
        int upper = 0;
        for (int i = 0; i < message.length(); i++) {
            char character = message.charAt(i);
            if (!Character.isLetter(character)) continue;
            letters++;
            if (Character.isUpperCase(character)) upper++;
        }
        if (letters < MIN_CAPS_LETTERS) return message;
        int percent = (int) Math.round(upper * 100.0 / letters);
        return percent >= capsPercent ? message.toLowerCase(Locale.ROOT) : message;
    }

    private static Component mentionHover(Messages messages, String candidate) {
        return messages == null
                ? Component.text("@" + candidate)
                : messages.get("chat.mention-hover", "candidate", candidate);
    }

    static boolean isDuplicate(String previous, String current) {
        if (previous == null || current == null) return false;
        String a = previous.trim();
        String b = current.trim();
        return !a.isEmpty() && a.equalsIgnoreCase(b);
    }

    static boolean hasMention(String message, String playerName) {
        if (message == null || playerName == null || playerName.isEmpty()) return false;
        Pattern pattern = Pattern.compile("@" + Pattern.quote(playerName) + "(?![A-Za-z0-9_])",
                Pattern.CASE_INSENSITIVE);
        return pattern.matcher(message).find();
    }

    static String formatSeconds(long millis) {
        double seconds = millis / 1000.0;
        if (seconds < 1.0) return "1";
        return String.format(Locale.ROOT, "%.1f", seconds);
    }

    /**
     * Builds the body of a chat line: keeps the color of the surrounding format for plain text,
     * translates {@code &}-codes for players allowed to use them,
     * and highlights mentions of known players.
     */
    static Component buildMessageBody(
            String rawMessage,
            boolean colorAllowed,
            ChatConfig cfg,
            boolean highlightMentions,
            TextColor inheritedColor,
            Predicate<String> isKnownPlayer
    ) {
        return buildMessageBody(rawMessage, colorAllowed, cfg, highlightMentions, inheritedColor,
                isKnownPlayer, null);
    }

    /** Полная версия: {@code messages} нужен для подсказки при наведении на упоминание (в тестах может быть null). */
    static Component buildMessageBody(
            String rawMessage,
            boolean colorAllowed,
            ChatConfig cfg,
            boolean highlightMentions,
            TextColor inheritedColor,
            Predicate<String> isKnownPlayer,
            Messages messages
    ) {
        String normalized = rawMessage == null ? "" : rawMessage;
        if (colorAllowed) {
            // Section signs typed by clients are handled like '&'-codes.
            normalized = normalized.replace('\u00a7', '&');
        } else {
            normalized = normalized.replace("\u00a7", "");
        }

        List<Component> parts = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int index = 0;
        int length = normalized.length();
        while (index < length) {
            char character = normalized.charAt(index);
            if (character != '@' || !highlightMentions || isKnownPlayer == null) {
                buffer.append(character);
                index++;
                continue;
            }
            int end = index + 1;
            while (end < length && end - index <= MAX_MENTION_LENGTH && isNameCharacter(normalized.charAt(end))) {
                end++;
            }
            String candidate = normalized.substring(index + 1, end);
            if (candidate.isEmpty() || !isKnownPlayer.test(candidate)) {
                buffer.append(character);
                index++;
                continue;
            }
            flushPlain(parts, buffer, colorAllowed, inheritedColor);
            parts.add(Component.text("@" + candidate, mentionColor(cfg, inheritedColor), TextDecoration.BOLD)
                    .hoverEvent(HoverEvent.showText(mentionHover(messages, candidate)))
                    .clickEvent(ClickEvent.suggestCommand("/msg " + candidate + " ")));
            index = end;
        }
        flushPlain(parts, buffer, colorAllowed, inheritedColor);

        Component result = Component.empty();
        for (Component part : parts) {
            result = result.append(part);
        }
        return result;
    }

    private static boolean isNameCharacter(char character) {
        return (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9')
                || character == '_';
    }

    private static TextColor mentionColor(ChatConfig cfg, TextColor fallback) {
        String raw = cfg == null ? null : cfg.mentionColor();
        if (raw == null || raw.isEmpty()) {
            return fallback == null ? NamedTextColor.YELLOW : fallback;
        }
        for (int i = 0; i + 1 < raw.length(); i++) {
            char marker = raw.charAt(i);
            if (marker != '&' && marker != '\u00a7') continue;
            return namedColor(Character.toLowerCase(raw.charAt(i + 1)), NamedTextColor.YELLOW);
        }
        return NamedTextColor.YELLOW;
    }

    private static void flushPlain(List<Component> parts, StringBuilder buffer,
                                   boolean colorAllowed, TextColor inheritedColor) {
        if (buffer.isEmpty()) return;
        String segment = buffer.toString();
        buffer.setLength(0);
        if (segment.isEmpty()) return;
        if (colorAllowed) {
            Component parsed = legacy(segment);
            if (parsed.color() == null && inheritedColor != null) {
                parsed = parsed.color(inheritedColor);
            }
            parts.add(parsed);
        } else {
            parts.add(inheritedColor == null ? Component.text(segment) : Component.text(segment, inheritedColor));
        }
    }
}
