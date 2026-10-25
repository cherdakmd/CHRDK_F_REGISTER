package ru.cherdakmd.chrdkbosses;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Расчёты боссов (1.4.162): фазы, доли урона, урон зоны и надбавка к опыту.
 *
 * <p>Это чистая математика без сервера — именно её нельзя проверять «на глаз»
 * в бою: ошибка в долях урона отнимает награду у того, кто её заслужил.</p>
 */
class BossMathTest {

    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private static final List<BossPhase> PHASES = List.of(
            new BossPhase(100, "Первая", 1.0, 1.0, List.of()),
            new BossPhase(60, "Вторая", 1.3, 1.1, List.of()),
            new BossPhase(30, "Третья", 1.6, 1.2, List.of()));

    @Test
    void phaseIndexFollowsHealth() {
        assertEquals(0, BossMath.phaseIndex(PHASES, 200, 200));
        assertEquals(1, BossMath.phaseIndex(PHASES, 120, 200));
        assertEquals(1, BossMath.phaseIndex(PHASES, 61, 200), "60 % — уже вторая фаза");
        assertEquals(2, BossMath.phaseIndex(PHASES, 60, 200));
        assertEquals(2, BossMath.phaseIndex(PHASES, 0, 200));
        assertEquals(0, BossMath.phaseIndex(List.of(), 100, 100));
        assertEquals(0, BossMath.phaseIndex(PHASES, 100, 0), "без максимума фаза первая");
    }

    @Test
    void healthPercentIsClamped() {
        assertEquals(50.0, BossMath.healthPercent(100, 200), 1.0e-9);
        assertEquals(100.0, BossMath.healthPercent(300, 200), 1.0e-9);
        assertEquals(0.0, BossMath.healthPercent(-10, 200), 1.0e-9);
        assertEquals(0.0, BossMath.healthPercent(10, 0), 1.0e-9);
    }

    @Test
    void sharesIgnoreNonContributors() {
        Map<UUID, Double> damage = new LinkedHashMap<>();
        damage.put(FIRST, 75.0);
        damage.put(SECOND, 25.0);
        damage.put(UUID.fromString("00000000-0000-0000-0000-0000000000c3"), -5.0);
        Map<UUID, Double> shares = BossMath.shares(damage);
        assertEquals(2, shares.size(), "отрицательный урон — не вклад");
        assertEquals(0.75, shares.get(FIRST), 1.0e-9);
        assertEquals(0.25, shares.get(SECOND), 1.0e-9);
        assertEquals(1.0, shares.values().stream().mapToDouble(Double::doubleValue).sum(),
                1.0e-9);
        assertTrue(BossMath.shares(Map.of()).isEmpty());
        assertTrue(BossMath.shares(null).isEmpty());
    }

    @Test
    void xpShareHasFloorAndCeiling() {
        assertEquals(300, BossMath.xpShare(400, 0.75));
        assertEquals(0, BossMath.xpShare(0, 0.5));
        assertEquals(0, BossMath.xpShare(400, 0.0));
        assertEquals(1, BossMath.xpShare(100, 0.0001), "кто бил — не остаётся с нулём");
        assertEquals(BossReward.MAX_XP, BossMath.xpShare(BossReward.MAX_XP * 2, 1.0));
    }

    @Test
    void zoneNeverTakesTheLastTwoHearts() {
        assertEquals(1.0, BossMath.zoneDamage(1.0, 20.0), 1.0e-9);
        assertEquals(0.0, BossMath.zoneDamage(1.0, 2.0), 1.0e-9);
        assertEquals(0.0, BossMath.zoneDamage(1.0, 1.0), 1.0e-9);
        assertEquals(1.0, BossMath.zoneDamage(4.0, 3.0), 1.0e-9, "остаётся ровно два сердца");
        assertEquals(0.0, BossMath.zoneDamage(0.0, 20.0), 1.0e-9);
        assertEquals(0.0, BossMath.zoneDamage(2.0, 0.0), 1.0e-9);
    }

    @Test
    void zoneAtPicksTheMostDangerous() {
        DangerZone mild = new DangerZone("mild", "Тихая", "", true, "world",
                0, 64, 0, 30, 1, 0.5, 10, 5, List.of(), Material.MAGMA_BLOCK);
        DangerZone harsh = new DangerZone("harsh", "Злая", "", true, "world",
                0, 64, 0, 30, 4, 2.0, 25, 5, List.of(), Material.MAGMA_BLOCK);
        assertEquals("harsh", BossMath.zoneAt(List.of(mild, harsh), "world", 5, 64, 5).id());
        assertNull(BossMath.zoneAt(List.of(mild, harsh), "world", 100, 64, 100));
        assertEquals("mild", BossMath.zoneAt(List.of(mild), "world", 5, 64, 5).id());
        assertNull(BossMath.zoneAt(List.of(mild), "nether", 0, 64, 0));
        assertNull(BossMath.zoneAt(null, "world", 0, 64, 0));
        assertNull(BossMath.zoneAt(List.of(), "world", 0, 64, 0));
    }

    @Test
    void bonusXpIsPercentOfBase() {
        assertEquals(3, BossMath.bonusXp(10, 25));
        assertEquals(5, BossMath.bonusXp(10, 50));
        assertEquals(0, BossMath.bonusXp(10, 0));
        assertEquals(0, BossMath.bonusXp(0, 50));
        assertEquals(50, BossMath.bonusXp(100, 500), "надбавка ограничена потолком зоны");
    }

    @Test
    void clockReadsLikeAHuman() {
        assertEquals("0 с", BossMath.clock(0));
        assertEquals("42 с", BossMath.clock(42));
        assertEquals("1 мин 5 с", BossMath.clock(65));
        assertEquals("0 с", BossMath.clock(-5), "отрицательное время — ноль");
    }
}
