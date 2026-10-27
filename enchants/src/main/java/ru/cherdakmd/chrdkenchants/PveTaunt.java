package ru.cherdakmd.chrdkenchants;

import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Одно легальное переназначение цели, без фиксации агро, телепорта, лечения или изменения AI. */
final class PveTaunt {
    private static boolean allowed(Monster mob, Player owner, BooleanSupplier context) {
        return context.getAsBoolean() && PveEffectsListener.hostile(mob) && mob.isValid() && !mob.isDead()
                && mob.hasAI() && mob.isAware() && !mob.isLeashed() && owner.isValid() && !owner.isDead()
                && !owner.isInvisible() && !owner.hasMetadata("vanished")
                && mob.getWorld().equals(owner.getWorld())
                && mob.getLocation().distanceSquared(owner.getLocation()) <= 36 && mob.hasLineOfSight(owner);
    }
    static boolean apply(Monster mob, Player owner, BooleanSupplier context, Consumer<EntityTargetLivingEntityEvent> dispatcher) {
        if (!allowed(mob, owner, context)) return false;
        var previous = mob.getTarget(); if (owner.equals(previous)) return false;
        // CraftMob.setTarget сам не гарантирует событие. Сначала штатная отменяемая проверка CUSTOM.
        var targeting = new EntityTargetLivingEntityEvent(mob, owner, EntityTargetEvent.TargetReason.CUSTOM);
        dispatcher.accept(targeting);
        if (targeting.isCancelled() || targeting.getTarget() != owner || !allowed(mob, owner, context)
                || !java.util.Objects.equals(previous, mob.getTarget())) return false;
        mob.setTarget(owner);
        return owner.equals(mob.getTarget());
    }
    private PveTaunt() { }
}
