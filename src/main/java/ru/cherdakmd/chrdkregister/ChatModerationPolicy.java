package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Независимые правила никнеймов, антимата и антиспама. Состояние живёт только в памяти процесса. */
final class ChatModerationPolicy {
    enum Channel { CHAT, PRIVATE }
    enum Status { ALLOW, BLOCK }
    record Decision(Status status, String text, String messageKey) {
        static Decision allow(String text) { return new Decision(Status.ALLOW, text, null); }
        static Decision block(String messageKey) { return new Decision(Status.BLOCK, null, messageKey); }
    }
    record NickSettings(boolean enabled, List<String> denied, List<String> allowed) { }
    record SwearSettings(boolean enabled, boolean block, String replacement, List<String> words,
                         List<Pattern> patterns, List<String> allowed, String bypassPermission) { }
    record SpamSettings(boolean enabled, boolean chat, boolean privateMessages, long cooldownMillis,
                        long duplicateWindowMillis, int maxMessages, long burstWindowMillis,
                        String bypassPermission) { }

    private static final class History {
        long lastAt;
        String lastText;
        final Deque<Long> recent = new ArrayDeque<>();
    }

    private volatile NickSettings nicknames;
    private volatile SwearSettings swears;
    private volatile SpamSettings spam;
    private final Map<UUID, History> history = new HashMap<>();

    ChatModerationPolicy(ConfigurationSection nicknames, ConfigurationSection swears, ConfigurationSection spam) {
        reload(nicknames, swears, spam);
    }

    final void reload(ConfigurationSection nickConfig, ConfigurationSection swearConfig, ConfigurationSection spamConfig) {
        NickSettings newNicknames = new NickSettings(nickConfig.getBoolean("enabled", true),
                clean(nickConfig.getStringList("denied-fragments")), clean(nickConfig.getStringList("allowed-names")));
        List<Pattern> patterns = new ArrayList<>();
        for (String raw : swearConfig.getStringList("regex")) {
            try { patterns.add(Pattern.compile(raw, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)); }
            catch (PatternSyntaxException error) { throw new IllegalArgumentException("Неверный regex в anti-swear.yml: " + raw, error); }
        }
        SwearSettings newSwears = new SwearSettings(swearConfig.getBoolean("enabled", true),
                "block".equalsIgnoreCase(swearConfig.getString("mode", "replace")),
                swearConfig.getString("replacement", "***"), clean(swearConfig.getStringList("words")),
                List.copyOf(patterns), clean(swearConfig.getStringList("whitelist")),
                swearConfig.getString("bypass-permission", "chrdkregister.moderation.swear.bypass"));
        SpamSettings newSpam = new SpamSettings(spamConfig.getBoolean("enabled", true),
                spamConfig.getBoolean("apply-to-chat", true), spamConfig.getBoolean("apply-to-private", true),
                millis(spamConfig, "cooldown-ms", 1_000), millis(spamConfig, "duplicate-window-ms", 15_000),
                Math.max(0, spamConfig.getInt("burst.max-messages", 6)),
                millis(spamConfig, "burst.window-ms", 10_000),
                spamConfig.getString("bypass-permission", "chrdkregister.moderation.spam.bypass"));
        // Сначала валидируются все три файла, затем атомарно меняются снимки. Старые правила не затираются ошибкой.
        nicknames = newNicknames; swears = newSwears; spam = newSpam;
        synchronized (history) { history.clear(); }
    }

    boolean isNicknameAllowed(String name) {
        NickSettings settings = nicknames;
        if (!settings.enabled() || name == null) return true;
        String normalized = normalize(name);
        for (String allowed : settings.allowed()) if (normalize(allowed).equals(normalized)) return true;
        for (String denied : settings.denied()) if (normalized.contains(normalize(denied))) return false;
        return true;
    }

    Decision moderate(UUID player, String raw, Channel channel, boolean swearBypass, boolean spamBypass, long now) {
        if (raw == null) return Decision.block("chat.empty");
        String text = raw.trim();
        SwearSettings swear = swears;
        if (swear.enabled() && !swearBypass && !whitelisted(text, swear.allowed())) {
            String masked = mask(text, swear);
            if (!masked.equals(text)) {
                if (swear.block()) return Decision.block("chat.swear-blocked");
                text = masked;
            }
        }
        SpamSettings settings = spam;
        if (!settings.enabled() || spamBypass || channel == Channel.CHAT && !settings.chat()
                || channel == Channel.PRIVATE && !settings.privateMessages()) return Decision.allow(text);
        synchronized (history) {
            History entry = history.computeIfAbsent(player, ignored -> new History());
            if (now < entry.lastAt) { entry.lastAt = 0; entry.lastText = null; entry.recent.clear(); }
            while (!entry.recent.isEmpty() && now - entry.recent.peekFirst() >= settings.burstWindowMillis()) {
                entry.recent.removeFirst();
            }
            if (settings.duplicateWindowMillis() > 0 && entry.lastText != null
                    && now - entry.lastAt <= settings.duplicateWindowMillis()
                    && normalizeMessage(entry.lastText).equals(normalizeMessage(text))) {
                return Decision.block("chat.anti-spam-duplicate");
            }
            if (settings.cooldownMillis() > 0 && entry.lastText != null
                    && now - entry.lastAt < settings.cooldownMillis()) {
                return Decision.block("chat.anti-spam-cooldown");
            }
            if (settings.maxMessages() > 0 && settings.burstWindowMillis() > 0
                    && entry.recent.size() >= settings.maxMessages()) {
                return Decision.block("chat.anti-spam-flood");
            }
            entry.lastAt = now; entry.lastText = text; entry.recent.addLast(now);
            return Decision.allow(text);
        }
    }

    void forget(UUID player) { synchronized (history) { history.remove(player); } }

    String bypassPermission(boolean swear) {
        return swear ? swears.bypassPermission() : spam.bypassPermission();
    }

    private static String mask(String text, SwearSettings settings) {
        String result = text;
        for (String word : settings.words()) {
            if (word.isBlank()) continue;
            result = Pattern.compile(Pattern.quote(word), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    .matcher(result).replaceAll(java.util.regex.Matcher.quoteReplacement(settings.replacement()));
        }
        for (Pattern pattern : settings.patterns()) {
            result = pattern.matcher(result).replaceAll(java.util.regex.Matcher.quoteReplacement(settings.replacement()));
        }
        return result;
    }

    private static boolean whitelisted(String text, List<String> allowed) {
        String normalized = normalizeMessage(text);
        for (String entry : allowed) if (!entry.isBlank() && normalized.contains(normalizeMessage(entry))) return true;
        return false;
    }
    private static String normalize(String text) { return text.trim().toLowerCase(Locale.ROOT); }
    private static String normalizeMessage(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}]+", " ").trim();
    }
    private static List<String> clean(List<String> values) {
        return values.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).toList();
    }
    private static long millis(ConfigurationSection config, String path, long fallback) {
        return Math.max(0, Math.min(86_400_000L, config.getLong(path, fallback)));
    }
}
