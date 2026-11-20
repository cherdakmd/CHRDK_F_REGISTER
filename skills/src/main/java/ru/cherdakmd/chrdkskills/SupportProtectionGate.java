package ru.cherdakmd.chrdkskills;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Explicit multi-provider agreement; a missing provider never becomes ALLOW. */
final class SupportProtectionGate {
    record Source(String name, boolean enabled, SupportProtectionPolicy policy) { }

    /** Caller must configure all relevant protection plugins; not wired into abilities. */
    static SupportTargetRules.Protection check(Player caster, Player target, BranchAbility ability, Set<String> required) {
        try {
            if (!Bukkit.isPrimaryThread()) return SupportTargetRules.Protection.UNKNOWN;
            var sources = new ArrayList<Source>();
            for (var registration : Bukkit.getServicesManager().getRegistrations(SupportProtectionPolicy.class)) {
                var owner = registration.getPlugin();
                sources.add(new Source(owner == null ? null : owner.getName(), owner != null && owner.isEnabled(), registration.getProvider()));
            }
            return evaluate(caster, target, ability, required, sources);
        } catch (RuntimeException | LinkageError e) {
            return SupportTargetRules.Protection.UNKNOWN;
        }
    }

    static SupportTargetRules.Protection evaluate(Player caster, Player target, BranchAbility ability,
                                                   Set<String> required, Collection<Source> sources) {
        if (caster == null || target == null || caster == target || !support(ability)
                || required == null || required.isEmpty() || sources == null || sources.isEmpty())
            return SupportTargetRules.Protection.UNKNOWN;
        Set<String> missing = new HashSet<>();
        for (String name : required) {
            if (name == null || name.isBlank()) return SupportTargetRules.Protection.UNKNOWN;
            missing.add(name.toLowerCase(Locale.ROOT));
        }
        boolean uncertain = false;
        for (Source source : sources) {
            if (source == null || source.name() == null || source.name().isBlank()
                    || !source.enabled() || source.policy() == null) {
                uncertain = true; continue;
            }
            missing.remove(source.name().toLowerCase(Locale.ROOT));
            try {
                var verdict = source.policy().check(caster, target, ability);
                if (verdict == SupportProtectionPolicy.Verdict.DENY) return SupportTargetRules.Protection.DENY;
                if (verdict != SupportProtectionPolicy.Verdict.ALLOW) uncertain = true;
            } catch (RuntimeException | LinkageError e) {
                uncertain = true;
            }
        }
        return uncertain || !missing.isEmpty() ? SupportTargetRules.Protection.UNKNOWN : SupportTargetRules.Protection.ALLOW;
    }

    private static boolean support(BranchAbility ability) {
        return ability == BranchAbility.FAITH_SHIELD || ability == BranchAbility.PURGE
                || ability == BranchAbility.HEALING_SHIELD || ability == BranchAbility.GRACE;
    }
    private SupportProtectionGate() { }
}
