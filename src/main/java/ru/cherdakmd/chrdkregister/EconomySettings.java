package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Настройки банка алмазов {@code /bal}, {@code /pay}, {@code /baltop} (секция
 * {@code economy:}).
 *
 * <p>Валюта сервера — алмазы: физический предмет в инвентаре и «банк» на счёте
 * игрока. Курс фиксированный (алмазный блок = 9 алмазов), поэтому депозит и
 * снятие ничего не теряют. Переводы между игроками идут по счёту: они работают
 * для офлайн-получателей и не зависят от свободного места в инвентаре.</p>
 *
 * @param enabled          мастер-флаг банка и команд /bal /pay /baltop
 * @param blockWorth       сколько алмазов даёт один алмазный блок при депозите
 * @param minPay           минимальная сумма одного перевода
 * @param maxPay           максимальная сумма одного перевода
 * @param payTaxPercent    комиссия перевода в процентах (0 — без комиссии)
 * @param payCooldownSeconds пауза между переводами одного игрока
 * @param baltopSize       сколько строк показывает /baltop
 * @param allowOfflinePay  разрешать переводы игрокам не в сети
 */
public record EconomySettings(boolean enabled, int blockWorth, long minPay, long maxPay,
                              int payTaxPercent, int payCooldownSeconds, int baltopSize,
                              boolean allowOfflinePay) {

    /** Значения по умолчанию, если секция отсутствует целиком. */
    public static final EconomySettings DEFAULTS =
            new EconomySettings(true, 9, 1L, 1_000_000L, 2, 2, 10, true);

    public EconomySettings {
        blockWorth = Math.clamp(blockWorth, 1, 64);
        minPay = Math.clamp(minPay, 1L, 1_000_000_000L);
        maxPay = Math.clamp(maxPay, minPay, 1_000_000_000L);
        payTaxPercent = Math.clamp(payTaxPercent, 0, 50);
        payCooldownSeconds = Math.clamp(payCooldownSeconds, 0, 3600);
        baltopSize = Math.clamp(baltopSize, 3, 50);
    }

    public static EconomySettings from(FileConfiguration config) {
        if (config == null) {
            return DEFAULTS;
        }
        return new EconomySettings(
                config.getBoolean("economy.enabled", DEFAULTS.enabled),
                config.getInt("economy.block-worth", DEFAULTS.blockWorth),
                config.getLong("economy.min-pay", DEFAULTS.minPay),
                config.getLong("economy.max-pay", DEFAULTS.maxPay),
                config.getInt("economy.pay-tax-percent", DEFAULTS.payTaxPercent),
                config.getInt("economy.pay-cooldown-seconds", DEFAULTS.payCooldownSeconds),
                config.getInt("economy.baltop-size", DEFAULTS.baltopSize),
                config.getBoolean("economy.allow-offline-pay", DEFAULTS.allowOfflinePay));
    }
}
