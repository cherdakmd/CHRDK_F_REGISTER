package ru.cherdakmd.chrdkclaims;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/** Pure chunk geometry for a radius around one or more source chunks. */
final class ClaimCircle {
    record Cell(int x, int z) { }
    record Command(int radius, boolean territory, boolean confirm) { }

    /** Parse the full /claim argument array without changing any game state. */
    static Command parse(String[] args) {
        if (args == null || args.length < 2 || args.length > 4 || !"circle".equalsIgnoreCase(args[0]))
            throw new IllegalArgumentException("Invalid circle command");
        int radius;
        try { radius = Integer.parseInt(args[1]); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid radius", ex); }
        if (radius < 1 || radius > 5) throw new IllegalArgumentException("Invalid radius");
        boolean territory = false, confirm = false;
        if (args.length >= 3) {
            if ("territory".equalsIgnoreCase(args[2])) territory = true;
            else if ("confirm".equalsIgnoreCase(args[2])) confirm = true;
            else throw new IllegalArgumentException("Invalid mode");
        }
        if (args.length == 4) {
            if (!territory || !"confirm".equalsIgnoreCase(args[3]))
                throw new IllegalArgumentException("Invalid confirmation");
            confirm = true;
        }
        return new Command(radius, territory, confirm);
    }

    private static final Comparator<Cell> ORDER = Comparator.comparingInt(Cell::x).thenComparingInt(Cell::z);

    /**
     * The union of Euclidean disks measured between chunk coordinates. A strict
     * work/output budget prevents a large territory from monopolising the server.
     * Overflow is rejected rather than silently wrapping around the world.
     */
    static List<Cell> expand(Collection<Cell> sources, int radius, int maxWork, int maxCells) {
        if (sources == null || sources.isEmpty() || radius < 1 || radius > 64 || maxWork < 1 || maxCells < 1)
            throw new IllegalArgumentException("Invalid circle parameters");
        long diameter = 2L * radius + 1;
        if ((long) sources.size() * diameter * diameter > maxWork)
            throw new IllegalArgumentException("Circle work limit exceeded");
        TreeSet<Cell> result = new TreeSet<>(ORDER);
        for (Cell source : sources) {
            if (source == null) throw new IllegalArgumentException("Null source");
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz > radius * radius) continue;
                    long x = (long) source.x + dx, z = (long) source.z + dz;
                    if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE)
                        throw new IllegalArgumentException("Chunk coordinate overflow");
                    result.add(new Cell((int) x, (int) z));
                    if (result.size() > maxCells) throw new IllegalArgumentException("Circle size limit exceeded");
                }
            }
        }
        return List.copyOf(result);
    }

    private ClaimCircle() { }
}
