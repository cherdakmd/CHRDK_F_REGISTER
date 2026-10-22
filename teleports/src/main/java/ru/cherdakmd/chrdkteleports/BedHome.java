package ru.cherdakmd.chrdkteleports;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Bed;

import java.util.Set;

/**
 * Кровать как точка дома и точка возрождения.
 *
 * <p>Ванильная кровать помнит точку спавна, но эта память живёт ровно до того
 * момента, когда кровать снесли: после этого смерть отправляет игрока на спавн
 * мира. Модуль запоминает кровать в {@code teleports-data.json} под именем
 * {@code bed}, поэтому <b>кровать остаётся домом</b>, даже если её сломали, а
 * {@code /home bed} работает так же, как любая другая точка дома.</p>
 *
 * <p>Точка считается по <b>ножной</b> половине кровати: клик по изголовью
 * нормализуется в ножку, иначе после перестановки кровати на один блок в
 * сторону дом «уезжал» бы на половину кровати. Высота — на блок выше кровати:
 * там, где игрок реально стоит, когда встаёт с постели.</p>
 */
public final class BedHome {

    /**
     * Где стоять рядом с кроватью. Порядок важен: сначала сама кровать, потом
     * соседние клетки на том же уровне, и только затем уровень выше и ниже —
     * так игрок почти всегда появляется ровно на кровати, а не в углу.
     */
    private static final int[][] OFFSETS = {
            {0, 0, 0},
            {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
            {1, 0, 1}, {1, 0, -1}, {-1, 0, 1}, {-1, 0, -1},
            {0, 1, 0}, {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1},
            {0, -1, 0}
    };

    /** На такой земле стоять нельзя: возрождение туда — это подарочная смерть. */
    private static final Set<Material> DANGEROUS = Set.of(
            Material.LAVA, Material.MAGMA_BLOCK, Material.CACTUS, Material.FIRE,
            Material.SOUL_FIRE, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.POINTED_DRIPSTONE,
            Material.WITHER_ROSE, Material.LAVA_CAULDRON);

    private BedHome() {
    }

    /** Точка дома на кровати: центр блока и один блок вверх. */
    public static HomeLocation at(String world, int x, int y, int z, float yaw) {
        return new HomeLocation(HomeStore.BED, world,
                x + 0.5, y + 1.0, z + 0.5, yaw, 0.0f);
    }

    /** Точка дома по блоку кровати; клик по изголовью нормализуется в ножку. */
    public static HomeLocation at(Block bed, float yaw) {
        if (bed == null) {
            return null;
        }
        Block foot = foot(bed);
        return at(foot.getWorld().getName(), foot.getX(), foot.getY(), foot.getZ(), yaw);
    }

    /** Ножная половина кровати: именно её ванильный сервер считает точкой спавна. */
    public static Block foot(Block bed) {
        if (bed.getBlockData() instanceof Bed data && data.getPart() == Bed.Part.HEAD) {
            return bed.getRelative(data.getFacing().getOppositeFace());
        }
        return bed;
    }

    /** Является ли запись кроватью-домом. */
    public static boolean isBed(HomeLocation home) {
        return home != null && HomeStore.isBedName(home.name());
    }

    /**
     * Точка для телепорта и возрождения.
     *
     * <p>Если кровать на месте — возвращается она сама. Если кровать сломали или
     * застроили, ищется ближайшее свободное место в пределах одного блока;
     * совсем ничего не нашлось — возвращается запомненная точка (дом должен
     * работать даже в разрушенной постройке).</p>
     */
    public static Location safeSpot(World world, HomeLocation home) {
        if (world == null || home == null) {
            return null;
        }
        Location base = new Location(world, home.x(), home.y(), home.z(),
                home.yaw(), home.pitch());
        int baseX = base.getBlockX();
        int baseY = base.getBlockY();
        int baseZ = base.getBlockZ();
        for (int[] offset : OFFSETS) {
            Block candidate = world.getBlockAt(baseX + offset[0], baseY + offset[1], baseZ + offset[2]);
            if (standable(candidate)) {
                return new Location(world, candidate.getX() + 0.5, candidate.getY(),
                        candidate.getZ() + 0.5, home.yaw(), home.pitch());
            }
        }
        return base;
    }

    /** Можно ли поставить игрока в этот блок: ноги и голова свободны, опора твёрдая. */
    static boolean standable(Block feet) {
        if (feet == null) {
            return false;
        }
        Material ground = feet.getRelative(BlockFace.DOWN).getType();
        if (ground.isAir() || ground.isLiquid() || DANGEROUS.contains(ground)) {
            return false;
        }
        Material at = feet.getType();
        Material head = feet.getRelative(BlockFace.UP).getType();
        if (at.isSolid() || head.isSolid() || at.isLiquid() || head.isLiquid()) {
            return false;
        }
        return !DANGEROUS.contains(at);
    }
}
