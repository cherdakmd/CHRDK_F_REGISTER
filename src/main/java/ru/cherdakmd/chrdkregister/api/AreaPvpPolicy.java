package ru.cherdakmd.chrdkregister.api;

import org.bukkit.entity.Player;

/** Read-only preflight for collateral PvP. Never inflict damage, issue fines or mutate state. */
@FunctionalInterface
public interface AreaPvpPolicy {
    enum Verdict { ALLOW, DENY, UNKNOWN }
    Verdict check(Player attacker, Player target);
}
