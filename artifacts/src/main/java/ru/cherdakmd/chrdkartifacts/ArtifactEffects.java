package ru.cherdakmd.chrdkartifacts;

import java.util.List;
import java.util.Map;

/**
 * Таблица эффектов артефактов (чистые данные, без привязки к Bukkit-типам, чтобы
 * класс оставался пригодным для юнит-тестов). Скрипты эффектов зашиты в логике
 * {@link ActiveEffectsListener} и {@link PassiveEffectsService}; здесь — только данные:
 * какие зельевые эффекты вешает пассивный артефакт, и какой скрипт исполняет активный.
 */
public final class ArtifactEffects {

    /** Описание зельевого эффекта (строковой тип — резолвится в PotionEffectType в рантайме). */
    public record PotionSpec(String type, int amplifier) {
    }

    /** Случайный триггер проклятого пассива: раз в цикл бросается шанс. */
    public record TriggerSpec(Kind kind, double chancePerCycle) {
        public enum Kind {IGNITE, POISON}
    }

    /** Скрипт активного артефакта. */
    public enum ActiveKind {
        BOLT_STORM,       // 3 молнии в точку взгляда
        DASH,             // рывок-вспышка по взгляду
        FEAST,            // полное насыщение
        FULL_HEAL,        // полный хил + чистка дебаффов
        RANDOM_TELEPORT,  // безопасный случайный телепорт
        REVEAL,           // подсветка мобов вокруг
        ENCHANT_ROLL,     // хартия: накат случайной кастомной чары (зависимость от CHRDK_Enchants)
        DARK_BARGAIN,     // чёрная монета: сила + слабость
        BONE_CALL,        // свист: призвать скелетов на себя
        WILD_TELEPORT,    // дикий телепорт без проверки + слепота
        GRIEF_STORM,      // гроза на мир + молния в себя
        MOROK_STING,      // окружающим мобам медлительность, себе слепота
        NIGHT_CLOCK,      // ночь + тьма
        SOUL_COUPON       // уровни опыта в обмен на урон и слабость
    }

    private static final Map<String, List<PotionSpec>> PASSIVE_POTIONS = Map.ofEntries(
            Map.entry("solnechny-obereg", List.of(new PotionSpec("fire_resistance", 0),
                    new PotionSpec("luck", 0))),
            Map.entry("zhabii-kamen", List.of(new PotionSpec("water_breathing", 0),
                    new PotionSpec("dolphins_grace", 0))),
            Map.entry("dyhanie-gor", List.of(new PotionSpec("slow_falling", 0))),
            Map.entry("nochnoj-svetlyak", List.of(new PotionSpec("night_vision", 0))),
            Map.entry("kamen-stojkosti", List.of(new PotionSpec("resistance", 0))),
            Map.entry("vetryanaya-spitsa", List.of(new PotionSpec("speed", 0))),
            Map.entry("koren-zhizni", List.of(new PotionSpec("health_boost", 0))),
            Map.entry("ruka-rudokopa", List.of(new PotionSpec("haste", 0))),
            Map.entry("sleza-vdovy", List.of(new PotionSpec("slowness", 0))),
            Map.entry("golodnyj-koren", List.of(new PotionSpec("hunger", 0))),
            Map.entry("smolyanoj-mrak", List.of(new PotionSpec("darkness", 0))),
            Map.entry("krivoe-zerkalo", List.of(new PotionSpec("nausea", 0))),
            Map.entry("rzhavyj-gvozd", List.of(new PotionSpec("mining_fatigue", 0))),
            // «Глиняная душа»: прыжок выключен — очень большой усилитель jump_boost.
            Map.entry("glinyanaya-dusha", List.of(new PotionSpec("jump_boost", 250)))
    );

    private static final Map<String, TriggerSpec> PASSIVE_TRIGGERS = Map.of(
            "pepel-pogroma", new TriggerSpec(TriggerSpec.Kind.IGNITE, 0.08),
            "sushonyj-glaz", new TriggerSpec(TriggerSpec.Kind.POISON, 0.10)
    );

    private static final Map<String, ActiveKind> ACTIVE_KINDS = Map.ofEntries(
            Map.entry("grozovoj-kolokol", ActiveKind.BOLT_STORM),
            Map.entry("rog-tajgi", ActiveKind.DASH),
            Map.entry("plamya-ochaga", ActiveKind.FEAST),
            Map.entry("kaplya-zhizni", ActiveKind.FULL_HEAL),
            Map.entry("klyuch-strannika", ActiveKind.RANDOM_TELEPORT),
            Map.entry("linza-providesa", ActiveKind.REVEAL),
            Map.entry("hartiya-char", ActiveKind.ENCHANT_ROLL),
            Map.entry("chernaya-moneta", ActiveKind.DARK_BARGAIN),
            Map.entry("svistok-kostej", ActiveKind.BONE_CALL),
            Map.entry("membrana-prizraka", ActiveKind.WILD_TELEPORT),
            Map.entry("rakovina-toski", ActiveKind.GRIEF_STORM),
            Map.entry("zhalo-moroki", ActiveKind.MOROK_STING),
            Map.entry("chasy-duroshljopa", ActiveKind.NIGHT_CLOCK),
            Map.entry("porvannyj-kupon", ActiveKind.SOUL_COUPON)
    );

    private ArtifactEffects() {
    }

    public static List<PotionSpec> passivePotions(String id) {
        return PASSIVE_POTIONS.getOrDefault(id, List.of());
    }

    public static TriggerSpec passiveTrigger(String id) {
        return PASSIVE_TRIGGERS.get(id);
    }

    public static ActiveKind activeKind(String id) {
        ArtifactDef def = ArtifactRegistry.byId(id);
        return def == null ? null : ACTIVE_KINDS.get(def.id());
    }

    /** Целостность: у каждого пассива — зелья или триггер; у каждого активного — скрипт. */
    public static boolean isComplete(ArtifactDef def) {
        if (def.mode() == ArtifactDef.Mode.PASSIVE) {
            return PASSIVE_POTIONS.containsKey(def.id()) || PASSIVE_TRIGGERS.containsKey(def.id());
        }
        return ACTIVE_KINDS.containsKey(def.id());
    }
}
