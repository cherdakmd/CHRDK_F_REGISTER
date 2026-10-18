package ru.cherdakmd.chrdkregister;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Безопасная площадка: твёрдый пол без лавы/воды/кактусов и два свободных блока сверху. */
class SpawnSafetyTest {

    @Test
    void plainGroundWithAirAboveIsSafe() {
        assertTrue(SpawnSafety.isSafeSpot(Material.GRASS_BLOCK, Material.AIR, Material.AIR,
                64, -64, 320));
        assertTrue(SpawnSafety.isSafeSpot(Material.STONE, Material.AIR, Material.CAVE_AIR,
                0, -64, 320));
    }

    @Test
    void dangerousGroundIsRejected() {
        for (Material ground : new Material[]{Material.WATER, Material.LAVA, Material.CACTUS,
                Material.MAGMA_BLOCK, Material.POWDER_SNOW, Material.FIRE,
                Material.SOUL_FIRE, Material.LAVA_CAULDRON, Material.SWEET_BERRY_BUSH,
                Material.POINTED_DRIPSTONE, Material.CAMPFIRE, Material.SOUL_CAMPFIRE}) {
            assertTrue(SpawnSafety.isDangerousGround(ground), "должен быть опасным: " + ground);
            assertFalse(SpawnSafety.isSafeSpot(ground, Material.AIR, Material.AIR, 64, -64, 320),
                    "нельзя ставить на " + ground);
        }
    }

    @Test
    void airAndNonSolidGroundRejected() {
        assertTrue(SpawnSafety.isDangerousGround(Material.AIR));
        assertTrue(SpawnSafety.isDangerousGround(null));
        assertFalse(SpawnSafety.isSafeSpot(Material.AIR, Material.AIR, Material.AIR, 64, -64, 320));
    }

    @Test
    void blocksAtFeetOrHeadRejected() {
        assertFalse(SpawnSafety.isSafeSpot(Material.GRASS_BLOCK, Material.TALL_GRASS, Material.AIR,
                64, -64, 320));
        assertFalse(SpawnSafety.isSafeSpot(Material.GRASS_BLOCK, Material.AIR, Material.OAK_LEAVES,
                64, -64, 320));
    }

    @Test
    void worldBoundsRespected() {
        assertFalse(SpawnSafety.isSafeSpot(Material.GRASS_BLOCK, Material.AIR, Material.AIR,
                -64, -64, 320), "на дне мира ставить нельзя");
        assertFalse(SpawnSafety.isSafeSpot(Material.GRASS_BLOCK, Material.AIR, Material.AIR,
                318, -64, 320), "у потолка мира ставить нельзя");
        assertTrue(SpawnSafety.isSafeSpot(Material.GRASS_BLOCK, Material.AIR, Material.AIR,
                317, -64, 320));
    }
}
