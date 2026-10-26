package ru.cherdakmd.chrdkevents;

import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Нашествие мобов «Кровавой луны» (1.4.176): множитель, волны и потолки.
 *
 * <p>Главная проверка — арифметика эха: при множителе 2.5 на один естественный
 * спавн приходится в среднем полтора лишних моба, а не два и не ноль. Именно
 * это игрок ощущает как «мобов стало больше».</p>
 */
class HordeSpawnTest {

    private static final List<EntityType> MOBS = List.of(EntityType.ZOMBIE, EntityType.SKELETON);

    private static HordeSpawn horde(boolean enabled, double multiplier, int interval, int size,
                                    int maxNear, int minDistance, int maxDistance,
                                    boolean onlyAtNight, List<EntityType> mobs) {
        return new HordeSpawn(enabled, multiplier, interval, size, maxNear, minDistance,
                maxDistance, onlyAtNight, mobs);
    }

    @Test
    void nothingWithoutMobs() {
        assertFalse(HordeSpawn.NONE.usable());
        assertFalse(horde(true, 2.0, 40, 4, 32, 10, 20, false, List.of()).usable(),
                "без списка мобов нашествию некого спавнить");
        assertFalse(horde(false, 2.0, 40, 4, 32, 10, 20, false, MOBS).usable());
        assertTrue(horde(true, 2.0, 40, 4, 32, 10, 20, false, MOBS).usable());
        assertNull(HordeSpawn.NONE.mob(new Random(1)));
    }

    @Test
    void numbersAreClamped() {
        HordeSpawn low = horde(true, 0.1, 1, 99, 9_999, 0, 0, false, MOBS);
        assertEquals(HordeSpawn.MIN_MULTIPLIER, low.multiplier(), 0.0001);
        assertEquals(HordeSpawn.MIN_WAVE_INTERVAL_SECONDS, low.waveIntervalSeconds());
        assertEquals(HordeSpawn.MAX_WAVE_SIZE, low.waveSize());
        assertEquals(HordeSpawn.MAX_NEAR_PLAYER_LIMIT, low.maxNearPlayer());
        assertEquals(HordeSpawn.MIN_DISTANCE_FLOOR, low.minDistance());
        assertEquals(HordeSpawn.MIN_DISTANCE_FLOOR, low.maxDistance(),
                "максимум не может быть меньше минимума");

        HordeSpawn high = horde(true, 99.0, 9_999, 1, 1, 999, 999, false, MOBS);
        assertEquals(HordeSpawn.MAX_MULTIPLIER, high.multiplier(), 0.0001);
        assertEquals(HordeSpawn.MAX_WAVE_INTERVAL_SECONDS, high.waveIntervalSeconds());
        assertEquals(HordeSpawn.MAX_DISTANCE_CEILING, high.minDistance());
        assertEquals(HordeSpawn.MAX_DISTANCE_CEILING, high.maxDistance());
        assertEquals(1, horde(true, 2.0, 40, 0, 32, 10, 20, false, MOBS).waveSize());
    }

    @Test
    void echoAddsOnAverageMultiplierMinusOne() {
        Random random = new Random(11);
        HordeSpawn spawn = horde(true, 2.5, 40, 4, 32, 10, 20, false, MOBS);
        int total = 0;
        int draws = 10_000;
        for (int index = 0; index < draws; index++) {
            int extra = spawn.extraSpawns(random);
            assertTrue(extra >= 0 && extra <= HordeSpawn.MAX_EXTRA_PER_SPAWN,
                    "лишних мобов слишком много: " + extra);
            total += extra;
        }
        double average = total / (double) draws;
        assertTrue(Math.abs(average - 1.5) < 0.06,
                "среднее эхо должно быть 1.5, а не " + average);
    }

    @Test
    void wholeMultiplierAlwaysAdds() {
        Random random = new Random(3);
        HordeSpawn spawn = horde(true, 3.0, 40, 4, 32, 10, 20, false, MOBS);
        for (int index = 0; index < 100; index++) {
            assertEquals(HordeSpawn.MAX_EXTRA_PER_SPAWN, spawn.extraSpawns(random),
                    "при множителе 3.0 лишних всегда двое");
        }
        HordeSpawn none = horde(true, 1.0, 40, 4, 32, 10, 20, false, MOBS);
        for (int index = 0; index < 100; index++) {
            assertEquals(0, none.extraSpawns(random), "множитель 1.0 ничего не добавляет");
        }
    }

    @Test
    void nightGateOnlyTouchesNormalWorlds() {
        HordeSpawn nightOnly = horde(true, 2.0, 40, 4, 32, 10, 20, true, MOBS);
        assertFalse(nightOnly.allowSpawnNow(true, true), "день в обычном мире — тихо");
        assertTrue(nightOnly.allowSpawnNow(true, false), "ночь — нашествие");
        assertTrue(nightOnly.allowSpawnNow(false, true),
                "в Нижнем и Энде ночи нет — ограничение не действует");

        HordeSpawn always = horde(true, 2.0, 40, 4, 32, 10, 20, false, MOBS);
        assertTrue(always.allowSpawnNow(true, true));
        assertTrue(always.allowSpawnNow(true, false));
    }

    @Test
    void duplicateMobsAreDropped() {
        HordeSpawn spawn = horde(true, 2.0, 40, 4, 32, 10, 20, false,
                List.of(EntityType.ZOMBIE, EntityType.ZOMBIE, EntityType.SPIDER));
        assertEquals(List.of(EntityType.ZOMBIE, EntityType.SPIDER), spawn.mobs());
    }
}
