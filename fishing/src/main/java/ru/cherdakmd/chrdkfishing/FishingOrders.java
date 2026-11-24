package ru.cherdakmd.chrdkfishing;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** Deterministic, provisional daily contracts; no storage or payment side effects. */
public final class FishingOrders {
    public record Order(long day, int slot, String speciesId, FishingModel.Size size, long rewardMinor) {
        public Order {
            if (day < 0 || slot < 0 || slot >= 3 || FishCatalog.byId(speciesId) == null || size == null
                    || rewardMinor < 1 || rewardMinor > 1_000_000) throw new IllegalArgumentException("fishing order");
        }
        public String key() { return day + ":" + slot; }
        public boolean matches(FishToken.Token fish) {
            return fish != null && speciesId.equals(fish.fish().speciesId()) && size == fish.fish().size();
        }
    }
    public static long utcDay() { return Instant.now().atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay(); }
    public static List<Order> forDay(long day) {
        if (day < 0) throw new IllegalArgumentException("order day");
        var catalog = FishCatalog.all(); var orders = new ArrayList<Order>();
        int start = (int) (Math.floorMod(day, catalog.size()) * 3 % catalog.size());
        for (int i = 0; i < 3; i++) {
            var species = catalog.get((start + i) % catalog.size());
            var size = FishingModel.Size.values()[(int) Math.floorMod(day + i, 3)];
            var catchInfo = new FishingModel.Catch(species.id(), size, species.lengths().get(size.ordinal()).min());
            long base = FishingModel.price(catchInfo);
            long reward = Math.addExact(Math.multiplyExact(base, 3), 1) / 2;
            orders.add(new Order(day, i, species.id(), size, reward));
        }
        return List.copyOf(orders);
    }
    private FishingOrders() { }
}
