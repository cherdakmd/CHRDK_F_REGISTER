package ru.cherdakmd.chrdkvk;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Одноразовые коды подтверждения привязки аккаунта Minecraft ↔ ВКонтакте.
 *
 * <p>Алфавит исключает похожие символы ({@code 0/O}, {@code 1/I}), у каждого игрока
 * одновременно активен не более одного кода, а после успешного использования или
 * истечения TTL код немедленно удаляется.</p>
 */
public final class VkLinkCodeManager {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    public enum Status {
        ISSUED,
        COOLDOWN
    }

    public record IssueResult(Status status, String code, long remainingCooldownSeconds) {
    }

    private record PendingCode(String playerName, String canonicalName, String code, long expiresAtMillis) {
    }

    private final SecureRandom random = new SecureRandom();
    private final Map<String, PendingCode> byCode = new HashMap<>();
    private final Map<String, String> codeByCanonicalPlayer = new HashMap<>();
    private final Map<String, Long> lastIssuedAt = new HashMap<>();

    public synchronized IssueResult issue(String playerName, int length, int ttlSeconds,
                                          int cooldownSeconds, long nowMillis) {
        pruneExpired(nowMillis);
        String canonical = VkStore.canonical(playerName);
        if (canonical.isEmpty()) {
            throw new IllegalArgumentException("playerName must not be blank");
        }
        if (cooldownSeconds > 0) {
            Long previous = lastIssuedAt.get(canonical);
            long cooldownMillis = cooldownSeconds * 1_000L;
            if (previous != null && nowMillis - previous < cooldownMillis) {
                long remaining = Math.max(1L, (cooldownMillis - (nowMillis - previous) + 999L) / 1_000L);
                return new IssueResult(Status.COOLDOWN, "", remaining);
            }
        }
        String existingCode = codeByCanonicalPlayer.remove(canonical);
        if (existingCode != null) {
            byCode.remove(existingCode);
        }
        int safeLen = Math.clamp(length, 4, 10);
        String generated = generateUniqueCode(safeLen);
        long expiresAt = nowMillis + Math.max(1, ttlSeconds) * 1_000L;
        PendingCode pending = new PendingCode(playerName.trim(), canonical, generated, expiresAt);
        byCode.put(generated, pending);
        codeByCanonicalPlayer.put(canonical, generated);
        lastIssuedAt.put(canonical, nowMillis);
        return new IssueResult(Status.ISSUED, generated, 0L);
    }

    /** Проверяет и одноразово погашает код, возвращая имя игрока при успехе. */
    public synchronized Optional<String> consume(String rawCode, long nowMillis) {
        pruneExpired(nowMillis);
        String normalized = normalizeCode(rawCode);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        PendingCode pending = byCode.remove(normalized);
        if (pending == null) {
            return Optional.empty();
        }
        codeByCanonicalPlayer.remove(pending.canonicalName);
        if (pending.expiresAtMillis <= nowMillis) {
            return Optional.empty();
        }
        return Optional.of(pending.playerName);
    }

    /** Приводит потенциальный код из ЛС ВК к верхнему регистру и проверяет допустимые символы. */
    public static String normalizeCode(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim().toUpperCase(Locale.ROOT);
        if (trimmed.length() < 4 || trimmed.length() > 10) {
            return "";
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (ALPHABET.indexOf(trimmed.charAt(i)) < 0) {
                return "";
            }
        }
        return trimmed;
    }

    public synchronized int activeCount(long nowMillis) {
        pruneExpired(nowMillis);
        return byCode.size();
    }

    private String generateUniqueCode(int length) {
        for (int attempt = 0; attempt < 64; attempt++) {
            StringBuilder sb = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            String candidate = sb.toString();
            if (!byCode.containsKey(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate unique VK link code");
    }

    private void pruneExpired(long nowMillis) {
        Iterator<Map.Entry<String, PendingCode>> iterator = byCode.entrySet().iterator();
        while (iterator.hasNext()) {
            PendingCode pending = iterator.next().getValue();
            if (pending.expiresAtMillis <= nowMillis) {
                iterator.remove();
                codeByCanonicalPlayer.remove(pending.canonicalName, pending.code);
            }
        }
    }
}
