package ru.cherdakmd.chrdkevents;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Падающие звёзды (1.4.176): потолки, пригодность и добыча.
 *
 * <p>Проверки держат два обещания: конфиг с опечаткой не устроит звездопад из
 * тысячи звёзд, а звезда всегда роняет хоть что-то — иначе игрок увидит
 * вспышку и пустоту.</p>
 */
class MeteorSpawnTest {

    private static MeteorSpawn spawn(boolean enabled, int interval, int radius, int active,
                                     List<Material> blocks, List<Material> loot,
                                     int minLoot, int maxLoot) {
        return new MeteorSpawn(enabled, interval, radius, active, blocks, loot, minLoot, maxLoot,
                true);
    }

    private static final List<Material> BLOCKS = List.of(Material.AMETHYST_BLOCK, Material.GLOWSTONE);
    private static final List<Material> LOOT = List.of(Material.AMETHYST_SHARD, Material.GLOWSTONE_DUST);

    @Test
    void nothingWithoutSection() {
        assertFalse(MeteorSpawn.NONE.usable());
        assertTrue(MeteorSpawn.NONE.blocks().isEmpty());
        assertFalse(spawn(true, 20, 24, 2, List.of(), LOOT, 1, 2).usable(),
                "без блоков звезда не из чего");
        assertFalse(spawn(true, 20, 24, 2, BLOCKS, List.of(), 1, 2).usable(),
                "без добычи звезда бессмысленна");
        assertFalse(spawn(false, 20, 24, 2, BLOCKS, LOOT, 1, 2).usable(),
                "выключенная звезда не падает");
        assertTrue(spawn(true, 20, 24, 2, BLOCKS, LOOT, 1, 2).usable());
    }

    @Test
    void numbersAreClamped() {
        MeteorSpawn spawn = spawn(true, 1, 1_000, 99, BLOCKS, LOOT, 9, 1);
        assertEquals(MeteorSpawn.MIN_INTERVAL_SECONDS, spawn.intervalSeconds());
        assertEquals(MeteorSpawn.MAX_RADIUS, spawn.radius());
        assertEquals(MeteorSpawn.MAX_ACTIVE, spawn.maxActive());
        assertEquals(MeteorSpawn.MAX_LOOT_PER_STAR, spawn.minLoot());
        assertEquals(MeteorSpawn.MAX_LOOT_PER_STAR, spawn.maxLoot(),
                "максимум не может быть меньше минимума");
        assertEquals(MeteorSpawn.MAX_INTERVAL_SECONDS,
                spawn(true, 10_000, 24, 2, BLOCKS, LOOT, 1, 2).intervalSeconds());
        assertEquals(MeteorSpawn.MIN_RADIUS,
                spawn(true, 20, 0, 2, BLOCKS, LOOT, 1, 2).radius());
        assertEquals(1, spawn(true, 20, 24, 0, BLOCKS, LOOT, 1, 2).maxActive());
    }

    @Test
    void airAndDuplicatesAreDropped() {
        MeteorSpawn spawn = spawn(true, 20, 24, 2,
                List.of(Material.AIR, Material.GLOWSTONE, Material.GLOWSTONE),
                List.of(Material.CAVE_AIR, Material.AMETHYST_SHARD), 1, 2);
        assertEquals(List.of(Material.GLOWSTONE), spawn.blocks());
        assertEquals(List.of(Material.AMETHYST_SHARD), spawn.loot());
    }

    @Test
    void lootAmountStaysInRange() {
        Random random = new Random(42);
        MeteorSpawn one = spawn(true, 20, 24, 2, BLOCKS, LOOT, 1, 1);
        MeteorSpawn three = spawn(true, 20, 24, 2, BLOCKS, LOOT, 1, 3);
        for (int index = 0; index < 200; index++) {
            assertEquals(1, one.lootAmount(random));
            int amount = three.lootAmount(random);
            assertTrue(amount >= 1 && amount <= 3, "добыча вне диапазона: " + amount);
        }
    }

    @Test
    void picksOnlyFromConfiguredLists() {
        MeteorSpawn spawn = spawn(true, 20, 24, 2, BLOCKS, LOOT, 1, 2);
        Random random = new Random(7);
        for (int index = 0; index < 100; index++) {
            assertTrue(BLOCKS.contains(spawn.block(random)));
            assertTrue(LOOT.contains(spawn.lootItem(random)));
        }
    }

    @Test
    void craterIsOffByDefaultAndClamped() {
        assertFalse(spawn(true, 20, 24, 2, BLOCKS, LOOT, 1, 2).crater(),
                "прежние настройки мир не трогают");
        assertFalse(MeteorSpawn.NONE.crater());

        MeteorSpawn crater = new MeteorSpawn(true, 20, 24, 2, BLOCKS, LOOT, 1, 2, true,
                true, 2);
        assertTrue(crater.crater());
        assertEquals(2, crater.craterRadius());

        MeteorSpawn huge = new MeteorSpawn(true, 20, 24, 2, BLOCKS, LOOT, 1, 2, true,
                true, 999);
        assertEquals(MeteorSpawn.MAX_CRATER_RADIUS, huge.craterRadius(), "радиус зажат");

        MeteorSpawn off = new MeteorSpawn(true, 20, 24, 2, BLOCKS, LOOT, 1, 2, true,
                false, 3);
        assertEquals(0, off.craterRadius(), "без break-blocks радиус бессмысленен");
        assertFalse(off.crater());
    }

    @Test
    void announcementsCanBeTurnedOff() {
        MeteorSpawn quiet = new MeteorSpawn(true, 20, 24, 2, BLOCKS, LOOT, 1, 2, false);
        assertFalse(quiet.announce());
        assertTrue(quiet.usable(), "тишина не отменяет саму звезду");
        assertTrue(MeteorSpawn.NONE.announce());
    }
}
