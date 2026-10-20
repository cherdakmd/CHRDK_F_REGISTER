package ru.cherdakmd.chrdkclaims;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Поиск приватов вокруг игрока для {@code /claim near}: тот же мир, расстояние
 * в чанках по Чебышёву (радиус квадрата), сначала ближние. Чистая геометрия без
 * Bukkit — покрыта unit-тестами.
 */
public final class ClaimNear {

    public static final int MIN_RADIUS = 1;
    public static final int MAX_RADIUS = 16;
    public static final int DEFAULT_RADIUS = 8;
    /** Сколько строк показывать; остальные видны только счётчиком в заголовке. */
    public static final int MAX_RESULTS = 10;

    /** Приват и расстояние до него в чанках. */
    public record Entry(Claim claim, int distance) {
    }

    /**
     * Итог поиска: {@code total} — сколько приватов попало в радиус,
     * {@code entries} — первые {@link #MAX_RESULTS} из них.
     */
    public record Result(List<Entry> entries, int total) {
    }

    private ClaimNear() {
    }

    public static int clampRadius(int raw) {
        return Math.min(MAX_RADIUS, Math.max(MIN_RADIUS, raw));
    }

    /**
     * Ищет приваты рядом с чанком.
     *
     * @param excludeOwner ник, чьи приваты не показывать (обычно сам игрок); {@code null} — все
     */
    public static Result search(List<Claim> claims, String world, int chunkX, int chunkZ,
                                int radius, String excludeOwner) {
        List<Entry> found = new ArrayList<>();
        if (claims == null || world == null) {
            return new Result(List.of(), 0);
        }
        int limit = clampRadius(radius);
        for (Claim claim : claims) {
            if (claim == null || !world.equals(claim.world)) {
                continue;
            }
            if (excludeOwner != null && claim.isOwner(excludeOwner)) {
                continue;
            }
            long dx = (long) claim.x - chunkX;
            long dz = (long) claim.z - chunkZ;
            long distance = Math.max(Math.abs(dx), Math.abs(dz));
            if (distance > limit) {
                continue;
            }
            found.add(new Entry(claim, (int) distance));
        }
        Comparator<Entry> order = Comparator.comparingInt(Entry::distance)
                .thenComparing(entry -> entry.claim().owner, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(entry -> entry.claim().x)
                .thenComparingInt(entry -> entry.claim().z);
        found.sort(order);
        List<Entry> page = found.size() > MAX_RESULTS
                ? List.copyOf(found.subList(0, MAX_RESULTS))
                : List.copyOf(found);
        return new Result(page, found.size());
    }
}
