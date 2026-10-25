package ru.cherdakmd.chrdkbosses;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Чистая математика боя (1.4.162): фазы, вклад участников, урон зоны.
 *
 * <p>Здесь нет ни Bukkit, ни игрока — только числа. Поэтому «на 29 % началась
 * третья фаза», «кто получил сколько опыта» и «зона не отнимает последние
 * сердца» проверяются тестами, а не на живом сервере.</p>
 */
final class BossMath {

    private BossMath() {
    }

    /**
     * Индекс фазы для текущего здоровья.
     *
     * <p>Фазы идут по убыванию порога: {@code 100, 60, 30}. Пока здоровье выше
     * 60 %, действует первая; на 60 % включается вторая и так далее.</p>
     */
    static int phaseIndex(List<BossPhase> phases, double health, double maxHealth) {
        if (phases == null || phases.isEmpty() || maxHealth <= 0) {
            // Без максимума здоровье нельзя перевести в проценты: считаем, что
            // бой ещё в первой фазе, а не в последней.
            return 0;
        }
        double percent = Math.clamp(health / maxHealth * 100.0, 0.0, 100.0);
        int index = 0;
        for (int cursor = 0; cursor < phases.size(); cursor++) {
            if (phases.get(cursor).fromPercent() >= percent) {
                index = cursor;
            }
        }
        return index;
    }

    /** Процент здоровья 0..100. */
    static double healthPercent(double health, double maxHealth) {
        if (maxHealth <= 0) {
            return 0.0;
        }
        return Math.clamp(health / maxHealth * 100.0, 0.0, 100.0);
    }

    /**
     * Доли урона каждого участника.
     *
     * <p>Отрицательный и нулевой урон отбрасывается: «постоял рядом» — не вклад.
     * Сумма долей нормализуется в 1.0, поэтому босс, добитый одним ударом,
     * отдаёт всю награду одному.</p>
     */
    static Map<UUID, Double> shares(Map<UUID, Double> damage) {
        Map<UUID, Double> out = new LinkedHashMap<>();
        if (damage == null || damage.isEmpty()) {
            return out;
        }
        double total = 0.0;
        for (Double value : damage.values()) {
            if (value != null && value > 0) {
                total += value;
            }
        }
        if (total <= 0) {
            return out;
        }
        for (Map.Entry<UUID, Double> entry : damage.entrySet()) {
            Double value = entry.getValue();
            if (entry.getKey() == null || value == null || value <= 0) {
                continue;
            }
            out.put(entry.getKey(), value / total);
        }
        return out;
    }

    /**
     * Опыт участнику по его доле.
     *
     * <p>Минимум одно очко тому, кто действительно бил: обидно получить ноль
     * после боя. Общий потолок держит награду в рамках {@link BossReward#MAX_XP}.
     */
    static int xpShare(int totalXp, double share) {
        if (totalXp <= 0 || share <= 0) {
            return 0;
        }
        long value = Math.round(totalXp * Math.clamp(share, 0.0, 1.0));
        return (int) Math.clamp(Math.max(1L, value), 0L, BossReward.MAX_XP);
    }

    /**
     * Урон зоны за тик: не отнимает последние два сердца.
     *
     * <p>Зона должна выматывать и заставлять уходить, а не убивать игрока,
     * который зазевался. Смертельный удар — всегда дело мобов и боссов.</p>
     */
    static double zoneDamage(double damagePerSecond, double currentHealth) {
        if (damagePerSecond <= 0 || currentHealth <= 0) {
            return 0.0;
        }
        double allowed = Math.max(0.0, currentHealth - 2.0);
        return Math.min(damagePerSecond, allowed);
    }

    /** Самая опасная зона из подходящих: выше уровень — важнее сообщение. */
    static DangerZone zoneAt(List<DangerZone> zones, String world, double x, double y, double z) {
        if (zones == null) {
            return null;
        }
        DangerZone best = null;
        for (DangerZone zone : zones) {
            if (zone == null || !zone.contains(world, x, y, z)) {
                continue;
            }
            if (best == null || zone.level() > best.level()) {
                best = zone;
            }
        }
        return best;
    }

    /** Время в виде «5 мин 12 с» — для меню, команд и плейсхолдеров. */
    static String clock(long seconds) {
        long safe = Math.max(0L, seconds);
        long minutes = safe / 60;
        long rest = safe % 60;
        return minutes > 0 ? minutes + " мин " + rest + " с" : rest + " с";
    }

    /** Надбавка к опыту моба: процент зоны, но не больше базового опыта. */
    static int bonusXp(int baseXp, int percent) {
        if (baseXp <= 0 || percent <= 0) {
            return 0;
        }
        long value = Math.round(baseXp * (Math.clamp(percent, 0, DangerZone.MAX_XP_BONUS_PERCENT)
                / 100.0));
        return (int) Math.clamp(value, 0L, 10_000L);
    }
}
