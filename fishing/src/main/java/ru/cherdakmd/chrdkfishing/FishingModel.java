package ru.cherdakmd.chrdkfishing;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** Pure, non-integrated domain model. Prices are provisional points, not bank currency. */
public final class FishingModel {
    public enum Rarity { COMMON, UNCOMMON, RARE, VERY_RARE, LEGENDARY }
    public enum Size { SMALL, NORMAL, LARGE, TROPHY }

    public record Range(int min, int max) {
        public Range {
            if (min < 1 || max < min || max > 1000) throw new IllegalArgumentException("length range");
        }
        int roll(RandomGenerator random) { return random.nextInt(min, max + 1); }
        boolean contains(int value) { return value >= min && value <= max; }
    }
    public record Species(String id, String name, Rarity rarity, boolean fictional, long basePrice,
                          List<Range> lengths) {
        public Species {
            if (id == null || !id.matches("[a-z][a-z0-9_]{0,63}") || name == null || name.isBlank()
                    || rarity == null || basePrice <= 0 || basePrice > 1_000_000 || lengths == null || lengths.size() != Size.values().length)
                throw new IllegalArgumentException("species");
            lengths = List.copyOf(lengths);
            for (int i = 1; i < lengths.size(); i++) {
                if (lengths.get(i - 1).max() >= lengths.get(i).min()) throw new IllegalArgumentException("overlapping lengths");
            }
        }
    }
    public record Catch(String speciesId, Size size, int lengthCm) {
        public Catch {
            if (speciesId == null || size == null || lengthCm < 1) throw new IllegalArgumentException("catch");
        }
    }
    public record Rod(int level, String name, int xpBonusPercent, int largeBonusPoints) {
        public Rod {
            if (level < 1 || level > 10 || name == null || name.isBlank()
                    || xpBonusPercent < 0 || largeBonusPoints < 0 || largeBonusPoints > 30)
                throw new IllegalArgumentException("rod");
        }
    }
    private static final int[] RARITY_WEIGHTS = {55, 28, 12, 4, 1};
    private static final int[] SIZE_WEIGHTS = {30, 50, 17, 3};
    private static final int[] PRICE_NUMERATORS = {3, 4, 6, 12};

    private FishingModel() { }

    /** Location is intentionally absent: the default model has one global pool. */
    public static Catch roll(RandomGenerator random, int rodLevel) {
        Objects.requireNonNull(random, "random");
        Rod rod = rod(rodLevel);
        Rarity rarity = Rarity.values()[weighted(random, RARITY_WEIGHTS)];
        List<Species> pool = FishCatalog.byRarity(rarity);
        Species species = pool.get(random.nextInt(pool.size()));
        int[] weights = SIZE_WEIGHTS.clone();
        weights[Size.SMALL.ordinal()] -= rod.largeBonusPoints();
        weights[Size.LARGE.ordinal()] += rod.largeBonusPoints();
        Size size = Size.values()[weighted(random, weights)];
        return new Catch(species.id(), size, species.lengths().get(size.ordinal()).roll(random));
    }

    private static int weighted(RandomGenerator random, int[] weights) {
        int total = 0;
        for (int weight : weights) {
            if (weight < 0) throw new IllegalArgumentException("negative weight");
            total = Math.addExact(total, weight);
        }
        if (total == 0) throw new IllegalArgumentException("empty weights");
        int roll = random.nextInt(total);
        for (int i = 0; i < weights.length; i++) {
            if (roll < weights[i]) return i;
            roll -= weights[i];
        }
        throw new AssertionError("unreachable");
    }

    /** Prices use half-up rounding to a whole provisional point, never floating point. */
    public static long price(Catch fish) {
        Species species = validate(fish);
        long numerator = Math.multiplyExact(species.basePrice(), PRICE_NUMERATORS[fish.size().ordinal()]);
        return Math.addExact(numerator, 2) / 4;
    }

    public static Species validate(Catch fish) {
        Objects.requireNonNull(fish, "fish");
        Species species = FishCatalog.byId(fish.speciesId());
        if (species == null || !species.lengths().get(fish.size().ordinal()).contains(fish.lengthCm()))
            throw new IllegalArgumentException("invalid catch");
        return species;
    }

    private static final List<Rod> RODS = List.of(
            new Rod(1, "Простая", 0, 0), new Rod(2, "Удочка новичка", 2, 1),
            new Rod(3, "Озёрная", 4, 2), new Rod(4, "Речная", 6, 3),
            new Rod(5, "Карельская", 8, 4), new Rod(6, "Северная", 10, 5),
            new Rod(7, "Удочка мастера", 12, 6), new Rod(8, "Трофейная", 14, 7),
            new Rod(9, "Легендарная", 16, 8), new Rod(10, "Удочка хранителя вод", 20, 10));
    public static Rod rod(int level) {
        if (level < 1 || level > RODS.size()) throw new IllegalArgumentException("rod level");
        return RODS.get(level - 1);
    }
    public static List<Rod> rods() { return RODS; }
}
