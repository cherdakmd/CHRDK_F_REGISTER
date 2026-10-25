package ru.cherdakmd.chrdkevents;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Форма воронки от упавшей звезды (1.4.176).
 *
 * <p>Звезда бьёт по земле и выбивает небольшую воронку. Правила простые и
 * проверяются тестами: глубина — не больше двух слоёв, ломаются только
 * природные блоки (камень, земля, песок, гравий), а решения «можно ли здесь
 * ломать» принимает сервис — он же спрашивает приваты.</p>
 *
 * <p>Дом, сундук, постройка из досок или стекла остаются целыми: их материалов
 * в списке природных нет. Это осознанно — событие не должно разрушать чужой
 * труд даже вне привата.</p>
 */
final class Crater {

    /** Не больше двух слоёв: воронка, а не карьер. */
    static final int DEPTH = 2;

    /** Природные блоки, которые звезда вправе выбить. */
    private static final Set<Material> NATURAL = Set.of(
            Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT,
            Material.PODZOL, Material.MYCELIUM, Material.MOSS_BLOCK, Material.MUD,
            Material.PACKED_MUD, Material.CLAY, Material.GRAVEL, Material.SAND, Material.RED_SAND,
            Material.SANDSTONE, Material.RED_SANDSTONE, Material.STONE, Material.COBBLESTONE,
            Material.ANDESITE, Material.DIORITE, Material.GRANITE, Material.TUFF,
            Material.DEEPSLATE, Material.COBBLED_DEEPSLATE, Material.CALCITE,
            Material.DRIPSTONE_BLOCK, Material.NETHERRACK, Material.SOUL_SAND, Material.SOUL_SOIL,
            Material.BASALT, Material.BLACKSTONE, Material.END_STONE, Material.TERRACOTTA,
            Material.SNOW_BLOCK, Material.SNOW, Material.SCULK);

    private Crater() {
    }

    /**
     * Колонки воронки вокруг точки падения.
     *
     * <p>Центр идёт первым, затем кольца: так сокровище звезды оказывается на
     * дне, а не на краю. Радиус 0 — пустой список, то есть «не ломать».</p>
     *
     * @param radius радиус в блоках (0..{@link MeteorSpawn#MAX_CRATER_RADIUS})
     * @return смещения {@code [dx, dz]} от центра
     */
    static List<int[]> columns(int radius) {
        if (radius <= 0) {
            return List.of();
        }
        int limit = radius * radius + radius;
        List<int[]> out = new ArrayList<>();
        out.add(new int[]{0, 0});
        for (int ring = 1; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    if (dx * dx + dz * dz > limit) {
                        continue;
                    }
                    out.add(new int[]{dx, dz});
                }
            }
        }
        return out;
    }

    /** Природный ли блок: только такие звезда вправе выбить. */
    static boolean natural(Material material) {
        return material != null && NATURAL.contains(material);
    }
}
