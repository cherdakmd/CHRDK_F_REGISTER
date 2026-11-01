package ru.cherdakmd.chrdkprofessions;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import java.util.*;
/** Ремесленная мастерская не является боевым ремонтом/снабжением. */
final class ProfessionCombat implements Listener {
    private final Map<UUID,Long> until=new HashMap<>();
    long remaining(UUID id){long now=System.currentTimeMillis();until.values().removeIf(t->t<=now);return Math.max(0,until.getOrDefault(id,0L)-now);}
    @EventHandler(priority=EventPriority.MONITOR)
    public void attack(EntityDamageByEntityEvent event){
        if(!(event.getEntity() instanceof Player victim))return;
        Entity source=event.getDamager();if(source instanceof Projectile p&&p.getShooter() instanceof Entity e)source=e;
        if(!(source instanceof Player actor)||actor.getUniqueId().equals(victim.getUniqueId()))return;
        remaining(actor.getUniqueId());long end=System.currentTimeMillis()+30_000;
        until.put(actor.getUniqueId(),end);until.put(victim.getUniqueId(),end);
    }
}
