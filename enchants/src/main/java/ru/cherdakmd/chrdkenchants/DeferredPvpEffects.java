package ru.cherdakmd.chrdkenchants;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/** Bounded next-tick side effects; original event is rechecked after all listeners. */
final class DeferredPvpEffects {
    private static final int MAX_PENDING = 2048;
    private final Consumer<Runnable> scheduler;
    private int pending;

    DeferredPvpEffects(Consumer<Runnable> scheduler) { this.scheduler = Objects.requireNonNull(scheduler); }

    boolean submit(EntityDamageByEntityEvent event, BooleanSupplier stillValid, Runnable effect) {
        if (event == null || stillValid == null || effect == null || event.isCancelled()
                || !DamageEvidence.positive(event.getFinalDamage()) || pending >= MAX_PENDING) return false;
        pending++;
        boolean[] finished = {false};
        try {
            scheduler.accept(() -> {
                if (finished[0]) return;
                finished[0] = true;
                pending--;
                if (!event.isCancelled() && DamageEvidence.positive(event.getFinalDamage()) && stillValid.getAsBoolean()) effect.run();
            });
            return true;
        } catch (RuntimeException | LinkageError error) {
            if (!finished[0]) { finished[0] = true; pending--; }
            return false;
        }
    }
    int pending() { return pending; }
}
