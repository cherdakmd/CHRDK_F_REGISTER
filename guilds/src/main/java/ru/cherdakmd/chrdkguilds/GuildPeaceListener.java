package ru.cherdakmd.chrdkguilds;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import java.util.Set;
import java.util.UUID;

/** Адресное PvP: не снимает отмену другого плагина и не меняет обычный PvE-урон. */
final class GuildPeaceListener implements Listener {
    private final GuildsPlugin plugin;
    private final NamespacedKey ownerKey;
    private final NamespacedKey peacefulKey;
    private static final Set<String> HARMFUL=Set.of("instant_damage","poison","wither","slowness","weakness","blindness","hunger","mining_fatigue","nausea","levitation","darkness","unluck","infested","oozing","weaving","wind_charged");
    GuildPeaceListener(GuildsPlugin plugin) {
        this.plugin=plugin;ownerKey=new NamespacedKey(plugin,"pvp_owner");peacefulKey=new NamespacedKey(plugin,"peaceful_launch");
    }
    private boolean enabled(){return plugin.config().enabled();}
    private UUID owner(Entity entity,int depth) {
        if(entity==null || depth>5)return null;
        if(entity instanceof Player player)return plugin.authenticated(player)&&!player.hasMetadata("NPC")?player.getUniqueId():null;
        String marked=entity.getPersistentDataContainer().get(ownerKey,PersistentDataType.STRING);
        if(marked!=null)try{return UUID.fromString(marked);}catch(IllegalArgumentException ignored){return null;}
        if(entity instanceof Projectile shot && shot.getShooter() instanceof Entity source)return owner(source,depth+1);
        if(entity instanceof TNTPrimed tnt)return owner(tnt.getSource(),depth+1);
        if(entity instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Entity source)return owner(source,depth+1);
        if(entity instanceof EvokerFangs fangs)return owner(fangs.getOwner(),depth+1);
        if(entity instanceof Tameable pet && pet.getOwner()!=null)return pet.getOwner().getUniqueId();
        return null;
    }
    private boolean launchedPeaceful(Entity entity){return entity.getPersistentDataContainer().has(peacefulKey,PersistentDataType.BYTE);}
    private void mark(Entity entity,UUID owner) {
        if(owner==null)return;
        entity.getPersistentDataContainer().set(ownerKey,PersistentDataType.STRING,owner.toString());
        if(plugin.peace().peaceful(owner))entity.getPersistentDataContainer().set(peacefulKey,PersistentDataType.BYTE,(byte)1);
        else entity.getPersistentDataContainer().remove(peacefulKey);
    }
    private void markFrom(Entity child,Entity parent) {
        if(parent==null)return;
        mark(child,owner(parent,0));
        if(launchedPeaceful(parent))child.getPersistentDataContainer().set(peacefulKey,PersistentDataType.BYTE,(byte)1);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void tntSpawn(EntitySpawnEvent event) {
        if(enabled()&&event.getEntity() instanceof TNTPrimed tnt)markFrom(tnt,tnt.getSource());
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void launch(ProjectileLaunchEvent event) {if(enabled())mark(event.getEntity(),owner(event.getEntity(),0));}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void placeCrystal(EntityPlaceEvent event) {
        if(enabled()&&event.getEntity() instanceof EnderCrystal&&event.getPlayer()!=null&&plugin.authenticated(event.getPlayer()))mark(event.getEntity(),event.getPlayer().getUniqueId());
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void primeCrystal(EntityDamageByEntityEvent event) {
        if(enabled()&&event.getEntity() instanceof EnderCrystal)mark(event.getEntity(),owner(event.getDamager(),0));
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void damage(EntityDamageByEntityEvent event) {
        if(!enabled() || !(event.getEntity() instanceof Player victim) || !plugin.authenticated(victim) || victim.isDead() || event.getDamage()<=0)return;
        UUID attacker=owner(event.getDamager(),0);
        // Автоматическая защита чужого питомца не создаёт денежный штраф владельцу: иначе мирный мог бы провоцировать волка.
        boolean fine=!(event.getDamager() instanceof Tameable);
        if(plugin.peace().attack(attacker,victim.getUniqueId(),launchedPeaceful(event.getDamager()),fine,System.currentTimeMillis()))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void splash(PotionSplashEvent event) {
        if(!enabled()||event.getPotion().getEffects().stream().noneMatch(e->HARMFUL.contains(e.getType().getKey().getKey())))return;
        UUID attacker=owner(event.getPotion(),0);
        for(LivingEntity entity:event.getAffectedEntities())if(entity instanceof Player victim && plugin.authenticated(victim)&&event.getIntensity(victim)>0) {
            if(plugin.peace().attack(attacker,victim.getUniqueId(),launchedPeaceful(event.getPotion()),System.currentTimeMillis()))event.setIntensity(victim,0);
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void cloudSpawn(LingeringPotionSplashEvent event) {if(enabled())markFrom(event.getAreaEffectCloud(),event.getEntity());}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void cloud(AreaEffectCloudApplyEvent event) {
        if(!enabled())return;
        var cloud=event.getEntity();
        boolean harmful=cloud.getCustomEffects().stream().anyMatch(e->HARMFUL.contains(e.getType().getKey().getKey()));
        if(cloud.getBasePotionType()!=null)harmful|=cloud.getBasePotionType().getPotionEffects().stream().anyMatch(e->HARMFUL.contains(e.getType().getKey().getKey()));
        if(!harmful)return;
        UUID attacker=owner(cloud,0);
        event.getAffectedEntities().removeIf(entity->entity instanceof Player victim&&plugin.authenticated(victim)
                &&plugin.peace().attack(attacker,victim.getUniqueId(),launchedPeaceful(cloud),System.currentTimeMillis()));
    }
    @EventHandler public void quit(PlayerQuitEvent event){plugin.peace().quit(event.getPlayer().getUniqueId());}
}
