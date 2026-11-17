package ru.cherdakmd.chrdkgenerator;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;

import java.util.List;

/**
 * Bukkit-мост ячеек камеры: кандидаты вокруг ядра (N, E, S, W, под ним)
 * пересчитываются в чистые {@link GeneratorChamber.Cell} для скана.
 */
final class GeneratorCells {

    private GeneratorCells() {
    }

    /** Кандидаты ячеек: по горизонтали и под ядром (п.31, §2). */
    static List<Block> candidates(World world, int x, int y, int z) {
        return List.of(
                world.getBlockAt(x, y, z - 1),
                world.getBlockAt(x + 1, y, z),
                world.getBlockAt(x, y, z + 1),
                world.getBlockAt(x - 1, y, z),
                world.getBlockAt(x, y - 1, z));
    }

    static List<GeneratorChamber.Cell> scan(World world, int x, int y, int z) {
        return candidates(world, x, y, z).stream().map(GeneratorCells::cellOf).toList();
    }

    static GeneratorChamber.Cell cellOf(Block block) {
        Material type = block.getType();
        if (type == Material.LAVA) {
            return isSource(block) ? GeneratorChamber.Cell.LAVA_SOURCE : GeneratorChamber.Cell.OTHER;
        }
        if (type == Material.WATER) {
            return isSource(block) ? GeneratorChamber.Cell.WATER_SOURCE : GeneratorChamber.Cell.OTHER;
        }
        return type.isAir() ? GeneratorChamber.Cell.EMPTY : GeneratorChamber.Cell.OTHER;
    }

    /** Только источник (level 0) — «ячейка должна быть лавой/водой-источником». */
    private static boolean isSource(Block block) {
        return block.getBlockData() instanceof Levelled level && level.getLevel() == 0;
    }

    /** Ключ сообщения с причиной остановки (или работа). */
    static String stateKey(GeneratorChamber.State state) {
        return switch (state) {
            case OK -> "state-working";
            case NO_LAVA -> "state-no-lava";
            case NO_WATER -> "state-no-water";
            case NO_CELLS -> "state-no-cells";
        };
    }
}
