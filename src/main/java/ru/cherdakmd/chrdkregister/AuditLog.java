package ru.cherdakmd.chrdkregister;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Append-only audit trail (one JSON object per line) and daily chat history.
 *
 * <p>Files: {@code audit/audit-YYYY-MM-DD.jsonl} and {@code logs/chat/chat-YYYY-MM-DD.log}. Both are rotated
 * by date and pruned according to {@code audit.keep-days} and {@code chat.chat-log.keep-days}.</p>
 */
public final class AuditLog {
    /** Well-known audit event names. */
    public static final String PASS_GRANT_DONATEPAY = "pass.grant.donatepay";
    public static final String PASS_GRANT_MANUAL = "pass.grant.manual";
    public static final String PASS_GRANT_GIFT = "pass.grant.gift";
    public static final String PASS_REVOKE = "pass.revoke";
    public static final String PASS_EXPIRE = "pass.expire";
    public static final String PASSWORD_CHANGE = "password.change";
    public static final String PASSWORD_RESET = "password.reset";
    public static final String TOTP_ENABLE = "totp.enable";
    public static final String TOTP_DISABLE = "totp.disable";
    public static final String TOTP_RESET = "totp.reset";
    public static final String LOGIN_SUCCESS = "login.success";
    public static final String LOGIN_FAIL = "login.fail";
    public static final String SESSION_RESTORE = "session.restore";
    public static final String SESSION_END = "session.end";
    public static final String BRUTEFORCE_ALERT = "bruteforce.alert";
    public static final String MULTIALT_ALERT = "multialt.alert";
    public static final String DONATION_PENDING = "donation.pending";
    public static final String DONATION_APPROVED = "donation.approved";
    public static final String DONATION_REJECTED = "donation.rejected";
    public static final String DONATION_CLAIMED = "donation.claimed";
    public static final String DONATEPAY_ERROR = "donatepay.error";
    public static final String STORAGE_MIGRATION = "storage.migration";
    public static final String STORAGE_BACKUP = "storage.backup";
    public static final String STORAGE_RESTORE = "storage.restore";
    public static final String CONFIG_RELOAD = "config.reload";
    public static final String CHAT_MUTE = "chat.mute";
    public static final String CHAT_UNMUTE = "chat.unmute";
    public static final String CHAT_FILTER = "chat.filter";
    public static final String CHAT_BROADCAST = "chat.broadcast";
    public static final String ADMIN_COMMAND = "admin.command";

    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Path auditDirectory;
    private final Path chatDirectory;
    private final Logger logger;
    private final ZoneId zone;

    public AuditLog(Path dataFolder, Logger logger, ZoneId zone) {
        this.auditDirectory = dataFolder.resolve("audit");
        this.chatDirectory = dataFolder.resolve("logs").resolve("chat");
        this.logger = logger;
        this.zone = zone == null ? ZoneId.of("Europe/Moscow") : zone;
    }

    /** Writes one audit event. Never throws: audit failures must not break gameplay. */
    public void log(String event, String actor, String target, String ip, Map<String, Object> details) {
        try {
            Files.createDirectories(auditDirectory);
            JsonObject entry = new JsonObject();
            entry.addProperty("time", Instant.now().toString());
            entry.addProperty("event", event == null ? "unknown" : event);
            entry.addProperty("actor", actor == null ? "" : actor);
            entry.addProperty("target", target == null ? "" : target);
            entry.addProperty("ip", maskIp(ip));
            if (details != null && !details.isEmpty()) {
                JsonObject payload = new JsonObject();
                for (Map.Entry<String, Object> detail : details.entrySet()) {
                    if (detail.getValue() == null) continue;
                    if (detail.getValue() instanceof Number number) {
                        payload.addProperty(detail.getKey(), number);
                    } else if (detail.getValue() instanceof Boolean bool) {
                        payload.addProperty(detail.getKey(), bool);
                    } else {
                        payload.addProperty(detail.getKey(), String.valueOf(detail.getValue()));
                    }
                }
                entry.add("details", payload);
            }
            Path file = auditDirectory.resolve("audit-" + FILE_STAMP.format(LocalDate.now(zone)) + ".jsonl");
            Files.writeString(file, GSON.toJson(entry) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Could not write the audit entry " + event, exception);
        }
    }

    /** Convenience overload for events without extra details. */
    public void log(String event, String actor, String target, String ip) {
        log(event, actor, target, ip, Map.of());
    }

    /** Appends one chat line to the daily history file. */
    public void chat(String channel, String sender, String target, String message) {
        try {
            Files.createDirectories(chatDirectory);
            String stamp = DateTimeFormatter.ofPattern("HH:mm:ss").format(
                    java.time.LocalDateTime.now(zone));
            StringBuilder line = new StringBuilder()
                    .append('[').append(stamp).append("] [").append(channel == null ? "?" : channel).append(']')
                    .append(' ').append(sender == null ? "?" : sender);
            if (target != null && !target.isBlank()) {
                line.append(" -> ").append(target);
            }
            line.append(": ").append(message == null ? "" : message);
            Path file = chatDirectory.resolve("chat-" + FILE_STAMP.format(LocalDate.now(zone)) + ".log");
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Could not write the chat history line", exception);
        }
    }

    /**
     * Reads recent audit entries, newest first.
     *
     * @param playerFilter when set, only entries where actor or target matches (case-insensitive) are returned
     * @param eventFilter optional exact event name
     * @param limit maximum number of entries
     */
    public List<JsonObject> read(String playerFilter, String eventFilter, int limit) {
        List<JsonObject> result = new ArrayList<>();
        List<Path> files = auditFiles();
        String player = playerFilter == null ? "" : playerFilter.trim().toLowerCase(Locale.ROOT);
        for (Path file : files) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (Exception exception) {
                continue;
            }
            for (int index = lines.size() - 1; index >= 0; index--) {
                String line = lines.get(index);
                if (line.isBlank()) continue;
                JsonObject entry;
                try {
                    entry = JsonParser.parseString(line).getAsJsonObject();
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (eventFilter != null && !eventFilter.isBlank()) {
                    String event = entry.has("event") ? entry.get("event").getAsString() : "";
                    if (!eventFilter.equalsIgnoreCase(event)) continue;
                }
                if (!player.isEmpty()) {
                    String actor = entry.has("actor") ? entry.get("actor").getAsString().toLowerCase(Locale.ROOT) : "";
                    String target = entry.has("target") ? entry.get("target").getAsString().toLowerCase(Locale.ROOT) : "";
                    if (!actor.contains(player) && !target.contains(player)) continue;
                }
                result.add(entry);
                if (result.size() >= Math.max(1, limit)) {
                    return result;
                }
            }
        }
        return result;
    }

    /** Deletes audit and chat files older than the configured retention. */
    public void purge(int auditKeepDays, int chatKeepDays) {
        purgeDirectory(auditDirectory, "audit-", auditKeepDays);
        purgeDirectory(chatDirectory, "chat-", chatKeepDays);
    }

    private void purgeDirectory(Path directory, String prefix, int keepDays) {
        if (keepDays <= 0 || Files.notExists(directory)) return;
        LocalDate threshold = LocalDate.now(zone).minusDays(keepDays);
        try (var stream = Files.list(directory)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString();
                if (!name.startsWith(prefix)) continue;
                Optional<LocalDate> date = parseDate(name.substring(prefix.length()));
                if (date.isEmpty()) continue;
                if (date.get().isBefore(threshold)) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Could not purge old files in " + directory.toAbsolutePath(), exception);
        }
    }

    private static Optional<LocalDate> parseDate(String raw) {
        int dot = raw.indexOf('.');
        String candidate = dot > 0 ? raw.substring(0, dot) : raw;
        try {
            return Optional.of(LocalDate.parse(candidate, FILE_STAMP));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private List<Path> auditFiles() {
        if (Files.notExists(auditDirectory)) return List.of();
        try (var stream = Files.list(auditDirectory)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .toList();
        } catch (Exception exception) {
            return List.of();
        }
    }

    /** Masks the last octet/segment of an IP address before it is written to disk. */
    public static String maskIp(String ip) {
        if (ip == null || ip.isBlank()) return "";
        int lastDot = ip.lastIndexOf('.');
        if (lastDot > 0) {
            return ip.substring(0, lastDot) + ".*";
        }
        int lastColon = ip.lastIndexOf(':');
        if (lastColon > 0) {
            return ip.substring(0, lastColon) + ":*";
        }
        return ip;
    }

    /** Formats a duration for audit output, e.g. {@code "12 дней"}. */
    public static String describeDuration(long millis) {
        Duration duration = Duration.ofMillis(Math.max(0, millis));
        long days = duration.toDays();
        if (days > 0) return days + " дн.";
        long hours = duration.toHours();
        if (hours > 0) return hours + " ч.";
        return Math.max(1, duration.toMinutes()) + " мин.";
    }

    /** Builds a details map for audit calls without boilerplate. */
    public static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            map.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
        }
        return map;
    }
}
