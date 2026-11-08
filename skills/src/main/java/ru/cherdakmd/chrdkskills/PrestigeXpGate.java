package ru.cherdakmd.chrdkskills;

import java.util.Collection;
import java.util.Map;

/**
 * Баланс прокачки престижа: опыт навыку не идёт, пока он оторвался по ступеням
 * от самого отсталого активного навыка.
 *
 * <p>Майнинг объективно дешевле остальных (частые события, 3 XP за блок),
 * поэтому игроки сбрасывали только его и уходили вперёд по престижу, не качая
 * остальные девять навыков. С 1.5.2 ступень любого навыка может опережать
 * минимальную ступень среди <em>включённых</em> навыков не более чем на
 * {@code max-lead}. Правило общее, а не «только для майнинга»: иначе дешёвый
 * способ просто переехал бы на рыбалку или лесорубку.</p>
 *
 * <p>Проверяется до начисления XP в {@link SkillService#award}. Ступени не
 * отнимаются, опыт не теряется молча: игрок видит уведомление с кулдауном и
 * может выровнять баланс, прокачав отстающие навыки до 100 и сбросив их.
 * Админская выдача опыта ({@code grantXp}, {@code ChrdkSkillsApi.addXp})
 * намеренно остаётся вне правила.</p>
 *
 * <p>Класс чистый, без Bukkit и состояния — проверяется юнит-тестами.</p>
 */
public final class PrestigeXpGate {

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

    /** Можно ли сейчас начислять опыт этому навыку. */
    public static boolean allows(Map<SkillType, Integer> prestige,
                                 Collection<SkillType> tracked, SkillType skill, int maxLead) {
        if (skill == null) {
            return true;
        }
        int allowed = clamp(maxLead);
        return lead(prestige, tracked, skill) <= allowed;
    }

    /** Допустимый отрыв из конфига: 0..MAX_PER_SKILL, никогда не отрицательный. */
    public static int clampLead(int raw) {
        return clamp(raw);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(PrestigeMath.MAX_PER_SKILL, value));
    }
}
