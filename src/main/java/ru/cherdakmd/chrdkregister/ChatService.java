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

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String MESSAGE_PLACEHOLDER = "%message%";
    private static final String ANNOUNCEMENT_PREFIX = "&8[&cОбъявление&8] &r";
    private static final int MIN_CAPS_LETTERS = 6;
    private static final int MAX_MENTION_LENGTH = 16;
    private static final int CHAT_CLEAR_LINES = 100;

    private final JavaPlugin plugin;
    private final AccessManager accessManager;
    private final Logger logger;

    private volatile ChatConfig config;
    private final Map<UUID, ChatConfig.Channel> channels = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMessageAt = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastMessageText = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> lastPrivatePartners = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> ignoreCache = new ConcurrentHashMap<>();
    private final Set<UUID> socialSpy = ConcurrentHashMap.newKeySet();

    private BukkitTask autoMessageTask;
    private int autoMessageIndex;

    public ChatService(JavaPlugin plugin, AccessManager accessManager, ChatConfig config, Logger logger) {
        this.plugin = plugin;
        this.accessManager = accessManager;
        this.config = config;
        this.logger = logger;
    }

    public ChatConfig config() {
        return config;
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
        long now = System.currentTimeMillis();

        if (!bypass && text.length() > cfg.maxMessageLength()) {
            event.setCancelled(true);
            player.sendMessage(Component.text("Сообщение слишком длинное! Максимум "
                    + cfg.maxMessageLength() + " символов.", NamedTextColor.RED));
            return;
        }

        if (!bypass && cfg.antiCaps()) {
            String lowered = applyCapsRule(text, cfg.capsPercent());
            if (!lowered.equals(text)) {
                text = lowered;
                player.sendMessage(Component.text("Пожалуйста, не пишите капсом — сообщение было исправлено.",
                        NamedTextColor.YELLOW));
            }
        }

        if (!bypass && cfg.blockDuplicates() && cfg.duplicateWindowMillis() > 0) {
            String previous = lastMessageText.get(uuid);
            long previousAt = lastMessageAt.getOrDefault(uuid, 0L);
            if (previous != null && now - previousAt <= cfg.duplicateWindowMillis() && isDuplicate(previous, text)) {
                event.setCancelled(true);
                player.sendMessage(Component.text("Это сообщение вы уже недавно отправляли!", NamedTextColor.RED));
                return;
            }
        }

        if (!bypass && cfg.cooldownMillis() > 0) {
            long last = lastMessageAt.getOrDefault(uuid, 0L);
            long remaining = cfg.cooldownMillis() - (now - last);
            if (last > 0 && remaining > 0) {
                event.setCancelled(true);
                player.sendMessage(Component.text("Подождите " + formatSeconds(remaining)
                        + " сек. перед следующим сообщением.", NamedTextColor.RED));
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
        placeholders.put("%player%", player.getName());
        placeholders.put("%world%", player.getWorld().getName());
        String[] segments = splitFormat(format, placeholders);
        NamedTextColor bodyColor = extractTrailingColor(segments[0], NamedTextColor.WHITE);
        Component body = buildMessageBody(
                text,
                colorAllowed,
                cfg,
                cfg.mentionHighlight(),
                bodyColor,
                candidate -> viewerNames.contains(candidate.toLowerCase(Locale.ROOT))
        );
        Component line = legacy(segments[0]).append(body).append(legacy(segments[1]));

        event.renderer((source, sourceDisplayName, message, viewer) -> line);
        event.viewers().removeIf(audience -> !canSee(audience, player, channel, cfg));

        notifyMentions(player, text, mentionCandidates, cfg);
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
                    viewer.sendActionBar(Component.text("Вас упомянул(а) " + senderName + " в чате",
                            NamedTextColor.YELLOW));
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

    private void scheduleAutoMessages() {
        ChatConfig cfg = this.config;
        if (!cfg.enabled() || !cfg.autoMessagesEnabled() || cfg.autoMessages().isEmpty()) return;
        long intervalTicks = Math.max(20L * 30L, cfg.autoMessagesIntervalSeconds() * 20L);
        autoMessageTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::sendNextAutoMessage, intervalTicks, intervalTicks);
        logger.info("Automatic chat messages enabled: every " + cfg.autoMessagesIntervalSeconds()
                + " second(s), " + cfg.autoMessages().size() + " message(s).");
    }

    private void sendNextAutoMessage() {
        ChatConfig cfg = this.config;
        if (!cfg.enabled() || !cfg.autoMessagesEnabled()) return;
        List<String> messages = cfg.autoMessages();
        if (messages.isEmpty()) return;

        int onlineCount = Bukkit.getOnlinePlayers().size();
        if (onlineCount < cfg.autoMessagesMinimumPlayers()) return;

        String raw;
        if (cfg.autoMessagesRandomOrder()) {
            raw = messages.get(ThreadLocalRandom.current().nextInt(messages.size()));
        } else {
            raw = messages.get(Math.floorMod(autoMessageIndex, messages.size()));
            autoMessageIndex++;
        }

        Component line = legacy(cfg.autoMessagesPrefix())
                .append(legacy(applyPlaceholders(raw, Map.of("%online%", Integer.toString(onlineCount)))));
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.hasPermission(PERMISSION_AUTO_MESSAGE)) continue;
            if (accessManager.getStage(online) != AccessManager.PlayerStage.AUTHENTICATED) continue;
            online.sendMessage(line);
        }
    }

    /* ------------------------------------------------------------------ *
     *  /chat command
     * ------------------------------------------------------------------ */

    public boolean handleChatCommand(CommandSender sender, String label, String[] args) {
        ChatConfig cfg = this.config;
        if (!cfg.enabled()) {
            sender.sendMessage(Component.text("Встроенный чат отключён в конфигурации сервера.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sendChatHelp(sender, label);
                return true;
            }
            ChatConfig.Channel next = currentChannel(player).toggled();
            setChannel(player, next);
            player.sendMessage(Component.text("Режим чата переключён: ", NamedTextColor.GRAY)
                    .append(Component.text(ChatConfig.describeChannel(next), NamedTextColor.AQUA)));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "global", "g", "глобальный", "общий" -> {
                if (!requirePlayer(sender)) return true;
                Player player = (Player) sender;
                setChannel(player, ChatConfig.Channel.GLOBAL);
                player.sendMessage(Component.text("Режим чата: ", NamedTextColor.GRAY)
                        .append(Component.text(ChatConfig.describeChannel(ChatConfig.Channel.GLOBAL), NamedTextColor.GOLD)));
                return true;
            }
            case "local", "l", "локальный", "местный" -> {
                if (!requirePlayer(sender)) return true;
                Player player = (Player) sender;
                setChannel(player, ChatConfig.Channel.LOCAL);
                player.sendMessage(Component.text("Режим чата: ", NamedTextColor.GRAY)
                        .append(Component.text(ChatConfig.describeChannel(ChatConfig.Channel.LOCAL), NamedTextColor.GREEN)));
                return true;
            }
            case "status", "info", "статус" -> {
                if (!requirePlayer(sender)) return true;
                Player player = (Player) sender;
                player.sendMessage(Component.text("Ваш режим чата: ", NamedTextColor.GRAY)
                        .append(Component.text(ChatConfig.describeChannel(currentChannel(player)), NamedTextColor.AQUA)));
                player.sendMessage(Component.text("Радиус локального чата: ", NamedTextColor.GRAY)
                        .append(Component.text((int) cfg.localRadius() + " блоков", NamedTextColor.WHITE)));
                player.sendMessage(Component.text("Разовое сообщение всем: ", NamedTextColor.GRAY)
                        .append(Component.text(cfg.quickGlobalPrefix() + "текст", NamedTextColor.YELLOW)));
                return true;
            }
            case "clear", "очистить" -> {
                if (!sender.hasPermission(PERMISSION_CLEAR)) {
                    sender.sendMessage(Component.text("Недостаточно прав для очистки чата.", NamedTextColor.RED));
                    return true;
                }
                if (args.length >= 2) {
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        sender.sendMessage(Component.text("Игрок " + args[1] + " не в сети.", NamedTextColor.RED));
                        return true;
                    }
                    clearChat(target);
                    sender.sendMessage(Component.text("Чат очищен для игрока " + target.getName() + ".",
                            NamedTextColor.GREEN));
                } else {
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        clearChat(online);
                    }
                    sender.sendMessage(Component.text("Чат очищен для всех игроков.", NamedTextColor.GREEN));
                }
                return true;
            }
            case "broadcast", "bc", "объявление" -> {
                if (!sender.hasPermission(PERMISSION_BROADCAST)) {
                    sender.sendMessage(Component.text("Недостаточно прав для объявлений.", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Использование: /" + label + " broadcast <текст>",
                            NamedTextColor.YELLOW));
                    return true;
                }
                String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
                broadcastAnnouncement(sender, text);
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

    private boolean requirePlayer(CommandSender sender) {
        if (sender instanceof Player) return true;
        sender.sendMessage(Component.text("Эта команда доступна только игрокам.", NamedTextColor.RED));
        return false;
    }

    private void sendChatHelp(CommandSender sender, String label) {
        ChatConfig cfg = this.config;
        sender.sendMessage(Component.text("=== Чат CHRDK_F_REGISTER ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/" + label + " [global|local|status]", NamedTextColor.YELLOW)
                .append(Component.text(" — режим чата (без аргументов — переключить)", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " <текст>", NamedTextColor.YELLOW)
                .append(Component.text(" — отправить текст в текущий режим чата", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text(cfg.quickGlobalPrefix() + "<текст>", NamedTextColor.YELLOW)
                .append(Component.text(" — разовое сообщение в глобальный чат", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/msg <ник> <текст>", NamedTextColor.YELLOW)
                .append(Component.text(" — личное сообщение, /r <текст> — ответ", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/ignore <ник>", NamedTextColor.YELLOW)
                .append(Component.text(" — скрыть сообщения игрока, /ignore list — список", NamedTextColor.GRAY)));
        if (sender.hasPermission(PERMISSION_SOCIAL_SPY)) {
            sender.sendMessage(Component.text("/socialspy [on|off]", NamedTextColor.YELLOW)
                    .append(Component.text(" — просмотр личных сообщений игроков", NamedTextColor.GRAY)));
        }
        if (sender.hasPermission(PERMISSION_CLEAR)) {
            sender.sendMessage(Component.text("/" + label + " clear [ник]", NamedTextColor.YELLOW)
                    .append(Component.text(" — очистить чат", NamedTextColor.GRAY)));
        }
        if (sender.hasPermission(PERMISSION_BROADCAST)) {
            sender.sendMessage(Component.text("/" + label + " broadcast <текст>", NamedTextColor.YELLOW)
                    .append(Component.text(" — объявление от лица сервера", NamedTextColor.GRAY)));
        }
    }

    public void clearChat(Audience audience) {
        for (int i = 0; i < CHAT_CLEAR_LINES; i++) {
            audience.sendMessage(Component.empty());
        }
    }

    public void broadcastAnnouncement(CommandSender source, String rawText) {
        boolean colorAllowed = !(source instanceof Player player) || player.hasPermission(PERMISSION_COLOR);
        Component body = colorAllowed ? legacy(rawText) : Component.text(rawText);
        Component line = legacy(ANNOUNCEMENT_PREFIX).append(body);
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
            sender.sendMessage(Component.text("Личные сообщения отключены в конфигурации сервера.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Использование: /" + label + " <ник> <сообщение>", NamedTextColor.YELLOW));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(Component.text("Игрок " + args[0] + " не в сети.", NamedTextColor.RED));
            return true;
        }
        if (sender instanceof Player senderPlayer) {
            if (!senderPlayer.isOnline()) return true;
            if (accessManager.getStage(senderPlayer) != AccessManager.PlayerStage.AUTHENTICATED) {
                sender.sendMessage(Component.text("Сначала авторизуйтесь на сервере, чтобы писать в чат.",
                        NamedTextColor.RED));
                return true;
            }
            if (target.getUniqueId().equals(senderPlayer.getUniqueId())) {
                sender.sendMessage(Component.text("Нельзя отправить личное сообщение самому себе.", NamedTextColor.RED));
                return true;
            }
        }

        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        if (message.isEmpty()) {
            sender.sendMessage(Component.text("Сообщение не может быть пустым.", NamedTextColor.RED));
            return true;
        }
        if (message.length() > cfg.maxMessageLength() * 2) {
            sender.sendMessage(Component.text("Личное сообщение слишком длинное! Максимум "
                    + (cfg.maxMessageLength() * 2) + " символов.", NamedTextColor.RED));
            return true;
        }

        String senderName = sender instanceof Player senderPlayer ? senderPlayer.getName() : "Сервер";
        if (store().isIgnoring(target.getName(), senderName)) {
            sender.sendMessage(Component.text("Игрок " + target.getName() + " вас игнорирует.",
                    NamedTextColor.RED));
            return true;
        }

        boolean colorAllowed = cfg.allowColorCodes()
                && (!(sender instanceof Player senderPlayer) || senderPlayer.hasPermission(PERMISSION_COLOR));
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("%sender%", senderName);
        placeholders.put("%target%", target.getName());

        String[] receive = splitFormat(cfg.privateReceiveFormat(), placeholders);
        NamedTextColor bodyColor = extractTrailingColor(receive[0], NamedTextColor.WHITE);
        Component body = buildMessageBody(message, colorAllowed, cfg, false, bodyColor, name -> false);
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
            sender.sendMessage(Component.text("Эта команда доступна только игрокам.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(Component.text("Использование: /" + label + " <сообщение>", NamedTextColor.YELLOW));
            return true;
        }
        UUID partnerId = lastPrivatePartners.get(player.getUniqueId());
        if (partnerId == null) {
            player.sendMessage(Component.text("Вам ещё не писали в личные сообщения или вы уже вышли из игры.",
                    NamedTextColor.YELLOW));
            return true;
        }
        Player target = Bukkit.getPlayer(partnerId);
        if (target == null || !target.isOnline()) {
            player.sendMessage(Component.text("Игрок, которому вы отвечаете, уже не в сети.", NamedTextColor.RED));
            return true;
        }
        String[] forwarded = new String[args.length + 1];
        forwarded[0] = target.getName();
        System.arraycopy(args, 0, forwarded, 1, args.length);
        return handlePrivateMessageCommand(sender, "msg", forwarded);
    }

    public boolean handleSocialSpyCommand(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Эта команда доступна только игрокам.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission(PERMISSION_SOCIAL_SPY)) {
            player.sendMessage(Component.text("Недостаточно прав для просмотра личных сообщений.", NamedTextColor.RED));
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
        player.sendMessage(Component.text("Режим слежки за личными сообщениями: ", NamedTextColor.GRAY)
                .append(Component.text(enabled ? "ВКЛЮЧЁН" : "ОТКЛЮЧЁН",
                        enabled ? NamedTextColor.GREEN : NamedTextColor.RED)));
        return true;
    }

    /* ------------------------------------------------------------------ *
     *  Ignore list
     * ------------------------------------------------------------------ */

    public boolean handleIgnoreCommand(CommandSender sender, String label, String[] args, boolean forcedRemove) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Эта команда доступна только игрокам.", NamedTextColor.RED));
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
                player.sendMessage(Component.text("Вы никого не игнорируете.", NamedTextColor.GRAY));
            } else {
                player.sendMessage(Component.text("Игнорируются (" + ignored.size() + "): ", NamedTextColor.GRAY)
                        .append(Component.text(String.join(", ", ignored), NamedTextColor.WHITE)));
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
            player.sendMessage(Component.text("Некорректный ник Minecraft: " + targetName, NamedTextColor.RED));
            return true;
        }
        if (targetName.equalsIgnoreCase(player.getName())) {
            player.sendMessage(Component.text("Нельзя игнорировать самого себя.", NamedTextColor.RED));
            return true;
        }

        if (remove) {
            if (removeIgnore(player, targetName)) {
                player.sendMessage(Component.text("Игрок " + targetName + " больше не игнорируется.",
                        NamedTextColor.GREEN));
            } else {
                player.sendMessage(Component.text("Игрок " + targetName + " не найден в вашем списке игнорирования.",
                        NamedTextColor.YELLOW));
            }
        } else if (addIgnore(player, targetName)) {
            player.sendMessage(Component.text("Игрок " + targetName + " добавлен в игнор: его сообщения и личные "
                    + "сообщения будут скрыты.", NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.text("Игрок " + targetName + " уже находится в вашем списке игнорирования.",
                    NamedTextColor.YELLOW));
        }
        return true;
    }

    private void sendIgnoreHelp(Player player, String label) {
        player.sendMessage(Component.text("Игнорирование игроков:", NamedTextColor.GOLD));
        player.sendMessage(Component.text("/" + label + " <ник>", NamedTextColor.YELLOW)
                .append(Component.text(" — игнорировать игрока", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("/" + label + " remove <ник>", NamedTextColor.YELLOW)
                .append(Component.text(" — снять игнорирование", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("/" + label + " list", NamedTextColor.YELLOW)
                .append(Component.text(" — список игнорируемых игроков", NamedTextColor.GRAY)));
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

    public static Component legacy(String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        return LEGACY.deserialize(raw);
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
                    .hoverEvent(HoverEvent.showText(Component.text(
                            "Нажмите, чтобы написать " + candidate, NamedTextColor.GRAY)))
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
