package ru.cherdakmd.chrdkskills;

import java.util.List;
import java.util.Map;

/**
 * Математика престижа: две шкалы, звания и все потолки баланса.
 *
 * <p><b>Шкала 1</b> — ступени каждого из десяти навыков (0..{@link #MAX_PER_SKILL}).
 * <b>Шкала 2</b> — общий серверный уровень: сумма ступеней (0..{@link #MAX_TOTAL}),
 * он определяет звание и разовые награды. Обе шкалы конечны: бесконечный
 * престиж разогнал бы и опыт, и дроп, и силу ветеранов без предела.</p>
 *
 * <p>Класс без Bukkit и без состояния — проверяется юнит-тестами. Пороги и
 * звания зашиты здесь (как {@code SkillRegistry.THRESHOLDS}), а крутилки
 * множителей приходят из {@code config.yml} и всегда проходят через местные
 * {@code cap}-функции.</p>
 */
public final class PrestigeMath {

    /** Десять навыков, по десять ступеней каждый → 100 — абсолютный потолок сервера. */
    public static final int SKILLS = 10;
    public static final int MAX_PER_SKILL = 10;
    public static final int MAX_TOTAL = SKILLS * MAX_PER_SKILL;

    /** Потолки множителей: конфиг не может их пробить. */
    public static final double MAX_XP_FACTOR = 1.50;
    public static final double MAX_PERCENT_PERK = 0.15;
    public static final double MAX_ABSOLUTE_PERK = 3.0;
    public static final int MAX_STEP_DIAMONDS = 32;
    public static final int MAX_TIER_DIAMONDS = 256;

    /** Звание серверного уровня престижа. */
    public record Tier(int threshold, String id, String title, int diamonds) {
    }

    /** Пороги растут строго; первое звание есть у всех, последнее — потолок. */
    public static final List<Tier> TIERS = List.of(
            new Tier(0, "wanderer", "Странник", 0),
            new Tier(5, "apprentice", "Подмастерье", 8),
            new Tier(15, "veteran", "Ветеран", 16),
            new Tier(30, "master", "Мастер", 32),
            new Tier(60, "legend", "Легенда сервера", 64),
            new Tier(100, "myth", "Миф сервера", 128));

    private PrestigeMath() {
    }

    /** Сумма ступеней по всем навыкам; кривые значения из файла прижимаются. */
    public static int total(Map<SkillType, Integer> perSkill) {
        if (perSkill == null || perSkill.isEmpty()) {
            return 0;
        }
        long sum = 0L;
        for (Integer value : perSkill.values()) {
            sum += clamp(value == null ? 0 : value, 0, MAX_PER_SKILL);
        }
        return (int) Math.min(MAX_TOTAL, sum);
    }

    /** Текущее звание: последний достигнутый порог. */
    public static Tier tierFor(int total) {
        Tier current = TIERS.get(0);
        for (Tier tier : TIERS) {
            if (clamp(total, 0, MAX_TOTAL) >= tier.threshold()) {
                current = tier;
            } else {
                break;
            }
        }
        return current;
    }

    /** Звание по точному порогу (для разовой награды). */
    public static Tier tierAt(int threshold) {
        for (Tier tier : TIERS) {
            if (tier.threshold() == threshold) {
                return tier;
            }
        }
        return null;
    }

    /** Следующее звание или {@code null} на потолке. */
    public static Tier nextTier(int total) {
        int safe = clamp(total, 0, MAX_TOTAL);
        for (Tier tier : TIERS) {
            if (tier.threshold() > safe) {
                return tier;
            }
        }
        return null;
    }

    /** Процент пути до следующего звания (100 на потолке). */
    public static int progressPercent(int total) {
        int safe = clamp(total, 0, MAX_TOTAL);
        Tier current = tierFor(safe);
        Tier next = nextTier(safe);
        if (next == null) {
            return 100;
        }
        int span = next.threshold() - current.threshold();
        return span <= 0 ? 100 : (int) Math.min(100L,
                (long) (safe - current.threshold()) * 100L / span);
    }

    /** Множитель опыта на повторную прокачку: 1.0 → не более 1.5. */
    public static double xpFactor(int skillPrestige, double perStep) {
        double factor = 1.0 + clamp(skillPrestige, 0, MAX_PER_SKILL)
                * Math.max(0.0, perStep);
        return Math.min(MAX_XP_FACTOR, factor);
    }

    /** Какой вид перка усиливает престиж конкретного навыка. */
    public static SkillPerk.Kind perkKind(SkillType skill) {
        if (skill == null) {
            return SkillPerk.Kind.DOUBLE_DROP;
        }
        return switch (skill) {
            case MINING, WOODCUTTING, DIGGING -> SkillPerk.Kind.BREAK_SPEED;
            case FARMING -> SkillPerk.Kind.DOUBLE_DROP;
            case FISHING -> SkillPerk.Kind.EXTRA_CATCH;
            case COMBAT -> SkillPerk.Kind.MELEE_DAMAGE;
            case ARCHERY -> SkillPerk.Kind.ARROW_DAMAGE;
            case DEFENSE -> SkillPerk.Kind.ARMOR;
            case ALCHEMY -> SkillPerk.Kind.EXTRA_POTION;
            case ENCHANTING -> SkillPerk.Kind.ENCHANT_BONUS;
        };
    }

    /**
     * Прибавка к перку за ступени престижа. Абсолютные виды (броня) считаются
     * в своих единицах, поэтому масштабируются отдельно от долей.
     */
    public static double perkValue(SkillType skill, int skillPrestige, double perStep) {
        SkillPerk.Kind kind = perkKind(skill);
        double steps = clamp(skillPrestige, 0, MAX_PER_SKILL);
        double raw = steps * Math.max(0.0, perStep) * (kind.percent() ? 1.0 : 20.0);
        return Math.min(kind.percent() ? MAX_PERCENT_PERK : MAX_ABSOLUTE_PERK, raw);
    }

    /** Алмазы за одну ступень (предметом, не в /bal). */
    public static int stepDiamonds(int configured) {
        return clamp(configured, 0, MAX_STEP_DIAMONDS);
    }

    /** Алмазы за достижение звания. */
    public static int tierDiamonds(Tier tier) {
        return tier == null ? 0 : clamp(tier.diamonds(), 0, MAX_TIER_DIAMONDS);
    }

    /** Все пороги, начиная со второго: по ним отмечаем выданные награды. */
    public static List<Integer> rewardThresholds() {
        return TIERS.stream().skip(1).map(Tier::threshold).toList();
    }

    /** Римская запись ступени для значка: I..X. */
    public static String roman(int value) {
        int safe = clamp(value, 0, MAX_PER_SKILL);
        return switch (safe) {
            case 0 -> "—";
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            default -> "X";
        };
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }
}
