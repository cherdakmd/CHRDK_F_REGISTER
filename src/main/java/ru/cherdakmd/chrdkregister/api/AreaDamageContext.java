package ru.cherdakmd.chrdkregister.api;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/** Marks synchronous attributed ability damage so it isn't mistaken for a weapon hit.
 * The ordinary damage event is still sent to every protection and defense listener. */
public final class AreaDamageContext {
    private record Hit(UUID attacker, UUID victim) { }
    private static final ThreadLocal<Deque<Hit>> HITS = ThreadLocal.withInitial(ArrayDeque::new);

    public static void run(Player attacker, Player victim, Runnable damage) {
        if (attacker == null || victim == null || damage == null) throw new IllegalArgumentException("area hit");
        var hits = HITS.get();
        hits.push(new Hit(attacker.getUniqueId(), victim.getUniqueId()));
        try { damage.run(); }
        finally { hits.pop(); if (hits.isEmpty()) HITS.remove(); }
    }

    public static boolean isAbilityDamage(EntityDamageByEntityEvent event) {
        if (event == null || !(event.getDamager() instanceof Player attacker)
                || !(event.getEntity() instanceof Player victim)) return false;
        var hits = HITS.get();
        try { return !hits.isEmpty() && hits.peek().equals(new Hit(attacker.getUniqueId(), victim.getUniqueId())); }
        finally { if (hits.isEmpty()) HITS.remove(); }
    }
    private AreaDamageContext() { }
}
