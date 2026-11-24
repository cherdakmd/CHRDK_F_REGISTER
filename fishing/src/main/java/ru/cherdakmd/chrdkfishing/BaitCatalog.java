package ru.cherdakmd.chrdkfishing;

import java.util.List;
import java.util.Locale;

/** Provisional balance proposals only. No purchase or fishing bonus is authorized here. */
public final class BaitCatalog {
    public enum Effect { WAIT_TIME, RARE_FISH, LARGE_FISH }
    public record Bait(String id, String name, long priceMinor, Effect effect, int bonusPercent) {
        public Bait {
            if (id == null || !id.matches("[a-z][a-z0-9_]{0,31}") || name == null || name.isBlank()
                    || priceMinor < 1 || priceMinor > 1_000_000 || effect == null || bonusPercent < 1 || bonusPercent > 10)
                throw new IllegalArgumentException("bait proposal");
        }
    }
    private static final List<Bait> ALL = List.of(
            new Bait("quick", "Быстрая приманка", 5, Effect.WAIT_TIME, 5),
            new Bait("rare", "Ароматная приманка", 10, Effect.RARE_FISH, 5),
            new Bait("large", "Трофейная приманка", 15, Effect.LARGE_FISH, 5));
    public static List<Bait> all() { return ALL; }
    public static Bait byId(String id) {
        if (id == null || !id.equals(id.toLowerCase(Locale.ROOT))) return null;
        return ALL.stream().filter(bait -> bait.id().equals(id)).findFirst().orElse(null);
    }
    private BaitCatalog() { }
}
