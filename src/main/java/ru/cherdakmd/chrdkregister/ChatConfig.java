package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Validated settings for the built-in chat (local/global channels), private messages, and automatic broadcasts.
 */
public final class ChatConfig {
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

    /** Formats the current channel for status and chat commands. */
    public static String describeChannel(Channel channel) {
        return channel == Channel.GLOBAL ? "ГЛОБАЛЬНЫЙ (весь сервер)" : "ЛОКАЛЬНЫЙ (радиус)";
    }

    /** Returns a plain string list of the default auto-messages (used by tests and config generation). */
    public static List<String> defaultAutoMessages() {
        return new ArrayList<>(DEFAULT_AUTO_MESSAGES);
    }
}
