package ru.cherdakmd.chrdkfishing;

import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** Pure, non-integrated domain model. Prices are provisional points, not bank currency. */
public final class FishingModel {
    public enum Rarity { COMMON, UNCOMMON, RARE, VERY_RARE, LEGENDARY, MYTHIC }
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
    private static final int[] RARITY_WEIGHTS = {550, 280, 120, 40, 9, 1};
    private static final int[] SIZE_WEIGHTS = {30, 50, 17, 3};
    private static final int[] PRICE_NUMERATORS = {3, 4, 6, 12};

    private FishingModel() { }

    /** Global-pool compatibility overload; no skill or environmental bonuses. */
    public static Catch roll(RandomGenerator random, int rodLevel) {
        return roll(random, rodLevel, 1, FishingRules.defaults(), new FishingRules.Context(null, null, false, false));
    }

    /**
     * Rules select a pool before weights are drawn. If a restricted pool lacks a rarity,
     * that rarity's weight is removed; remaining rarity weights are renormalized by weighted().
     * Skill shifts up to 3 percentage points from small to trophy, and the rod shifts
     * up to 10 points from small to large. Neither modifies total probability.
     */
    public static Catch roll(RandomGenerator random, int rodLevel, int skillLevel, FishingRules rules, FishingRules.Context context) {
        return roll(random, rodLevel, skillLevel, rules, context, null);
    }

    /**
     * Optional bait modifies only the current draw. A paid bait must first be
     * redeemed through the authoritative journal; this method never spends it.
     * Rare bait shifts up to five weight units from common to rare only when
     * both rarities exist in the configured pool; large bait shifts up to five
     * size points from small to large. Quick bait affects wait time elsewhere.
     */
    public static Catch roll(RandomGenerator random, int rodLevel, int skillLevel, FishingRules rules,
                             FishingRules.Context context, BaitCatalog.Bait bait) {
        Objects.requireNonNull(random, "random");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(context, "context");
        if (skillLevel < 1 || skillLevel > 100) throw new IllegalArgumentException("skill level");
        Rod rod = rod(rodLevel);
        Set<String> allowed = rules.eligible(context.zone(), context.biome());
        int[] rarityWeights = RARITY_WEIGHTS.clone();
        for (Rarity rarity : Rarity.values()) {
            if (FishCatalog.byRarity(rarity).stream().noneMatch(fish -> allowed.isEmpty() || allowed.contains(fish.id())))
                rarityWeights[rarity.ordinal()] = 0;
        }
        if (bait != null && bait.effect() == BaitCatalog.Effect.RARE_FISH
                && rarityWeights[Rarity.COMMON.ordinal()] > 0 && rarityWeights[Rarity.RARE.ordinal()] > 0) {
            int shift = Math.min(bait.bonusPercent() * 10, rarityWeights[Rarity.COMMON.ordinal()]);
            rarityWeights[Rarity.COMMON.ordinal()] -= shift;
            rarityWeights[Rarity.RARE.ordinal()] += shift;
        }
        Rarity rarity = Rarity.values()[weighted(random, rarityWeights)];
        List<Species> pool = FishCatalog.byRarity(rarity).stream()
                .filter(fish -> allowed.isEmpty() || allowed.contains(fish.id())).toList();
        int[] speciesWeights = new int[pool.size()];
        for (int i = 0; i < pool.size(); i++) {
            String id = pool.get(i).id();
            boolean nightBonus = context.night() && (id.equals("burbot") || id.equals("shadow_burbot"));
            boolean rainBonus = context.raining() && (id.equals("pike") || id.equals("zander"));
            speciesWeights[i] = rules.environmentEnabled() && (nightBonus || rainBonus) ? 125 : 100;
        }
        Species species = pool.get(weighted(random, speciesWeights));
        int[] weights = SIZE_WEIGHTS.clone();
        int skillBonus = (skillLevel - 1) * 3 / 99;
        weights[Size.SMALL.ordinal()] -= rod.largeBonusPoints() + skillBonus;
        weights[Size.LARGE.ordinal()] += rod.largeBonusPoints();
        weights[Size.TROPHY.ordinal()] += skillBonus;
        if (bait != null && bait.effect() == BaitCatalog.Effect.LARGE_FISH) {
            int shift = Math.min(bait.bonusPercent(), weights[Size.SMALL.ordinal()]);
            weights[Size.SMALL.ordinal()] -= shift;
            weights[Size.LARGE.ordinal()] += shift;
        }
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

    /** New standard catches cost 10x previous rounded prices; premium fish cost 10x their standard reference. Old signed quotes remain untouched. */
    public static long price(Catch fish) {
        Species species = validate(fish);
        Species reference = FishCatalog.priceReference(species.id());
        long base = reference == null ? species.basePrice() : reference.basePrice();
        long numerator = Math.multiplyExact(base, PRICE_NUMERATORS[fish.size().ordinal()]);
        long ordinary = Math.multiplyExact(Math.addExact(numerator, 2) / 4, 10);
        return reference == null ? ordinary : Math.multiplyExact(ordinary, 10);
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
