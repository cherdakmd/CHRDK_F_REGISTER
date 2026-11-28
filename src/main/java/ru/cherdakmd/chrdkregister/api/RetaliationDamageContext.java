package ru.cherdakmd.chrdkregister.api;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/** Identifies a synchronous automatic PvP retaliation, without bypassing the damage event or protections. */
public final class RetaliationDamageContext {
    private record Hit(UUID source, UUID target) { }
    private static final ThreadLocal<Deque<Hit>> HITS = ThreadLocal.withInitial(ArrayDeque::new);

    public static void run(Player source, Player target, Runnable damage) {
        if (source == null || target == null || damage == null) throw new IllegalArgumentException("retaliation hit");
        var hits = HITS.get();
        hits.push(new Hit(source.getUniqueId(), target.getUniqueId()));
        try { damage.run(); }
        finally { hits.pop(); if (hits.isEmpty()) HITS.remove(); }
    }

    public static boolean isRetaliation(EntityDamageByEntityEvent event) {
        if (event == null || !(event.getDamager() instanceof Player source)
                || !(event.getEntity() instanceof Player target)) return false;
        var hits = HITS.get();
        try { return !hits.isEmpty() && hits.peek().equals(new Hit(source.getUniqueId(), target.getUniqueId())); }
        finally { if (hits.isEmpty()) HITS.remove(); }
    }

    private RetaliationDamageContext() { }
}
