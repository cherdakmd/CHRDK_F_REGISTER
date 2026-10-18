package ru.cherdakmd.chrdkregister;

import org.bukkit.Material;

import java.util.Set;

/**
 * Проверка «безопасная площадка» для случайного спавна: твёрдый пол без лавы, воды,
 * кактусов, магмы и прочего, что ранит или топит, и два свободных блока над ним
 * (ноги и голова). Логика чистая — материалы передаются снаружи, Bukkit-мир не нужен.
 */
public final class SpawnSafety {

    /** Пол, на который нельзя ставить игрока. */
    private static final Set<Material> DANGEROUS_GROUND = Set.of(
            Material.WATER, Material.LAVA, Material.CACTUS, Material.MAGMA_BLOCK,
            Material.POWDER_SNOW, Material.FIRE, Material.SOUL_FIRE,
            Material.LAVA_CAULDRON, Material.SWEET_BERRY_BUSH, Material.POINTED_DRIPSTONE,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE);

    private SpawnSafety() {
    }

    /** Опасный ли пол (или это вообще не пол). */
    public static boolean isDangerousGround(Material ground) {
        return ground == null || ground.isAir() || !ground.isSolid()
                || DANGEROUS_GROUND.contains(ground);
    }

    /**
     * Можно ли ставить игрока в точку.
     *
     * @param ground    блок пола (игрок встанет на него)
     * @param feet      блок на уровне ног
     * @param head      блок на уровне головы
     * @param groundY   Y пола
     * @param minHeight низ мира
     * @param maxHeight верх мира
     */
    public static boolean isSafeSpot(Material ground, Material feet, Material head,
                                     int groundY, int minHeight, int maxHeight) {
        if (ground == null || feet == null || head == null) {
            return false;
        }
        if (groundY <= minHeight || groundY + 2 >= maxHeight) {
            return false;
        }
        if (isDangerousGround(ground)) {
            return false;
        }
        return feet.isAir() && head.isAir();
    }
}
