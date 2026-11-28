package ru.cherdakmd.chrdkregister.api;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/** Fail-closed collateral PvP preflight, not a substitute for a normal damage event. */
public final class AreaPvpGate {
    private static final Set<String> REQUIRED = Set.of("chrdk_claims", "chrdk_guilds");
    record Provider(String name, boolean enabled, AreaPvpPolicy policy) { }

    public static boolean allows(Player attacker, Player target) {
        try {
            if (!Bukkit.isPrimaryThread() || attacker == null || target == null
                    || attacker.getUniqueId().equals(target.getUniqueId())
                    || attacker.hasMetadata("NPC") || target.hasMetadata("NPC")
                    || !attacker.isOnline() || !target.isOnline() || attacker.isDead() || target.isDead()
                    || attacker.getWorld() != target.getWorld() || !target.getWorld().getPVP()
                    || target.isInvulnerable() || target.getGameMode() == GameMode.CREATIVE
                    || target.getGameMode() == GameMode.SPECTATOR) return false;
            var providers = new java.util.ArrayList<Provider>();
            for (var registration : Bukkit.getServicesManager().getRegistrations(AreaPvpPolicy.class)) {
                var owner = registration.getPlugin();
                providers.add(new Provider(owner == null ? null : owner.getName(),
                        owner != null && owner.isEnabled(), registration.getProvider()));
            }
            return evaluate(attacker, target, providers);
        } catch (RuntimeException | LinkageError error) {
            return false;
        }
    }

    static boolean evaluate(Player attacker, Player target, Collection<Provider> providers) {
        if (attacker == null || target == null || providers == null) return false;
        Set<String> missing = new HashSet<>(REQUIRED);
        try {
            for (var provider : providers) {
                if (provider == null || provider.name() == null || provider.name().isBlank()
                        || !provider.enabled() || provider.policy() == null) return false;
                missing.remove(provider.name().toLowerCase(Locale.ROOT));
                if (provider.policy().check(attacker, target) != AreaPvpPolicy.Verdict.ALLOW) return false;
            }
            return missing.isEmpty();
        } catch (RuntimeException | LinkageError error) {
            return false;
        }
    }
    private AreaPvpGate() { }
}
