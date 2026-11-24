package ru.cherdakmd.chrdkfishing;

import java.util.Set;
import java.util.random.RandomGenerator;

/** Pure precondition for the future event adapter: never replace treasure or another plugin's item. */
public final class CatchPolicy {
    private static final Set<String> VANILLA_FISH = Set.of("COD", "SALMON", "TROPICAL_FISH", "PUFFERFISH");
    public record Attempt(boolean enabled, boolean authenticated, boolean survival, boolean cancelled,
                          boolean caughtFishEvent, boolean hasMetadata, String material, int amount) { }
    private CatchPolicy() { }
    /** chance is in basis points, 0..10000. The adapter must check the final non-cancelled event. */
    public static boolean replace(Attempt attempt, int chance, RandomGenerator random) {
        if (attempt == null || random == null || chance < 0 || chance > 10_000) throw new IllegalArgumentException("catch policy");
        if (!attempt.enabled() || !attempt.authenticated() || !attempt.survival() || attempt.cancelled()
                || !attempt.caughtFishEvent() || attempt.hasMetadata() || attempt.amount() != 1
                || attempt.material() == null || !VANILLA_FISH.contains(attempt.material())) return false;
        return chance > 0 && random.nextInt(10_000) < chance;
    }
}
