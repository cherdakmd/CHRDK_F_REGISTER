package ru.cherdakmd.chrdkartifacts;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        public enum Kind {
            IGNITE,        // поджечь носителя
            POISON,        // отравить носителя
            BLEED,         // кровопускание: потеря половины сердца
            WATER_HURT,    // урон, пока носитель в воде
            BLINDNESS_PROC,// внезапная слепота
            XP_DRAIN,      // высасывание опыта
            WITHER_PROC    // иссушающее прикосновение
        }
    }

    /** Пассивная суперспособность (не зелье): обрабатывается событием или пульсом. */
    public enum SpecialKind {
        PHOENIX_REVIVE,   // смертельный урон → воскрешение на половине HP (кулдаун)
        SOUL_THIEF,       // убийство моба возвращает здоровье
        FROST_AURA,       // замедляет мобов вокруг носителя (пульс)
        THORNS_RETALIATE, // нападавший по игроку получает ответный шок
        XP_MAGNET,        // опытные шары вокруг летят к носителю (пульс)
        JUMP_WARD         // «Глиняная душа»: прыжок гасится атрибутом jump_strength
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
        SOUL_COUPON,      // уровни опыта в обмен на урон и слабость
        // --- добрая волна (1.4.58, по мотивам CHRDK_REBORN) ---
        CLEANSE_CURSE,    // свиток: снять с себя все злые эффекты и огонь
        BLOOD_TOTEM,      // тотем крови: щит поглощения + сопротивление на минуту
        HOME_TELEPORT,    // сфера побега: телепорт к точке воскрешения
        FROST_WAVE,       // ледяная стена: мобов вокруг сковывает и морозит
        REVEAL_BOSSES,    // глаз начала: боссы в большом радиусе светятся
        ABYSS_POWER,      // корона бездны: Сила II на полминуты с дурманом вкупе
        ENDER_SHIFT,      // эндер-сдвиг: телепорт по взгляду, даже сквозь воздух
        ETERNITY_HOUR,    // осколок вечности: запас здоровья и поглощения на полторы минуты
        // --- тёмная волна (1.4.58) ---
        CURSE_TOME,       // книга проклятий: слепота и вязкость игрокам вокруг
        BLOOD_DONATE,     // жертвенный кубок: обменять щеку здоровья на щит и реген
        MOON_SHATTER,     // расколотая луна: полночь для мира, свечение для чужих
        LAZY_HAT,         // шляпа бездельника: медленное парение и тяжесть рук
        SOUL_SNARE,       // силок души: мобов вокруг подбрасывает и слабит
        FOG_BOMB,         // облака дурмана: чужих мутит, себя зрячит бегать
        VOID_KEY,         // ключ пропасти: телепорт в пещерную пустоту под ногами
        VERMIN_CALL       // свист гадов: пещерные пауки сбегаются на зов
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
            // --- добрая волна (1.4.58) ---
            Map.entry("travjanoj-eliksir", List.of(new PotionSpec("regeneration", 0))),
            Map.entry("sapogi-vetra", List.of(new PotionSpec("jump_boost", 0))),
            Map.entry("klever-udachi", List.of(new PotionSpec("luck", 0))),
            Map.entry("serdtse-drakona", List.of(new PotionSpec("health_boost", 4))),
            Map.entry("travyanoj-meshochek", List.of(new PotionSpec("saturation", 0))),
            // --- тёмная волна (1.4.58) ---
            Map.entry("okov-tyazhesti", List.of(new PotionSpec("slowness", 1))),
            Map.entry("pustoy-zhivot", List.of(new PotionSpec("hunger", 1))),
            Map.entry("slabye-kisti", List.of(new PotionSpec("weakness", 0))),
            Map.entry("prah-marka", List.of(new PotionSpec("unluck", 0)))
    );

    private static final Map<String, TriggerSpec> PASSIVE_TRIGGERS = Map.ofEntries(
            Map.entry("pepel-pogroma", new TriggerSpec(TriggerSpec.Kind.IGNITE, 0.08)),
            Map.entry("sushonyj-glaz", new TriggerSpec(TriggerSpec.Kind.POISON, 0.10)),
            // --- тёмная волна (1.4.58) ---
            Map.entry("krovavyj-svyaz", new TriggerSpec(TriggerSpec.Kind.BLEED, 0.05)),
            Map.entry("smertnyj-potok", new TriggerSpec(TriggerSpec.Kind.WATER_HURT, 0.08)),
            Map.entry("chernye-slezy", new TriggerSpec(TriggerSpec.Kind.BLINDNESS_PROC, 0.06)),
            Map.entry("ruga-zhadnosti", new TriggerSpec(TriggerSpec.Kind.XP_DRAIN, 0.15)),
            Map.entry("koltso-usmerti", new TriggerSpec(TriggerSpec.Kind.WITHER_PROC, 0.04))
    );

    /** Пассивные суперспособности: добрая волна 1.4.58 + атрибутный лимит прыжка. */
    private static final Map<String, SpecialKind> PASSIVE_SPECIALS = Map.of(
            "pero-feniksa", SpecialKind.PHOENIX_REVIVE,
            "zhnej-dush", SpecialKind.SOUL_THIEF,
            "ledyanaya-aura", SpecialKind.FROST_AURA,
            "ternistyj-plasch", SpecialKind.THORNS_RETALIATE,
            "magnit-opyta", SpecialKind.XP_MAGNET,
            "glinyanaya-dusha", SpecialKind.JUMP_WARD
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
            Map.entry("porvannyj-kupon", ActiveKind.SOUL_COUPON),
            // --- добрая волна (1.4.58) ---
            Map.entry("svitok-ochischeniya", ActiveKind.CLEANSE_CURSE),
            Map.entry("totem-krovi", ActiveKind.BLOOD_TOTEM),
            Map.entry("sfera-pobega", ActiveKind.HOME_TELEPORT),
            Map.entry("ledyanaya-stena", ActiveKind.FROST_WAVE),
            Map.entry("glaz-nachala", ActiveKind.REVEAL_BOSSES),
            Map.entry("korona-bezdny", ActiveKind.ABYSS_POWER),
            Map.entry("ender-sdvig", ActiveKind.ENDER_SHIFT),
            Map.entry("oskolok-vechnosti", ActiveKind.ETERNITY_HOUR),
            // --- тёмная волна (1.4.58) ---
            Map.entry("kniga-proklyatiy", ActiveKind.CURSE_TOME),
            Map.entry("zhertvennyj-kubok", ActiveKind.BLOOD_DONATE),
            Map.entry("raskolotaya-luna", ActiveKind.MOON_SHATTER),
            Map.entry("shlyapa-bezdelnika", ActiveKind.LAZY_HAT),
            Map.entry("silok-dushi", ActiveKind.SOUL_SNARE),
            Map.entry("oblaka-durmana", ActiveKind.FOG_BOMB),
            Map.entry("klyuch-propasti", ActiveKind.VOID_KEY),
            Map.entry("svist-gadov", ActiveKind.VERMIN_CALL)
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

    /** Пассивная суперспособность артефакта (null — обычный зельевый/триггерный). */
    public static SpecialKind passiveSpecial(String id) {
        return PASSIVE_SPECIALS.get(id);
    }

    /**
     * Все иды, для которых объявлен хотя бы один эффект. Нужен проверке
     * целостности (аудит A-20): таблицы не должны ссылаться на артефакты,
     * которых нет в каталоге, и наоборот.
     */
    public static Set<String> effectIds() {
        Set<String> ids = new LinkedHashSet<>();
        ids.addAll(PASSIVE_POTIONS.keySet());
        ids.addAll(PASSIVE_TRIGGERS.keySet());
        ids.addAll(PASSIVE_SPECIALS.keySet());
        ids.addAll(ACTIVE_KINDS.keySet());
        return Collections.unmodifiableSet(ids);
    }

    /**
     * Целостность: у каждого пассива — зелья, триггер или суперспособность; у
     * активного — скрипт. «Глиняная душа» держится только суперспособностью
     * JUMP_WARD (атрибут), зельевой записи у неё больше нет.
     */
    public static boolean isComplete(ArtifactDef def) {
        if (def.mode() == ArtifactDef.Mode.PASSIVE) {
            return PASSIVE_POTIONS.containsKey(def.id()) || PASSIVE_TRIGGERS.containsKey(def.id())
                    || PASSIVE_SPECIALS.containsKey(def.id());
        }
        return ACTIVE_KINDS.containsKey(def.id());
    }
}
