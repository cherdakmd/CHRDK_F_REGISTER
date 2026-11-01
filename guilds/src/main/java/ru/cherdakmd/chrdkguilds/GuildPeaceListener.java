package ru.cherdakmd.chrdkguilds;

import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import java.util.Set;
import java.util.UUID;

/** Адресный PvP: не снимает чужую отмену; наказание требует доказанного действия. */
final class GuildPeaceListener implements Listener {
    interface Access {
        boolean enabled();
        boolean authenticated(Player player);
        boolean peaceful(UUID owner);
        GuildPeaceController.AttackOutcome attack(UUID attacker,UUID victim,boolean launchPeaceful,boolean peacefulAction,
                                                   boolean penalize,UUID source,String kind,long now);
        void quit(UUID owner);
    }
    private final Access access;
    private final NamespacedKey ownerKey, peacefulKey, sourceKey, usedKey, createdKey;
    private static final Set<String> HARMFUL=Set.of("instant_damage","poison","wither","slowness","weakness","blindness","hunger","mining_fatigue","nausea","levitation","darkness","unluck","infested","oozing","weaving","wind_charged");
    GuildPeaceListener(GuildsPlugin plugin) {
        this(new Access() {
            public boolean enabled(){return plugin.config().enabled();}
            public boolean authenticated(Player p){return plugin.authenticated(p);}
            public boolean peaceful(UUID id){return plugin.peace().peaceful(id);}
            public GuildPeaceController.AttackOutcome attack(UUID attacker,UUID victim,boolean launchedPeaceful,boolean peacefulAction,
                                                            boolean penalize,UUID source,String kind,long now) {
                return plugin.peace().attack(attacker,victim,launchedPeaceful,peacefulAction,penalize,source,kind,now);
            }
            public void quit(UUID owner){plugin.peace().quit(owner);}
        },plugin.getName().toLowerCase(java.util.Locale.ROOT),System::currentTimeMillis);
    }
    /** Подменяется только внешний адаптер, production-маркировка/маршрутизация остаётся настоящей. */
    GuildPeaceListener(Access access,String namespace,java.util.function.LongSupplier clock) {
        this.access=access;this.clock=clock;
        ownerKey=new NamespacedKey(namespace,"pvp_owner");peacefulKey=new NamespacedKey(namespace,"peaceful_launch");
        sourceKey=new NamespacedKey(namespace,"pvp_action");usedKey=new NamespacedKey(namespace,"pvp_accounted");
        createdKey=new NamespacedKey(namespace,"pvp_action_at");
    }
    private final java.util.function.LongSupplier clock;
    private boolean enabled(){return access.enabled();}
    private UUID owner(Entity entity,int depth) {
        if(entity==null || depth>5)return null;
        if(entity instanceof Player player)return access.authenticated(player)&&!player.hasMetadata("NPC")?player.getUniqueId():null;
        String marked=entity.getPersistentDataContainer().get(ownerKey,PersistentDataType.STRING);
        if(marked!=null)try{return UUID.fromString(marked);}catch(IllegalArgumentException ignored){return null;}
        if(entity instanceof Projectile shot && shot.getShooter() instanceof Entity source)return owner(source,depth+1);
        if(entity instanceof TNTPrimed tnt)return owner(tnt.getSource(),depth+1);
        if(entity instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Entity source)return owner(source,depth+1);
        if(entity instanceof EvokerFangs fangs)return owner(fangs.getOwner(),depth+1);
        if(entity instanceof Tameable pet && pet.getOwner()!=null)return pet.getOwner().getUniqueId();
        return null;
    }
    private boolean launchedPeaceful(Entity entity){return Byte.valueOf((byte)1).equals(entity.getPersistentDataContainer().get(peacefulKey,PersistentDataType.BYTE));}
    void mark(Entity entity,UUID owner) {
        var data=entity.getPersistentDataContainer();
        // Повторная активация кристалла неизвестным источником не наследует старого виновника.
        for(var key:java.util.List.of(ownerKey,peacefulKey,sourceKey,usedKey,createdKey))data.remove(key);
        if(owner==null)return;
        data.set(ownerKey,PersistentDataType.STRING,owner.toString());
        // Ноль тоже важен: старый/неизвестный источник нельзя обвинить задним числом.
        data.set(peacefulKey,PersistentDataType.BYTE,(byte)(access.peaceful(owner)?1:0));
        data.set(sourceKey,PersistentDataType.STRING,UUID.randomUUID().toString());
        data.remove(usedKey);
        data.set(createdKey,PersistentDataType.LONG,clock.getAsLong());
    }
    void markFrom(Entity child,Entity parent) {
        if(parent==null)return;
        mark(child,owner(parent,0));
        var from=parent.getPersistentDataContainer();var to=child.getPersistentDataContainer();
        Long created=from.get(createdKey,PersistentDataType.LONG);
        if(created!=null)to.set(createdKey,PersistentDataType.LONG,created);
        String source=from.get(sourceKey,PersistentDataType.STRING);
        if(source!=null)to.set(sourceKey,PersistentDataType.STRING,source);
        else if(!(parent instanceof Player)) {
            // Старый снаряд/автоматика питомца не становятся новым доказанным действием через потомка.
            to.remove(sourceKey);to.remove(createdKey);
        }
        Byte peaceful=from.get(peacefulKey,PersistentDataType.BYTE);
        if(peaceful!=null)to.set(peacefulKey,PersistentDataType.BYTE,peaceful);
        if(from.has(usedKey,PersistentDataType.BYTE))to.set(usedKey,PersistentDataType.BYTE,(byte)1);
    }
    private UUID source(Entity entity) {
        String value=entity.getPersistentDataContainer().get(sourceKey,PersistentDataType.STRING);
        if(value!=null)try{return UUID.fromString(value);}catch(IllegalArgumentException ignored){return null;}
        return null;
    }
    /** Автоматика питомцев и неизвестные старые источники блокируются, но не обвиняют владельца. */
    boolean attack(Entity entity,Player victim,String kind) {
        if(!access.authenticated(victim)||victim.isDead())return false;
        boolean direct=entity instanceof Player;
        UUID attacker=owner(entity,0),action=direct?UUID.randomUUID():source(entity);
        if(attacker==null)return false;
        long now=clock.getAsLong();
        Long created=entity.getPersistentDataContainer().get(createdKey,PersistentDataType.LONG);
        // Доказательства в домене живут 30 суток: древний снаряд после их очистки не обвиняет повторно.
        boolean known=direct||(action!=null&&created!=null&&created>=0&&created<=now&&now-created<GuildSanction.WINDOW);
        boolean penalize=known&&!(entity instanceof Tameable)
                && !entity.getPersistentDataContainer().has(usedKey,PersistentDataType.BYTE)
                && victim.getWorld().getPVP()&&!victim.isInvulnerable()
                && victim.getGameMode()!=GameMode.CREATIVE&&victim.getGameMode()!=GameMode.SPECTATOR;
        boolean peacefulAction=direct?access.peaceful(attacker):launchedPeaceful(entity);
        var result=access.attack(attacker,victim.getUniqueId(),launchedPeaceful(entity),peacefulAction,
                penalize,action==null?entity.getUniqueId():action,kind,now);
        if(!direct&&result.accounted())entity.getPersistentDataContainer().set(usedKey,PersistentDataType.BYTE,(byte)1);
        return result.blocked();
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void tntSpawn(EntitySpawnEvent event) {
        if(enabled()&&event.getEntity() instanceof TNTPrimed tnt)markFrom(tnt,tnt.getSource());
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void launch(ProjectileLaunchEvent event) {
        if(!enabled())return;
        var shot=event.getEntity();
        if(shot.getShooter() instanceof Player player)mark(shot,owner(player,0));
        else if(shot.getShooter() instanceof Entity parent)markFrom(shot,parent);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void primeCrystal(EntityDamageByEntityEvent event) {
        // Виновник — доказанный инициатор взрыва, а не тот, кто когда-то поставил кристалл.
        if(enabled()&&event.getEntity() instanceof EnderCrystal)markFrom(event.getEntity(),event.getDamager());
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void damage(EntityDamageByEntityEvent event) {
        if(!enabled() || !(event.getEntity() instanceof Player victim) || event.getDamage()<=0)return;
        Entity source=event.getDamager();
        String kind=source instanceof Projectile?"projectile":source instanceof TNTPrimed||source instanceof EnderCrystal?"explosion":source instanceof EvokerFangs?"fangs":"melee";
        if(attack(source,victim,kind))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void splash(PotionSplashEvent event) {
        if(!enabled()||event.getPotion().getEffects().stream().noneMatch(e->HARMFUL.contains(e.getType().getKey().getKey())))return;
        for(LivingEntity entity:event.getAffectedEntities())if(entity instanceof Player victim && event.getIntensity(victim)>0
                &&attack(event.getPotion(),victim,"potion"))event.setIntensity(victim,0);
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
        event.getAffectedEntities().removeIf(entity->entity instanceof Player victim&&attack(cloud,victim,"potion"));
    }
    @EventHandler public void quit(PlayerQuitEvent event){access.quit(event.getPlayer().getUniqueId());}
}
