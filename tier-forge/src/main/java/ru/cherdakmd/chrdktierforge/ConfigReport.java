package ru.cherdakmd.chrdktierforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Сводка действующих настроек в консоль (1.4.100).
 *
 * <p>Аддон читает десятки ключей с клампами и фолбэками, поэтому «что реально
 * включено» после правки `config.yml` приходилось выяснять опытом. Отчёт печатает
 * итоговые значения при старте и после `/tiersadmin reload` — с пометками
 * «выключено» и фолбэком шансов на общий.
 *
 * <p>Тексты здесь консольные (лог сервера), а не игровые: правила
 * `messages.yml` на них не распространяются.
 *
 * <p>Чистая логика — покрыто {@link ConfigReportTest}.
 */
final class ConfigReport {

    private ConfigReport() {
    }

    /** Строки отчёта в стабильном порядке (без лог-префикса). */
    static List<String> lines(TierForgeConfig config) {
        List<String> out = new ArrayList<>();
        if (config == null) {
            out.add("конфиг не загружен");
            return out;
        }
        out.add("крафт: " + (config.rollOnCraftChancePercent() > 0
                ? config.rollOnCraftChancePercent() + "%"
                : "выключен")
                + ", группы — оружие " + onOff(config.craftWeapons())
                + ", броня " + onOff(config.craftArmor())
                + ", инструменты " + onOff(config.craftTools()));
        out.add("опознание: " + (config.identifyUnidentified()
                ? "включено (скан каждые " + config.identifyScanTicks()
                + " тиков, " + config.identifyScanPlayersPerTick() + " игрок(ов) за тик)"
                : "выключено — тир виден сразу"));
        out.add("наковальня: " + onOff(config.anvilEnabled())
                + " (базовая цена " + config.anvilBaseXp() + " опыта)"
                + ", перекат " + (config.rerollEnabled()
                ? onOff(true) + " за " + config.rerollCatalyst()
                + (config.rerollNoDowngrade() ? " без понижения" : "")
                : "выключен")
                + ", ковка " + onOff(config.improveEnabled()));
        out.add("дроп с мобов: " + (config.mobDropEnabled()
                ? "общий " + config.mobDropChancePercent() + "%, по видам — оружие "
                + chance(config, ItemKind.SWORD)
                + ", броня " + chance(config, ItemKind.CHESTPLATE)
                + ", инструменты " + chance(config, ItemKind.PICKAXE)
                + ", босс ×" + trim(config.mobBossMultiplier())
                + (config.mobPlayerKillOnly() ? ", только убийство игроком" : "")
                : "выключен"));
        out.add("настройки: проходка +" + config.passBonusCraftPercent()
                + "% к крафту, клички " + onOff(config.namesEnabled())
                + ", копий stats.yml — " + config.statsBackupCopies());
        return out;
    }

    /** Шанс группы: свой процент или «общий» (фолбэк `-1`). */
    private static String chance(TierForgeConfig config, ItemKind kind) {
        int value = config.mobDropChanceFor(kind);
        if (value <= 0) {
            return "выкл.";
        }
        return value + "%";
    }

    private static String onOff(boolean value) {
        return value ? "вкл." : "выкл.";
    }

    /** Целое без лишних нулей: 2.0 → «2», 2.5 → «2.5». */
    static String trim(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
