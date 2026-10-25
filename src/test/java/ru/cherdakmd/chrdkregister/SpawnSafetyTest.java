package ru.cherdakmd.chrdkregister;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Безопасная площадка: твёрдый пол без лавы/воды/кактусов и два свободных блока сверху.
 * Твёрдость пола передаётся флагом — {@code Material#isSolid()} вне сервера недоступен.
 */
class SpawnSafetyTest {

    private static boolean safe(Material ground, boolean solid, Material feet, Material head,
                                int y, int min, int max) {
        return SpawnSafety.isSafeSpot(ground, solid, feet, head, y, min, max);
    }

    @Test
    void plainGroundWithAirAboveIsSafe() {
        assertTrue(safe(Material.GRASS_BLOCK, true, Material.AIR, Material.AIR, 64, -64, 320));
        assertTrue(safe(Material.STONE, true, Material.AIR, Material.CAVE_AIR, 0, -64, 320));
    }

    @Test
    void dangerousGroundIsRejected() {
        for (Material ground : new Material[]{Material.WATER, Material.LAVA, Material.CACTUS,
                Material.MAGMA_BLOCK, Material.POWDER_SNOW, Material.FIRE,
                Material.SOUL_FIRE, Material.LAVA_CAULDRON, Material.SWEET_BERRY_BUSH,
                Material.POINTED_DRIPSTONE, Material.CAMPFIRE, Material.SOUL_CAMPFIRE}) {
            assertTrue(SpawnSafety.isDangerousGround(ground), "должен быть опасным: " + ground);
            assertFalse(safe(ground, false, Material.AIR, Material.AIR, 64, -64, 320),
                    "нельзя ставить на " + ground);
        }
    }

    @Test
    void airAndNonSolidGroundRejected() {
        assertTrue(SpawnSafety.isDangerousGround(Material.AIR));
        assertTrue(SpawnSafety.isDangerousGround(null));
        assertFalse(safe(Material.AIR, false, Material.AIR, Material.AIR, 64, -64, 320));
        assertFalse(safe(Material.GRASS_BLOCK, false, Material.AIR, Material.AIR, 64, -64, 320),
                "нетвёрдый пол не годится");
    }

    @Test
    void blocksAtFeetOrHeadRejected() {
        assertFalse(safe(Material.GRASS_BLOCK, true, Material.TALL_GRASS, Material.AIR,
                64, -64, 320));
        assertFalse(safe(Material.GRASS_BLOCK, true, Material.AIR, Material.OAK_LEAVES,
                64, -64, 320));
        assertTrue(SpawnSafety.isFree(Material.VOID_AIR));
        assertFalse(SpawnSafety.isFree(Material.STONE));
    }

    @Test
    void worldBoundsRespected() {
        assertFalse(safe(Material.GRASS_BLOCK, true, Material.AIR, Material.AIR, -64, -64, 320),
                "на дне мира ставить нельзя");
        assertFalse(safe(Material.GRASS_BLOCK, true, Material.AIR, Material.AIR, 318, -64, 320),
                "у потолка мира ставить нельзя");
        assertTrue(safe(Material.GRASS_BLOCK, true, Material.AIR, Material.AIR, 317, -64, 320));
    }
}
