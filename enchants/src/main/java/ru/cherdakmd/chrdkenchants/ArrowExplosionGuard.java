package ru.cherdakmd.chrdkenchants;

import java.util.ArrayDeque;
import java.util.Deque;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import ru.cherdakmd.chrdkregister.api.AreaPvpGate;

/** Cancel forbidden collateral before guilds can record a sanction. Scope is synchronous. */
final class ArrowExplosionGuard implements Listener {
    private final Deque<Player> shooters = new ArrayDeque<>();

    void explode(Player shooter, Runnable action) {
        shooters.push(shooter);
        try { action.run(); } finally { shooters.pop(); }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void protect(EntityDamageEvent event) {
        if (shooters.isEmpty() || !(event.getEntity() instanceof Player target)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                && event.getCause() != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) return;
        if (!AreaPvpGate.allows(shooters.peek(), target)) event.setCancelled(true);
    }
}
