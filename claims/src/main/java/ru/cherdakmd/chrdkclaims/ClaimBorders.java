package ru.cherdakmd.chrdkclaims;

import java.util.ArrayList;
import java.util.List;

/** Только внешний контур: максимум 5×5 соседних чанков, без загрузки Bukkit Chunk. */
final class ClaimBorders {
    interface Lookup { Claim at(int x, int z); }
    record Edge(long x1, long z1, long x2, long z2) { }
    static List<Edge> visible(int x, int z, Lookup lookup) {
        Claim current = lookup.at(x, z);
        List<Edge> result = new ArrayList<>();
        int radius = current == null ? 0 : 2;
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            long nx = (long) x + dx, nz = (long) z + dz;
            if (!valid(nx) || !valid(nz)) continue;
            Claim c = lookup.at((int) nx, (int) nz);
            if (current != null && (c == null || !c.isOwner(current.owner))) continue;
            long bx = nx * 16, bz = nz * 16;
            if (current == null || !same(lookup, nx - 1, nz, current.owner)) result.add(new Edge(bx, bz, bx, bz + 16));
            if (current == null || !same(lookup, nx + 1, nz, current.owner)) result.add(new Edge(bx + 16, bz, bx + 16, bz + 16));
            if (current == null || !same(lookup, nx, nz - 1, current.owner)) result.add(new Edge(bx, bz, bx + 16, bz));
            if (current == null || !same(lookup, nx, nz + 1, current.owner)) result.add(new Edge(bx, bz + 16, bx + 16, bz + 16));
        }
        return result;
    }
    private static boolean valid(long n) { return n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE; }
    private static boolean same(Lookup lookup, long x, long z, String owner) {
        if (!valid(x) || !valid(z)) return false;
        Claim c = lookup.at((int) x, (int) z);
        return c != null && c.isOwner(owner);
    }
    private ClaimBorders() { }
}
