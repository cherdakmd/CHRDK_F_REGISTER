package ru.cherdakmd.chrdkclaims;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Полный связный контур по индексу приватов, без загрузки Bukkit Chunk. */
final class ClaimBorders {
    interface Lookup { Claim at(int x, int z); }
    record Edge(long x1, long z1, long x2, long z2) { }
    record Outline(String world, String owner, Set<Long> chunks, List<Edge> edges) {
        Outline {
            chunks = Set.copyOf(chunks);
            edges = edges.stream().sorted(EDGE_ORDER).toList();
        }
        boolean contains(int x, int z) { return chunks.contains(ClaimMath.chunkKey(x, z)); }
    }
    private static final Comparator<Edge> EDGE_ORDER = Comparator.comparingLong(Edge::x1)
            .thenComparingLong(Edge::z1).thenComparingLong(Edge::x2).thenComparingLong(Edge::z2);
    private static final int[][] SIDES = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    static List<Edge> visible(int x, int z, Lookup lookup) {
        return outline(x, z, lookup).edges();
    }

    static Outline outline(int x, int z, Lookup lookup) {
        Claim current = lookup.at(x, z);
        List<Edge> edges = new ArrayList<>();
        Set<Long> chunks = new HashSet<>();
        if (current == null) {
            chunks.add(ClaimMath.chunkKey(x, z));
            for (int side = 0; side < 4; side++) edges.add(edge(x, z, side));
            return new Outline("", "", chunks, edges);
        }
        ArrayDeque<Claim> queue = new ArrayDeque<>();
        queue.add(current);
        chunks.add(current.key());
        while (!queue.isEmpty()) {
            Claim part = queue.removeFirst();
            for (int side = 0; side < SIDES.length; side++) {
                long nx = (long) part.x + SIDES[side][0];
                long nz = (long) part.z + SIDES[side][1];
                Claim next = valid(nx) && valid(nz) ? lookup.at((int) nx, (int) nz) : null;
                if (next != null && next.world.equals(current.world) && next.isOwner(current.owner)) {
                    if (chunks.add(next.key())) queue.addLast(next);
                } else {
                    edges.add(edge(part.x, part.z, side));
                }
            }
        }
        return new Outline(current.world, current.owner, chunks, edges);
    }

    private static Edge edge(int x, int z, int side) {
        long bx = (long) x * 16, bz = (long) z * 16;
        return switch (side) {
            case 0 -> new Edge(bx, bz, bx, bz + 16);
            case 1 -> new Edge(bx + 16, bz, bx + 16, bz + 16);
            case 2 -> new Edge(bx, bz, bx + 16, bz);
            case 3 -> new Edge(bx, bz + 16, bx + 16, bz + 16);
            default -> throw new IllegalArgumentException("Неизвестная сторона чанка");
        };
    }
    private static boolean valid(long n) { return n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE; }
    private ClaimBorders() { }
}
