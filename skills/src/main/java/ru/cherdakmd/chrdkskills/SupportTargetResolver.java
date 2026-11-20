package ru.cherdakmd.chrdkskills;

import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.bukkit.entity.Player;

/**
 * Composes fresh server-thread evidence. This does not apply an effect, reserve
 * a target, or replace a protection policy. Currently not wired to abilities.
 */
final class SupportTargetResolver {
    @FunctionalInterface interface ProtectionCheck {
        SupportTargetRules.Protection check(Player caster, Player target);
    }

    static Player resolve(Player caster, Function<Player, SupportAim.Candidate> aim,
                          BiFunction<UUID, UUID, SupportRelations.Snapshot> relations,
                          ProtectionCheck protection) {
        if (caster == null) return null;
        if (aim == null || relations == null || protection == null) return caster;
        try {
            var candidate = aim.apply(caster);
            if (candidate == null || candidate.player() == null || candidate.player() == caster) return caster;
            Player target = candidate.player();
            UUID casterId = caster.getUniqueId(), targetId = target.getUniqueId();
            if (casterId == null || targetId == null || casterId.equals(targetId)) return caster;
            var snapshot = relations.apply(casterId, targetId);
            if (snapshot == null) return caster;
            var permission = protection.check(caster, target);
            var evidence = new SupportTargetRules.Evidence(true, true, true, true, true,
                    candidate.hitDistanceSquared(), snapshot.guild(), snapshot.group(), permission);
            return SupportTargetRules.decide(evidence) == SupportTargetRules.Result.EXTERNAL_TARGET ? target : caster;
        } catch (RuntimeException | LinkageError e) {
            return caster;
        }
    }

    private SupportTargetResolver() { }
}
