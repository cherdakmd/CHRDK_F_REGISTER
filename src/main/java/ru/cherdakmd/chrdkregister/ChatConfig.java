package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validated settings for the built-in chat (local/global channels), private messages, automatic broadcasts,
 * moderation (slow mode, word/link filter, mention limits) and chat history logging.
 */
public final class ChatConfig {
    /** Compiled moderation filter built from {@code chat.filter.*}. */
    public static final class ChatFilter {
        private final boolean enabled;
        private final boolean blockMode;
        private final List<String> words;
        private final List<String> whitelist;
        private final List<Pattern> patterns;

        private ChatFilter(boolean enabled, boolean blockMode, List<String> words,
                           List<String> whitelist, List<Pattern> patterns) {
            this.enabled = enabled;
            this.blockMode = blockMode;
            this.words = words;
            this.whitelist = whitelist;
            this.patterns = patterns;
        }

        public boolean enabled() {
            return enabled;
        }

        /** {@code true} when a matching message must be blocked instead of masked. */
        public boolean blockMode() {
            return blockMode;
        }

        public List<String> words() {
            return words;
        }

        public List<String> whitelist() {
            return whitelist;
        }

        public List<Pattern> patterns() {
            return patterns;
        }

        /** Returns {@code true} when the lower-cased text contains a whitelisted fragment. */
        public boolean isWhitelisted(String lowercased) {
            for (String allowed : whitelist) {
                if (!allowed.isBlank() && lowercased.contains(allowed)) return true;
            }
            return false;
        }
    }

    /** Chat channel a player is currently writing to. */
    public enum Channel {
        LOCAL,
        GLOBAL;

        public static Channel parse(String raw, Channel fallback) {
            if (raw == null || raw.isBlank()) return fallback;
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "global", "g", "общий", "глобальный" -> GLOBAL;
                case "local", "l", "локальный", "местный" -> LOCAL;
                default -> fallback;
            };
        }

        public Channel toggled() {
            return this == GLOBAL ? LOCAL : GLOBAL;
        }
    }

    private static final List<String> DEFAULT_AUTO_MESSAGES = List.of(
            "&eПонравился сервер? Поддержи проект и продли проходку: &b/pass",
            "&eВключи двухфакторную защиту аккаунта: &b/2fa setup",
            "&eСмени пароль на надёжный: &b/changepassword",
            "&eСейчас на сервере: &b%online% &eигроков"
    );

    private boolean enabled = true;
    private Channel defaultChannel = Channel.LOCAL;
    private double localRadius = 100.0;
    private boolean localCrossWorld = false;
    private String quickGlobalPrefix = "!";
    private String localFormat = "&7[Л] &f%player%&7: &f%message%";
    private String globalFormat = "&6[Г] &f%player%&7: &f%message%";
    private boolean allowColorCodes = true;
    private int cooldownMillis = 1_000;
    private int maxMessageLength = 256;
    private boolean antiCaps = true;
    private int capsPercent = 70;
    private boolean blockDuplicates = true;
    private int duplicateWindowMillis = 15_000;
    private boolean mentionHighlight = true;
    private String mentionColor = "&e";
    private boolean mentionSound = true;
    private int maxMentions = 3;
    private int slowModeSeconds = 0;
    private boolean filterEnabled = true;
    private boolean filterBlockMode = false;
    private List<String> filterWords = List.of();
    private List<String> filterPatternsRaw = List.of(
            "(?i)https?://", "(?i)discord\\.gg/", "(?i)(?:\\d{1,3}\\.){3}\\d{1,3}");
    private List<String> filterWhitelist = List.of();
    private boolean filterNotifyAdmins = true;
    private ChatFilter filter = new ChatFilter(false, false, List.of(), List.of(), List.of());
    private boolean chatLogEnabled = true;
    private int chatLogKeepDays = 90;
    private boolean chatLogIncludePrivate = true;
    private boolean joinQuitMessages = false;
    private String joinMessage = "&8[&a+&8] &7%player% &7присоединился к серверу";
    private String quitMessage = "&8[&c-&8] &7%player% &7покинул сервер";

    private boolean privateMessagesEnabled = true;
    private boolean privateMessageSound = true;
    private String privateSendFormat = "&7[&dЯ &7→ &d%target%&7] &f%message%";
    private String privateReceiveFormat = "&7[&d%sender% &7→ &dЯ&7] &f%message%";
    private String socialSpyFormat = "&8[SPY] &7%sender% &8→ &7%target%&8: &r%message%";

    private boolean autoMessagesEnabled = true;
    private int autoMessagesIntervalSeconds = 240;
    private boolean autoMessagesRandomOrder = true;
    private int autoMessagesMinimumPlayers = 1;
    private String autoMessagesPrefix = "&8[&6CHRDK&8] &r";
    private List<String> autoMessages = List.copyOf(DEFAULT_AUTO_MESSAGES);

    public ChatConfig() {
    }

    /** Copy constructor used for hot configuration tweaks (for example {@code /chat slow}). */
    private ChatConfig(ChatConfig other) {
        this.enabled = other.enabled;
        this.defaultChannel = other.defaultChannel;
        this.localRadius = other.localRadius;
        this.localCrossWorld = other.localCrossWorld;
        this.quickGlobalPrefix = other.quickGlobalPrefix;
        this.localFormat = other.localFormat;
        this.globalFormat = other.globalFormat;
        this.allowColorCodes = other.allowColorCodes;
        this.cooldownMillis = other.cooldownMillis;
        this.maxMessageLength = other.maxMessageLength;
        this.antiCaps = other.antiCaps;
        this.capsPercent = other.capsPercent;
        this.blockDuplicates = other.blockDuplicates;
        this.duplicateWindowMillis = other.duplicateWindowMillis;
        this.mentionHighlight = other.mentionHighlight;
        this.mentionColor = other.mentionColor;
        this.mentionSound = other.mentionSound;
        this.maxMentions = other.maxMentions;
        this.slowModeSeconds = other.slowModeSeconds;
        this.filterEnabled = other.filterEnabled;
        this.filterBlockMode = other.filterBlockMode;
        this.filterWords = other.filterWords;
        this.filterPatternsRaw = other.filterPatternsRaw;
        this.filterWhitelist = other.filterWhitelist;
        this.filterNotifyAdmins = other.filterNotifyAdmins;
        this.filter = other.filter;
        this.chatLogEnabled = other.chatLogEnabled;
        this.chatLogKeepDays = other.chatLogKeepDays;
        this.chatLogIncludePrivate = other.chatLogIncludePrivate;
        this.joinQuitMessages = other.joinQuitMessages;
        this.joinMessage = other.joinMessage;
        this.quitMessage = other.quitMessage;
        this.privateMessagesEnabled = other.privateMessagesEnabled;
        this.privateMessageSound = other.privateMessageSound;
        this.privateSendFormat = other.privateSendFormat;
        this.privateReceiveFormat = other.privateReceiveFormat;
        this.socialSpyFormat = other.socialSpyFormat;
        this.autoMessagesEnabled = other.autoMessagesEnabled;
        this.autoMessagesIntervalSeconds = other.autoMessagesIntervalSeconds;
        this.autoMessagesRandomOrder = other.autoMessagesRandomOrder;
        this.autoMessagesMinimumPlayers = other.autoMessagesMinimumPlayers;
        this.autoMessagesPrefix = other.autoMessagesPrefix;
        this.autoMessages = other.autoMessages;
    }

    /** Returns a copy of this configuration with a different slow-mode value. */
    public ChatConfig withSlowModeSeconds(int seconds) {
        ChatConfig copy = new ChatConfig(this);
        copy.slowModeSeconds = Math.clamp(seconds, 0, 3_600);
        return copy;
    }

    public static ChatConfig fromBukkitConfig(FileConfiguration config) {
        ChatConfig chat = new ChatConfig();
        chat.enabled = config.getBoolean("chat.enabled", true);
        chat.defaultChannel = Channel.parse(
                config.getString("chat.default-channel", "local"), Channel.LOCAL);
        chat.localRadius = config.getDouble("chat.local-radius", 100.0);
        chat.localCrossWorld = config.getBoolean("chat.local-cross-world", false);
        chat.quickGlobalPrefix = config.getString("chat.quick-global-prefix", "!");
        chat.localFormat = config.getString("chat.local-format", chat.localFormat);
        chat.globalFormat = config.getString("chat.global-format", chat.globalFormat);
        chat.allowColorCodes = config.getBoolean("chat.allow-color-codes", true);
        chat.cooldownMillis = (int) Math.round(config.getDouble("chat.cooldown-seconds", 1.0) * 1000.0);
        chat.maxMessageLength = config.getInt("chat.max-message-length", 256);
        chat.antiCaps = config.getBoolean("chat.anti-caps", true);
        chat.capsPercent = config.getInt("chat.caps-percent", 70);
        chat.blockDuplicates = config.getBoolean("chat.block-duplicates", true);
        chat.duplicateWindowMillis =
                (int) Math.round(config.getDouble("chat.duplicate-window-seconds", 15.0) * 1000.0);
        chat.mentionHighlight = config.getBoolean("chat.mention-highlight", true);
        chat.mentionColor = config.getString("chat.mention-color", "&e");
        chat.mentionSound = config.getBoolean("chat.mention-sound", true);
        chat.maxMentions = config.getInt("chat.max-mentions", 3);
        chat.slowModeSeconds = config.getInt("chat.slow-mode-seconds", 0);
        chat.filterEnabled = config.getBoolean("chat.filter.enabled", true);
        chat.filterBlockMode = "block".equalsIgnoreCase(config.getString("chat.filter.mode", "replace"));
        List<String> configuredWords = config.getStringList("chat.filter.words");
        chat.filterWords = configuredWords.isEmpty()
                ? List.of()
                : configuredWords.stream().map(word -> word.toLowerCase(Locale.ROOT)).toList();
        List<String> configuredPatterns = config.getStringList("chat.filter.regex");
        if (!configuredPatterns.isEmpty()) {
            chat.filterPatternsRaw = List.copyOf(configuredPatterns);
        }
        chat.filterWhitelist = config.getStringList("chat.filter.whitelist").stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).toList();
        chat.filterNotifyAdmins = config.getBoolean("chat.filter.notify-admins", true);
        chat.chatLogEnabled = config.getBoolean("chat.chat-log.enabled", true);
        chat.chatLogKeepDays = config.getInt("chat.chat-log.keep-days", 90);
        chat.chatLogIncludePrivate = config.getBoolean("chat.chat-log.include-private", true);
        chat.joinQuitMessages = config.getBoolean("chat.join-quit-messages", false);
        chat.joinMessage = config.getString("chat.join-message", chat.joinMessage);
        chat.quitMessage = config.getString("chat.quit-message", chat.quitMessage);

        chat.privateMessagesEnabled = config.getBoolean("private-messages.enabled", true);
        chat.privateMessageSound = config.getBoolean("private-messages.sound", true);
        chat.privateSendFormat = config.getString("private-messages.send-format", chat.privateSendFormat);
        chat.privateReceiveFormat = config.getString("private-messages.receive-format", chat.privateReceiveFormat);
        chat.socialSpyFormat = config.getString("private-messages.spy-format", chat.socialSpyFormat);

        chat.autoMessagesEnabled = config.getBoolean("auto-messages.enabled", true);
        chat.autoMessagesIntervalSeconds = config.getInt("auto-messages.interval-seconds", 240);
        chat.autoMessagesRandomOrder = config.getBoolean("auto-messages.random-order", true);
        chat.autoMessagesMinimumPlayers = config.getInt("auto-messages.minimum-players", 1);
        chat.autoMessagesPrefix = config.getString("auto-messages.prefix", chat.autoMessagesPrefix);
        List<String> configuredAutoMessages = config.getStringList("auto-messages.messages");
        chat.autoMessages = configuredAutoMessages.isEmpty()
                ? List.copyOf(DEFAULT_AUTO_MESSAGES)
                : List.copyOf(configuredAutoMessages);

        chat.validate();
        return chat;
    }

    private void validate() {
        if (defaultChannel == null) defaultChannel = Channel.LOCAL;
        localRadius = Math.max(1.0, Math.min(100_000.0, localRadius));
        cooldownMillis = Math.clamp(cooldownMillis, 0, 600_000);
        maxMessageLength = Math.clamp(maxMessageLength, 16, 2_000);
        capsPercent = Math.clamp(capsPercent, 10, 100);
        duplicateWindowMillis = Math.clamp(duplicateWindowMillis, 0, 600_000);
        autoMessagesIntervalSeconds = Math.clamp(autoMessagesIntervalSeconds, 30, 86_400);
        autoMessagesMinimumPlayers = Math.clamp(autoMessagesMinimumPlayers, 0, 10_000);
        maxMentions = Math.clamp(maxMentions, 0, 50);
        slowModeSeconds = Math.clamp(slowModeSeconds, 0, 3_600);
        chatLogKeepDays = Math.clamp(chatLogKeepDays, 1, 3_650);
        quickGlobalPrefix = quickGlobalPrefix == null ? "" : quickGlobalPrefix.trim();
        mentionColor = isBlank(mentionColor) ? "&e" : mentionColor.trim();
        localFormat = sanitizeFormat(localFormat, "&7[Л] &f%player%&7: &f%message%");
        globalFormat = sanitizeFormat(globalFormat, "&6[Г] &f%player%&7: &f%message%");
        privateSendFormat = sanitizeFormat(privateSendFormat, "&7[&dЯ &7→ &d%target%&7] &f%message%");
        privateReceiveFormat = sanitizeFormat(privateReceiveFormat, "&7[&d%sender% &7→ &dЯ&7] &f%message%");
        socialSpyFormat = sanitizeFormat(socialSpyFormat, "&8[SPY] &7%sender% &8→ &7%target%&8: &r%message%");
        joinMessage = sanitizeFormat(joinMessage, "&8[&a+&8] &7%player% &7присоединился к серверу");
        quitMessage = sanitizeFormat(quitMessage, "&8[&c-&8] &7%player% &7покинул сервер");
        autoMessagesPrefix = autoMessagesPrefix == null ? "" : autoMessagesPrefix;
        if (autoMessages == null || autoMessages.isEmpty()) {
            autoMessages = List.copyOf(DEFAULT_AUTO_MESSAGES);
        }
        this.filter = compileFilter();
    }

    private ChatFilter compileFilter() {
        List<Pattern> compiled = new ArrayList<>();
        if (filterPatternsRaw != null) {
            for (String raw : filterPatternsRaw) {
                if (raw == null || raw.isBlank()) continue;
                try {
                    compiled.add(Pattern.compile(raw));
                } catch (PatternSyntaxException ignored) {
                    // Invalid administrator-provided regex is skipped instead of breaking the chat.
                }
            }
        }
        return new ChatFilter(                filterEnabled,
                filterBlockMode,
                filterWords == null ? List.of() : List.copyOf(filterWords),
                filterWhitelist == null ? List.of() : filterWhitelist.stream()
                        .filter(entry -> entry != null && !entry.isBlank())
                        .map(entry -> entry.toLowerCase(Locale.ROOT))
                        .toList(),
                List.copyOf(compiled));
    }

    private static String sanitizeFormat(String raw, String fallback) {
        return isBlank(raw) ? fallback : raw;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public boolean enabled() {
        return enabled;
    }

    public Channel defaultChannel() {
        return defaultChannel;
    }

    public double localRadius() {
        return localRadius;
    }

    public boolean localCrossWorld() {
        return localCrossWorld;
    }

    public String quickGlobalPrefix() {
        return quickGlobalPrefix;
    }

    public String localFormat() {
        return localFormat;
    }

    public String globalFormat() {
        return globalFormat;
    }

    public boolean allowColorCodes() {
        return allowColorCodes;
    }

    public int cooldownMillis() {
        return cooldownMillis;
    }

    public int maxMessageLength() {
        return maxMessageLength;
    }

    public boolean antiCaps() {
        return antiCaps;
    }

    public int capsPercent() {
        return capsPercent;
    }

    public boolean blockDuplicates() {
        return blockDuplicates;
    }

    public int duplicateWindowMillis() {
        return duplicateWindowMillis;
    }

    public boolean mentionHighlight() {
        return mentionHighlight;
    }

    public String mentionColor() {
        return mentionColor;
    }

    public boolean mentionSound() {
        return mentionSound;
    }

    public boolean joinQuitMessages() {
        return joinQuitMessages;
    }

    public String joinMessage() {
        return joinMessage;
    }

    public String quitMessage() {
        return quitMessage;
    }

    public boolean privateMessagesEnabled() {
        return privateMessagesEnabled;
    }

    public boolean privateMessageSound() {
        return privateMessageSound;
    }

    public String privateSendFormat() {
        return privateSendFormat;
    }

    public String privateReceiveFormat() {
        return privateReceiveFormat;
    }

    public String socialSpyFormat() {
        return socialSpyFormat;
    }

    public boolean autoMessagesEnabled() {
        return autoMessagesEnabled;
    }

    public int autoMessagesIntervalSeconds() {
        return autoMessagesIntervalSeconds;
    }

    public boolean autoMessagesRandomOrder() {
        return autoMessagesRandomOrder;
    }

    public int autoMessagesMinimumPlayers() {
        return autoMessagesMinimumPlayers;
    }

    public String autoMessagesPrefix() {
        return autoMessagesPrefix;
    }

    public List<String> autoMessages() {
        return autoMessages;
    }

    /** Maximum number of {@code @mentions} honoured in one message (0 = unlimited). */
    public int maxMentions() {
        return maxMentions;
    }

    /** Server-wide slow mode for the global channel in seconds (0 = disabled). */
    public int slowModeSeconds() {
        return slowModeSeconds;
    }

    /** Compiled moderation filter (words, regex, whitelist). */
    public ChatFilter filter() {
        return filter;
    }

    public boolean filterNotifyAdmins() {
        return filterNotifyAdmins;
    }

    public boolean chatLogEnabled() {
        return chatLogEnabled;
    }

    public int chatLogKeepDays() {
        return chatLogKeepDays;
    }

    public boolean chatLogIncludePrivate() {
        return chatLogIncludePrivate;
    }

    /** Formats the current channel for status and chat commands. */
    public static String describeChannel(Channel channel) {
        return channel == Channel.GLOBAL ? "ГЛОБАЛЬНЫЙ (весь сервер)" : "ЛОКАЛЬНЫЙ (радиус)";
    }

    /** Returns a plain string list of the default auto-messages (used by tests and config generation). */
    public static List<String> defaultAutoMessages() {
        return new ArrayList<>(DEFAULT_AUTO_MESSAGES);
    }
}
