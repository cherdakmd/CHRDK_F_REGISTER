package ru.cherdakmd.chrdkskills;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Баланс прокачки престижа: опыт навыку урезается, пока он оторвался по ступеням
 * от самого отсталого активного навыка.
 *
 * <p>Майнинг объективно дешевле остальных (частые события, 3 XP за блок),
 * поэтому игроки сбрасывали только его и уходили вперёд по престижу, не качая
 * остальные девять навыков. Теперь отрыв от минимальной ступени среди
 * {@code ВКЛЮЧЁННЫХ} навыков даёт не мгновенную стену, а три зоны:</p>
 *
 * <ol>
 *   <li>{@code lead <= allowance} — полный опыт;</li>
 *   <li>{@code allowance < lead < hardCap} — множитель убывает на
 *       {@code softStep} за каждую ступень, но не ниже {@code minFactor};</li>
 *   <li>{@code lead >= hardCap} — опыта нет.</li>
 * </ol>
 *
 * <p>{@code allowance = baseMaxLead + rankMaxLead[rankLevel]}: база одинакова для
 * всех, донатная ступень расширяет только зону без штрафа и не повышает пол.
 * Ранговый {@code +50 %} опыта уже применяется ядром через
 * {@code boostExperience}, поэтому аддитивка его не удваивает. Даже топ-ранг не
 * может забить на остальные навыки навсегда: {@code hardCap} общий для всех.</p>
 *
 * <p>Правило общее для всех десяти навыков: запрет только майнинга переместил бы
 * дешёвую прокачку на рыбалку. Ступени не отнимаются, админская выдача XP
 * ({@code grantXp}, {@code ChrdkSkillsApi.addXp}) намеренно вне правила.</p>
 *
 * <p>Класс чистый, без Bukkit и состояния — проверяется юнит-тестами.</p>
 */
public final class PrestigeXpGate {

    /** Ступени ранга 0..5: без ранга + пять платных/эффективных уровней. */
    public static final int RANK_LEVELS = 6;

    private PrestigeXpGate() {
    }

    /** Ступень навыка, прижатая к 0..MAX_PER_SKILL (отсутствует = 0). */
    public static int step(Map<SkillType, Integer> prestige, SkillType skill) {
        if (skill == null) {
            return 0;
        }
        return clamp(prestige == null ? 0 : prestige.getOrDefault(skill, 0));
    }

    /**
     * Минимальная ступень среди отслеживаемых навыков. Отсутствующие ключи
     * считаются нулём — у нового игрока все навыки ещё не сбрасывали.
     * Выключенные в config.yml навыки в расчёт не входят, иначе один
     * отключённый навык навсегда заблокировал бы все остальные.
     */
    public static int minimum(Map<SkillType, Integer> prestige, Collection<SkillType> tracked) {
        if (tracked == null || tracked.isEmpty()) {
            return 0;
        }
        int min = PrestigeMath.MAX_PER_SKILL;
        for (SkillType skill : tracked) {
            if (skill != null) {
                min = Math.min(min, step(prestige, skill));
            }
        }
        return min;
    }

    /** На сколько ступеней навык оторвался от минимальной. */
    public static int lead(Map<SkillType, Integer> prestige,
                           Collection<SkillType> tracked, SkillType skill) {
        if (skill == null) {
            return 0;
        }
        return Math.max(0, step(prestige, skill) - minimum(prestige, tracked));
    }

    /**
     * Аддитивка разрешённого отрыва за эффективную ступень ранга.
     * Короткий список, отсутствующий индекс и {@code null} дают 0 — кривой
     * конфиг не должен ронять прокачку.
     */
    public static int rankBonus(List<Integer> rankMaxLead, int rankLevel) {
        if (rankMaxLead == null || rankMaxLead.isEmpty()) {
            return 0;
        }
        int index = Math.max(0, Math.min(RANK_LEVELS - 1, rankLevel));
        if (index >= rankMaxLead.size()) {
            return 0;
        }
        Integer value = rankMaxLead.get(index);
        return clamp(value == null ? 0 : value);
    }

    /** Разрешённый отрыв: база + ранг, но никогда выше жёсткого потолка. */
    public static int allowance(int baseMaxLead, List<Integer> rankMaxLead,
                                int rankLevel, int hardCap) {
        int cap = clamp(hardCap);
        if (cap <= 0) {
            return 0;
        }
        return Math.min(cap, clamp(baseMaxLead) + rankBonus(rankMaxLead, rankLevel));
    }

    /**
     * Множитель опыта для одного начисления: 1.0, убывающий или 0.0.
     * Применяется после бустеров ядра и престижа, до клампа одного действия.
     */
    public static double factor(Map<SkillType, Integer> prestige,
                                Collection<SkillType> tracked, SkillType skill,
                                int allowance, int hardCap,
                                double softStep, double minFactor) {
        if (skill == null) {
            return 1.0;
        }
        int cap = clamp(hardCap);
        if (cap <= 0) {
            return 0.0;
        }
        int safeAllowance = Math.min(cap, clamp(allowance));
        int distance = lead(prestige, tracked, skill);
        if (distance >= cap) {
            return 0.0;
        }
        if (distance <= safeAllowance) {
            return 1.0;
        }
        double step = Math.max(0.0, Math.min(1.0, softStep));
        double floor = Math.max(0.0, Math.min(1.0, minFactor));
        double raw = 1.0 - (distance - safeAllowance) * step;
        return Math.max(floor, Math.min(1.0, raw));
    }

    /**
     * Итог одного действия в мягкой зоне: при factor > 0 остаётся хотя бы 1 XP,
     * иначе округление мелких событий (2 XP за блок × 0.25) давало бы ноль.
     */
    public static long scaledXp(long gained, double factor) {
        if (gained <= 0L || factor <= 0.0) {
            return 0L;
        }
        if (factor >= 1.0) {
            return gained;
        }
        return Math.max(1L, Math.round(gained * factor));
    }

    /** Короткий текст множителя для actionbar и {@code /skills balance}. */
    public static String factorText(double factor) {
        if (factor >= 1.0) {
            return "1";
        }
        if (factor <= 0.0) {
            return "0";
        }
        return String.format(Locale.ROOT, "%.2f", factor);
    }

    /**
     * Жёсткая форма правила (полный XP или стоп) для обратной совместимости и
     * контрактных тестов. Рантайм использует {@link #factor}.
     */
    public static boolean allows(Map<SkillType, Integer> prestige,
                                 Collection<SkillType> tracked, SkillType skill, int maxLead) {
        if (skill == null) {
            return true;
        }
        return lead(prestige, tracked, skill) <= clamp(maxLead);
    }

    /** Допустимый отрыв из конфига: 0..MAX_PER_SKILL, никогда не отрицательный. */
    public static int clampLead(int raw) {
        return clamp(raw);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(PrestigeMath.MAX_PER_SKILL, value));
    }
}
