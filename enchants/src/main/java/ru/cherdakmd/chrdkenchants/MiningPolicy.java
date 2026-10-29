package ru.cherdakmd.chrdkenchants;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Чистая геометрия и таблицы преобразования: количеством управляет ванильный loot. */
final class MiningPolicy {
    record Offset(int x, int y, int z) { }
    static final List<Offset> NEIGHBORS = List.of(new Offset(1,0,0),new Offset(-1,0,0),new Offset(0,1,0),new Offset(0,-1,0),
            new Offset(0,0,1),new Offset(0,0,-1),new Offset(1,1,0),new Offset(-1,1,0),new Offset(0,1,1),new Offset(0,1,-1),
            new Offset(1,0,1),new Offset(-1,0,-1),new Offset(1,0,-1),new Offset(-1,0,1));
    private static final Map<String,String> MINERALS = Map.ofEntries(
            Map.entry("IRON_ORE","RAW_IRON"),Map.entry("GOLD_ORE","RAW_GOLD"),Map.entry("COPPER_ORE","RAW_COPPER"),
            Map.entry("COAL_ORE","COAL"),Map.entry("REDSTONE_ORE","REDSTONE"),Map.entry("LAPIS_ORE","LAPIS_LAZULI"),
            Map.entry("EMERALD_ORE","EMERALD"),Map.entry("DIAMOND_ORE","DIAMOND"),
            Map.entry("NETHER_QUARTZ_ORE","QUARTZ"),Map.entry("NETHER_GOLD_ORE","GOLD_NUGGET"));
    static boolean ore(String type) { return MINERALS.containsKey(base(type)); }
    static String mineral(String type) { return MINERALS.get(base(type)); }
    private static String base(String name) { return name == null ? "" : name.startsWith("DEEPSLATE_") ? name.substring(10) : name; }
    static String smelt(String ore, String drop, boolean silk) {
        if (silk) return drop;
        return switch (base(ore)) {
            case "IRON_ORE" -> drop.equals("RAW_IRON") ? "IRON_INGOT" : drop;
            case "GOLD_ORE" -> drop.equals("RAW_GOLD") ? "GOLD_INGOT" : drop;
            case "COPPER_ORE" -> drop.equals("RAW_COPPER") ? "COPPER_INGOT" : drop;
            case "ANCIENT_DEBRIS" -> drop.equals("ANCIENT_DEBRIS") ? "NETHERITE_SCRAP" : drop;
            default -> drop;
        };
    }
    static List<Offset> area(double x, double y, double z, int level) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return List.of();
        boolean vertical = Math.abs(y) > .6, alongX = Math.abs(x) > Math.abs(z);
        int sign = (vertical ? y : alongX ? x : z) < 0 ? -1 : 1;
        List<Offset> offsets = new ArrayList<>();
        for (int depth = 0; depth < (level >= 2 ? 2 : 1); depth++) for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
            Offset offset = vertical ? new Offset(a, sign * depth, b) : alongX ? new Offset(sign * depth, a, b) : new Offset(a, b, sign * depth);
            if (offset.x != 0 || offset.y != 0 || offset.z != 0) offsets.add(offset);
        }
        return List.copyOf(offsets);
    }
    static long position(int x, int y, int z) { return ((long) y << 8) | ((z & 15) << 4) | (x & 15); }
    static boolean validPositions(long[] values, int minimumY, int maximumY) {
        if (values == null || values.length > 4096) return false;
        var unique = new java.util.HashSet<Long>();
        for (long value : values) if ((value >> 8) < minimumY || (value >> 8) >= maximumY || !unique.add(value)) return false;
        return true;
    }
    static long[] mark(long[] values, long position) {
        if (values == null || values.length > 4096) return null;
        for (long value : values) if (value == position) return values;
        if (values.length >= 4096) return null; // null = консервативно запрещаем бонус всей ячейке чанка.
        long[] result = java.util.Arrays.copyOf(values, values.length + 1); result[values.length] = position; return result;
    }
    private MiningPolicy() { }
}
