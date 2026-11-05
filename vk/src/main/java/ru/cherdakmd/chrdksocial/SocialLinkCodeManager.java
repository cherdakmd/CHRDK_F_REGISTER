package ru.cherdakmd.chrdksocial;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Единое пространство одноразовых кодов входа для ВК и MAX.
 *
 * <p>Код выдаётся игроку при входе на сервер и может быть погашен в <b>любом</b>
 * из двух мессенджеров. Алфавит исключает похожие символы, на игрока активен
 * не более одного кода, действуют TTL, пауза между перевыпусками и лимит
 * неудачных попыток с отправителя (защита от перебора).</p>
 *
 * <p>Проверка ({@link #verify}) не удаляет код: он гасится только
 * {@link #consume(String)} после успешной записи привязки. Так неудачная
 * попытка привязки (например, профиль уже занят) не оставляет игрока без кода.</p>
 */
public final class SocialLinkCodeManager {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    public enum Status { ISSUED, COOLDOWN }
    public enum Verify { FOUND, NOT_FOUND, LOCKED }

    public record IssueResult(Status status, String code, long remainingCooldownSeconds) { }
    public record VerifyResult(Verify status, String playerName, long lockRemainingSeconds) { }

    private record Pending(String playerName, String canonical, String code, long expiresAt) { }

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> byCode = new HashMap<>();
    private final Map<String, String> codeByPlayer = new HashMap<>();
    private final Map<String, Long> lastIssued = new HashMap<>();
    private final Map<String, Integer> failures = new HashMap<>();
    private final Map<String, Long> lockedUntil = new HashMap<>();

    public static String canonical(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    /** Приводит текст из мессенджера к каноническому виду кода. */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim().toUpperCase(Locale.ROOT);
        if (trimmed.length() < 4 || trimmed.length() > 10) return "";
        for (int i = 0; i < trimmed.length(); i++) {
            if (ALPHABET.indexOf(trimmed.charAt(i)) < 0) return "";
        }
        return trimmed;
    }

    public synchronized IssueResult issue(String playerName, int length, int ttlSeconds,
                                          int cooldownSeconds, long now) {
        prune(now);
        String key = canonical(playerName);
        if (key.isEmpty()) throw new IllegalArgumentException("playerName must not be blank");
        Pending existing = activeFor(key, now);
        if (existing != null && cooldownSeconds > 0) {
            Long previous = lastIssued.get(key);
            if (previous != null && now - previous < cooldownSeconds * 1_000L) {
                long left = Math.max(1L, (cooldownSeconds * 1_000L - (now - previous) + 999L) / 1_000L);
                return new IssueResult(Status.COOLDOWN, existing.code(), left);
            }
        }
        String old = codeByPlayer.remove(key);
        if (old != null) byCode.remove(old);
        int safeLength = Math.clamp(length, 4, 10);
        String code = uniqueCode(safeLength);
        Pending pending = new Pending(playerName.trim(), key, code,
                now + Math.max(1, ttlSeconds) * 1_000L);
        byCode.put(code, pending);
        codeByPlayer.put(key, code);
        lastIssued.put(key, now);
        failures.remove(key);
        return new IssueResult(Status.ISSUED, code, 0L);
    }

    /** Возвращает живой код игрока без перевыпуска (для повторного показа при релоге). */
    public synchronized Optional<String> peek(String playerName, long now) {
        prune(now);
        Pending pending = activeFor(canonical(playerName), now);
        return pending == null ? Optional.empty() : Optional.of(pending.code());
    }

    /** Проверяет код, но не гасит его. {@code senderKey} — «vk:123» или «max:456». */
    public synchronized VerifyResult verify(String rawCode, String senderKey, int maxAttempts,
                                            long lockSeconds, long now) {
        prune(now);
        String sender = senderKey == null || senderKey.isBlank() ? "unknown" : senderKey;
        Long locked = lockedUntil.get(sender);
        if (locked != null && locked > now) {
            return new VerifyResult(Verify.LOCKED, "", Math.max(1L, (locked - now + 999L) / 1_000L));
        }
        lockedUntil.remove(sender);
        String code = normalize(rawCode);
        Pending pending = code.isEmpty() ? null : byCode.get(code);
        if (pending == null) {
            int count = failures.getOrDefault(sender, 0) + 1;
            failures.put(sender, count);
            if (count >= maxAttempts) {
                lockedUntil.put(sender, now + lockSeconds * 1_000L);
                failures.remove(sender);
                return new VerifyResult(Verify.LOCKED, "", lockSeconds);
            }
            return new VerifyResult(Verify.NOT_FOUND, "", 0L);
        }
        failures.remove(sender);
        return new VerifyResult(Verify.FOUND, pending.playerName(), 0L);
    }

    /** Успешный старый /vk link не должен копить неудачные попытки кода входа. */
    public synchronized void clearFailures(String senderKey) {
        if (senderKey == null) return;
        failures.remove(senderKey);
        lockedUntil.remove(senderKey);
    }

    /** Гасит код после успешной привязки. */
    public synchronized boolean consume(String rawCode) {
        String code = normalize(rawCode);
        Pending pending = code.isEmpty() ? null : byCode.remove(code);
        if (pending == null) return false;
        codeByPlayer.remove(pending.canonical(), code);
        return true;
    }

    public synchronized int activeCount(long now) {
        prune(now);
        return byCode.size();
    }

    private Pending activeFor(String canonical, long now) {
        String code = codeByPlayer.get(canonical);
        if (code == null) return null;
        Pending pending = byCode.get(code);
        if (pending == null || pending.expiresAt() <= now) {
            byCode.remove(code);
            codeByPlayer.remove(canonical, code);
            return null;
        }
        return pending;
    }

    private String uniqueCode(int length) {
        for (int attempt = 0; attempt < 64; attempt++) {
            StringBuilder sb = new StringBuilder(length);
            for (int i = 0; i < length; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            String candidate = sb.toString();
            if (!byCode.containsKey(candidate)) return candidate;
        }
        throw new IllegalStateException("Could not allocate unique social link code");
    }

    private void prune(long now) {
        Iterator<Map.Entry<String, Pending>> it = byCode.entrySet().iterator();
        while (it.hasNext()) {
            Pending pending = it.next().getValue();
            if (pending.expiresAt() <= now) {
                it.remove();
                codeByPlayer.remove(pending.canonical(), pending.code());
            }
        }
        lockedUntil.values().removeIf(until -> until <= now);
    }
}
