package ru.cherdakmd.chrdkbosses;

import java.util.Locale;

/**
 * Эффект зоны опасности (1.4.162).
 *
 * <p>Ид эффекта хранится строкой, а не {@code PotionEffectType}: конфиг должен
 * читаться и проверяться тестами без живого сервера, а неизвестный ид —
 * пропускаться с предупреждением, а не ронять плагин.</p>
 *
 * @param effect          ид эффекта ({@code slowness}, {@code darkness})
 * @param amplifier       уровень с нуля
 * @param durationSeconds длительность одного наложения
 */
public record ZoneEffect(String effect, int amplifier, int durationSeconds) {

    /** Потолок уровня: выше — уже не зона, а приговор. */
    public static final int MAX_AMPLIFIER = 4;
    /** Потолок длительности наложения. */
    public static final int MAX_DURATION_SECONDS = 60;

    public ZoneEffect {
        effect = effect == null ? "" : effect.trim().toLowerCase(Locale.ROOT);
        amplifier = Math.clamp(amplifier, 0, MAX_AMPLIFIER);
        durationSeconds = Math.clamp(durationSeconds, 1, MAX_DURATION_SECONDS);
    }

    /** Годится ли эффект: пустой ид пропускается. */
    public boolean usable() {
        return !effect.isEmpty();
    }
}
