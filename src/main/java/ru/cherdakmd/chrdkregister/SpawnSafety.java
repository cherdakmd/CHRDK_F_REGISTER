package ru.cherdakmd.chrdkregister;

import org.bukkit.Material;

import java.util.Set;

/**
 * Проверка «безопасная площадка» для случайного спавна: твёрдый пол без лавы, воды,
 * кактусов, магмы и прочего, что ранит или топит, и два свободных блока над ним
 * (ноги и голова). Логика чистая — материалы передаются снаружи, Bukkit-мир не нужен.
 *
 * <p>Твёрдость пола приходит отдельным флагом: {@code Material#isSolid()} в Paper
 * обращается к {@code Registry} и вне запущенного сервера недоступен, поэтому сервис
 * берёт значение у блока ({@code Block#getType().isSolid()}), а тесты передают его явно.</p>
 */
public final class SpawnSafety {

    /** Воздух во всех вариантах (обычный, пещерный, пустотный). */
    private static final Set<Material> AIR = Set.of(
            Material.AIR, Material.CAVE_AIR, Material.VOID_AIR);

    /** Пол, на который нельзя ставить игрока. */
    private static final Set<Material> DANGEROUS_GROUND = Set.of(
            Material.WATER, Material.LAVA, Material.CACTUS, Material.MAGMA_BLOCK,
            Material.POWDER_SNOW, Material.FIRE, Material.SOUL_FIRE,
            Material.LAVA_CAULDRON, Material.SWEET_BERRY_BUSH, Material.POINTED_DRIPSTONE,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE);

    private SpawnSafety() {
    }

    /** Опасный ли пол: воздух, жидкость, огонь или колючий блок. */
    public static boolean isDangerousGround(Material ground) {
        return ground == null || AIR.contains(ground) || DANGEROUS_GROUND.contains(ground);
    }

    /** Свободен ли блок (воздух любого вида). */
    public static boolean isFree(Material material) {
        return material != null && AIR.contains(material);
    }

    /**
     * Можно ли ставить игрока в точку.
     *
     * @param ground      блок пола (игрок встанет на него)
     * @param groundSolid твёрдый ли пол ({@code Block#getType().isSolid()} на сервере)
     * @param feet        блок на уровне ног
     * @param head        блок на уровне головы
     * @param groundY     Y пола
     * @param minHeight   низ мира
     * @param maxHeight   верх мира
     */
    public static boolean isSafeSpot(Material ground, boolean groundSolid,
                                     Material feet, Material head,
                                     int groundY, int minHeight, int maxHeight) {
        if (groundY <= minHeight || groundY + 2 >= maxHeight) {
            return false;
        }
        if (!groundSolid || isDangerousGround(ground)) {
            return false;
        }
        return isFree(feet) && isFree(head);
    }
}
