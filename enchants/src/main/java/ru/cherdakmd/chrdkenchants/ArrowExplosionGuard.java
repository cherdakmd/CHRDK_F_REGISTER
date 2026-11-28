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
    private record Shot(Player shooter, org.bukkit.entity.Arrow arrow) { }
    private final Deque<Shot> shots = new ArrayDeque<>();

    void explode(Player shooter, org.bukkit.entity.Arrow arrow, Runnable action) {
        shots.push(new Shot(shooter, arrow));
        try { action.run(); } finally { shots.pop(); }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void protect(EntityDamageEvent event) {
        if (shots.isEmpty() || !(event.getEntity() instanceof Player target)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                && event.getCause() != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) return;
        var shot = shots.peek();
        if (!AreaPvpGate.allows(shot.shooter(), target, shot.arrow())) event.setCancelled(true);
    }
}
