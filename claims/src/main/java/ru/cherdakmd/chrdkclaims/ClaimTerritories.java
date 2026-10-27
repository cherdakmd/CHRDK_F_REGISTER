package ru.cherdakmd.chrdkclaims;

import java.util.*;

/** Связные по стороне чанки одного владельца. Геометрия не загружает мир/чанки. */
public final class ClaimTerritories {
    private record Cell(String world, int x, int z) { }
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final Comparator<Claim> ORDER = Comparator.comparing((Claim c) -> c.world)
            .thenComparingInt(c -> c.x).thenComparingInt(c -> c.z);

    public static List<List<Claim>> groups(Collection<Claim> claims) {
        Map<Cell, Claim> remaining = new HashMap<>();
        if (claims != null) for (Claim claim : claims) {
            if (claim != null && claim.world != null && !claim.world.isBlank())
                remaining.put(new Cell(claim.world, claim.x, claim.z), claim);
        }
        List<List<Claim>> result = new ArrayList<>();
        while (!remaining.isEmpty()) {
            Claim start = remaining.values().iterator().next();
            List<Claim> group = new ArrayList<>();
            ArrayDeque<Claim> queue = new ArrayDeque<>();
            queue.add(start);
            remaining.remove(new Cell(start.world, start.x, start.z));
            while (!queue.isEmpty()) {
                Claim current = queue.removeFirst();
                group.add(current);
                for (int[] side : SIDES) {
                    long x = (long) current.x + side[0], z = (long) current.z + side[1];
                    if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) continue;
                    Cell cell = new Cell(current.world, (int) x, (int) z);
                    Claim next = remaining.get(cell);
                    if (next != null && next.isOwner(start.owner)) {
                        remaining.remove(cell);
                        queue.add(next);
                    }
                }
            }
            group.sort(ORDER);
            result.add(List.copyOf(group));
        }
        result.sort((a, b) -> ORDER.compare(a.getFirst(), b.getFirst()));
        return result;
    }

    public static List<Claim> representatives(Collection<Claim> claims) {
        return groups(claims).stream().map(List::getFirst).toList();
    }

    /** Только общий доступ с минимальной ролью: слияние не повышает чужие полномочия. */
    static void normalize(List<Claim> group, Collection<Claim> sources, boolean cancelSale) {
        if (group.isEmpty() || sources.isEmpty()) return;
        List<Claim> seeds = new ArrayList<>(sources);
        seeds.sort(ORDER);
        Claim first = seeds.getFirst();
        Map<String, Claim.Role> common = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : first.members) {
            Claim.Role role = first.memberRole(name);
            if (role != null && !first.isOwner(name)) common.put(name, role);
        }
        for (Claim source : seeds) {
            common.entrySet().removeIf(entry -> source.memberRole(entry.getKey()) == null);
            common.replaceAll((name, role) -> source.memberRole(name).ordinal() < role.ordinal() ? source.memberRole(name) : role);
        }
        String greeting = first.greeting;
        boolean inconsistentSale = seeds.stream().anyMatch(c -> c.salePrice != first.salePrice);
        for (Claim claim : group) {
            claim.clearMembers();
            common.forEach((name, role) -> { claim.members.add(name); claim.setRole(name, role); });
            claim.greeting = greeting;
            if (cancelSale || inconsistentSale) claim.salePrice = 0;
        }
    }

    private ClaimTerritories() { }
}
