package ru.cherdakmd.chrdkanticheat;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Настройки моста античита из config.yml.
 *
 * <p>Поля приходят из рукописного конфига, поэтому числа зажимаются в
 * безопасных пределах: кривое значение не должно выключать журнал или
 * заливать чат алертами.</p>
 */
record AnticheatConfig(boolean enabled, int throttleSeconds, int keepDays) {

    static AnticheatConfig from(ConfigurationSection config) {
        return new AnticheatConfig(
                config.getBoolean("general.enabled", true),
                (int) clamp(config.getLong("alerts.throttle-seconds", 10), 1, 3600),
                (int) clamp(config.getLong("journal.keep-days", 30), 1, 365));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
