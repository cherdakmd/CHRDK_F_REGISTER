package ru.cherdakmd.chrdkartifacts;

import org.bukkit.potion.PotionEffectType;

import java.util.Set;

/**
 * Резолвер строковых имён эффектов из {@link ArtifactEffects} в константы API 1.21.
 * Через строки — чтобы {@link ArtifactEffects} оставался чистым и тестируемым.
 */
final class Potions {

    /**
     * Имена, которые понимает {@link #resolve}. Держим списком отдельно от switch,
     * чтобы юнит-тесты могли проверять таблицу эффектов без загрузки реестра
     * {@link PotionEffectType} (в 1.21 он требует запущенного сервера).
     */
    private static final Set<String> KNOWN = Set.of(
            "speed", "slowness", "haste", "mining_fatigue", "strength", "jump_boost",
            "nausea", "regeneration", "resistance", "fire_resistance", "water_breathing",
            "blindness", "night_vision", "hunger", "weakness", "poison", "health_boost",
            "absorption", "glowing", "luck", "slow_falling", "dolphins_grace", "darkness",
            "saturation", "unluck");

    private Potions() {
    }

    /** Знает ли модуль такое имя эффекта (проверка без Bukkit-реестра). */
    static boolean isKnown(String name) {
        return name != null && KNOWN.contains(name);
    }

    static PotionEffectType resolve(String name) {
        if (!isKnown(name)) {
            return null;
        }
        return switch (name) {
            case "speed" -> PotionEffectType.SPEED;
            case "slowness" -> PotionEffectType.SLOWNESS;
            case "haste" -> PotionEffectType.HASTE;
            case "mining_fatigue" -> PotionEffectType.MINING_FATIGUE;
            case "strength" -> PotionEffectType.STRENGTH;
            case "jump_boost" -> PotionEffectType.JUMP_BOOST;
            case "nausea" -> PotionEffectType.NAUSEA;
            case "regeneration" -> PotionEffectType.REGENERATION;
            case "resistance" -> PotionEffectType.RESISTANCE;
            case "fire_resistance" -> PotionEffectType.FIRE_RESISTANCE;
            case "water_breathing" -> PotionEffectType.WATER_BREATHING;
            case "blindness" -> PotionEffectType.BLINDNESS;
            case "night_vision" -> PotionEffectType.NIGHT_VISION;
            case "hunger" -> PotionEffectType.HUNGER;
            case "weakness" -> PotionEffectType.WEAKNESS;
            case "poison" -> PotionEffectType.POISON;
            case "health_boost" -> PotionEffectType.HEALTH_BOOST;
            case "absorption" -> PotionEffectType.ABSORPTION;
            case "glowing" -> PotionEffectType.GLOWING;
            case "luck" -> PotionEffectType.LUCK;
            case "slow_falling" -> PotionEffectType.SLOW_FALLING;
            case "dolphins_grace" -> PotionEffectType.DOLPHINS_GRACE;
            case "darkness" -> PotionEffectType.DARKNESS;
            case "saturation" -> PotionEffectType.SATURATION;
            case "unluck" -> PotionEffectType.UNLUCK;
            default -> null;
        };
    }
}
