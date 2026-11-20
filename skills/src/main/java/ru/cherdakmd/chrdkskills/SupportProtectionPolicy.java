package ru.cherdakmd.chrdkskills;

import org.bukkit.entity.Player;

/**
 * Read-only protection check for an effect on another player. A provider must
 * account for its own rules at both endpoints, including zone boundaries,
 * PvP/war restrictions and ability type. Register through Bukkit's
 * ServicesManager; ALLOW covers only this provider, not other plugins.
 * Implementations must not mutate players or spend resources. Consumers must
 * call on the server thread and must not cache the result.
 *
 * This is a preparatory contract: current abilities do not consume it.
 */
public interface SupportProtectionPolicy {
    enum Verdict { ALLOW, DENY, UNKNOWN }
    Verdict check(Player caster, Player target, BranchAbility ability);
}
