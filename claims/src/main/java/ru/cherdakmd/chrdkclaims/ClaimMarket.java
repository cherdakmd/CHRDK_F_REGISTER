package ru.cherdakmd.chrdkclaims;

import java.util.ArrayList;
import java.util.List;

/**
 * Витрина чужих приватов, выставленных на продажу: чистая сортировка
 * «сначала текущий мир и ближние чанки», поэтому её можно тестировать без Bukkit.
 */
public final class ClaimMarket {

    /** Один лот витрины: чанк, владелец и цена в алмазах. */
    public record Offer(String world, int x, int z, String owner, int price) {
    }

    /**
     * Расстояние от игрока до лота в чанках (округление до целого).
     * Для другого мира расстояния нет — возвращается {@link Integer#MAX_VALUE}.
     */
    public static int distance(Offer offer, String world, int chunkX, int chunkZ) {
        if (offer == null || world == null || !world.equals(offer.world())) {
            return Integer.MAX_VALUE;
        }
        long dx = (long) offer.x() - chunkX;
        long dz = (long) offer.z() - chunkZ;
        return (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
    }

    /** Лоты в порядке показа: свой мир и ближние чанки — первыми, затем по миру и координатам. */
    public static List<Offer> sorted(List<Offer> offers, String world, int chunkX, int chunkZ) {
        List<Offer> copy = new ArrayList<>(offers);
        copy.sort((left, right) -> {
            boolean leftHere = world != null && world.equals(left.world());
            boolean rightHere = world != null && world.equals(right.world());
            if (leftHere != rightHere) {
                return leftHere ? -1 : 1;
            }
            int byDistance = Integer.compare(distance(left, world, chunkX, chunkZ),
                    distance(right, world, chunkX, chunkZ));
            if (byDistance != 0) {
                return byDistance;
            }
            int byWorld = left.world().compareTo(right.world());
            if (byWorld != 0) {
                return byWorld;
            }
            int byX = Integer.compare(left.x(), right.x());
            if (byX != 0) {
                return byX;
            }
            return Integer.compare(left.z(), right.z());
        });
        return copy;
    }

    private ClaimMarket() {
    }
}
