package ru.cherdakmd.chrdkclasses;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import java.util.*;

/** Только подтверждённые естественные враги и реальный вклад. Никакого XP за AFK-пульс. */
final class PveProgressListener implements Listener {
    private final ClassesPlugin plugin;
    private final NamespacedKey natural, rewarded, projectile;
    private record Hit(UUID player, double damage, long at, boolean ranged) { }
    private record Shield(double amount, long until) { }
    private final Map<UUID,Hit> hits = new LinkedHashMap<>();
    private final PveContributionLedger ledger = new PveContributionLedger();
    private final Map<UUID,Shield> shields = new HashMap<>();
    private final Map<UUID,Long> pvp = new LinkedHashMap<>();
    PveProgressListener(ClassesPlugin plugin) {
        this.plugin=plugin;
        natural=new NamespacedKey(plugin,"natural-v1"); rewarded=new NamespacedKey(plugin,"rewarded-v1");
        projectile=new NamespacedKey(plugin,"ability-projectile");
    }
    boolean honest(Player p) {
        return p != null && p.isOnline() && !p.isDead() && (p.getGameMode()==GameMode.SURVIVAL || p.getGameMode()==GameMode.ADVENTURE)
                && plugin.coreApi() != null && plugin.coreApi().isAuthenticated(p) && plugin.config().enabled();
    }
    boolean inPvp(UUID id) { return System.currentTimeMillis()-pvp.getOrDefault(id,0L)<15_000; }
    boolean supportAllowed(Player p) { return honest(p) && !inPvp(p.getUniqueId()); }
    boolean context(Player p) {
        if (debt(p)>0) return true;
        return p.getNearbyEntities(8,5,8).stream().anyMatch(e -> e instanceof Enemy && e instanceof LivingEntity living && !living.isDead());
    }
    void tag(Arrow arrow, Player owner) {
        arrow.getPersistentDataContainer().set(projectile,PersistentDataType.STRING,owner.getUniqueId().toString());
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
    }
    private boolean natural(LivingEntity e) {
        return e instanceof Enemy && e.getPersistentDataContainer().has(natural,PersistentDataType.BYTE);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void spawn(CreatureSpawnEvent event) {
        if (event.getEntity() instanceof Enemy && (event.getSpawnReason()==CreatureSpawnEvent.SpawnReason.NATURAL
                || event.getSpawnReason()==CreatureSpawnEvent.SpawnReason.PATROL))
            event.getEntity().getPersistentDataContainer().set(natural,PersistentDataType.BYTE,(byte)1);
    }
    private Player attacker(Entity entity) {
        if (entity instanceof Player p) return p;
        if (entity instanceof Projectile shot && shot.getShooter() instanceof Player p) return p;
        return null;
    }
    private LivingEntity mobAttacker(Entity entity) {
        if (entity instanceof LivingEntity living && living instanceof Enemy) return living;
        if (entity instanceof Projectile shot && shot.getShooter() instanceof LivingEntity living && living instanceof Enemy) return living;
        return null;
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void protect(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Projectile shot && shot.getPersistentDataContainer().has(projectile,PersistentDataType.STRING)) {
            String owner=shot.getPersistentDataContainer().get(projectile,PersistentDataType.STRING);
            if (!(shot.getShooter() instanceof Player shooter) || !shooter.getUniqueId().toString().equals(owner)
                    || !honest(shooter) || inPvp(shooter.getUniqueId()) || !(event.getEntity() instanceof Enemy)) {
                event.setCancelled(true); shot.remove(); return;
            }
        }
        Player attacker = attacker(event.getDamager());
        if (event.getEntity() instanceof Player victim && attacker != null) {
            long now=System.currentTimeMillis();
            pvp.put(victim.getUniqueId(),now); pvp.put(attacker.getUniqueId(),now);
            shields.remove(victim.getUniqueId()); shields.remove(attacker.getUniqueId());
            ledger.forgetWound(victim.getUniqueId()); ledger.forgetWound(attacker.getUniqueId());
            if(pvp.size()>8192) pvp.remove(pvp.keySet().iterator().next());
        }
        if (event.getEntity() instanceof Player victim && mobAttacker(event.getDamager()) != null) {
            Shield shield=shields.get(victim.getUniqueId());
            if(shield!=null && shield.until()>System.currentTimeMillis()) {
                double used=Math.min(event.getDamage(),shield.amount());
                event.setDamage(Math.max(0,event.getDamage()-used));
                if(shield.amount()<=used) shields.remove(victim.getUniqueId());
                else shields.put(victim.getUniqueId(),new Shield(shield.amount()-used,shield.until()));
            }
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void damage(EntityDamageByEntityEvent event) {
        if(event.getFinalDamage()<=0) return;
        long now=System.currentTimeMillis();
        if(event.getEntity() instanceof LivingEntity enemy && natural(enemy)) {
            Player p=attacker(event.getDamager());
            if(honest(p) && !inPvp(p.getUniqueId())) {
                Hit old=hits.get(enemy.getUniqueId());
                double contribution=Math.min(event.getFinalDamage(),enemy.getHealth());
                if(old!=null && old.player().equals(p.getUniqueId()) && now-old.at()<30_000) contribution+=old.damage();
                hits.put(enemy.getUniqueId(),new Hit(p.getUniqueId(),contribution,now,event.getDamager() instanceof Projectile));
                if(hits.size()>8192) hits.remove(hits.keySet().iterator().next());
            }
        }
        if(event.getEntity() instanceof Player p) {
            LivingEntity mob=mobAttacker(event.getDamager());
            if(mob!=null && natural(mob) && honest(p) && !inPvp(p.getUniqueId()))
                ledger.hurt(p.getUniqueId(),Math.min(event.getFinalDamage(),p.getHealth()),now);
            else ledger.forgetWound(p.getUniqueId());
        }
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void death(EntityDeathEvent event) {
        LivingEntity mob=event.getEntity();
        if(mob instanceof Player p) {ledger.forgetWound(p.getUniqueId());shields.remove(p.getUniqueId());}
        Hit hit=hits.remove(mob.getUniqueId());
        if(!natural(mob) || hit==null || System.currentTimeMillis()-hit.at()>30_000
                || mob.getPersistentDataContainer().has(rewarded,PersistentDataType.BYTE)) return;
        Player p=Bukkit.getPlayer(hit.player());
        double max=mob.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)==null ? 20 : mob.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
        if(!honest(p) || inPvp(hit.player()) || hit.damage()<Math.min(20,max*.2) || !plugin.ready(p)) return;
        PlayerClass role=plugin.service().classOf(hit.player());
        if(role==null || (role==PlayerClass.ARCHER && !hit.ranged()) || (role==PlayerClass.WARRIOR && hit.ranged())) return;
        mob.getPersistentDataContainer().set(rewarded,PersistentDataType.BYTE,(byte)1);
        award(p,ClassDevelopment.Source.COMBAT,25,1);
    }
    private double debt(Player p) {
        return ledger.debt(p.getUniqueId(),System.currentTimeMillis());
    }
    void healed(Player caster,Player target,double actual) {
        if(!honest(caster) || !supportAllowed(target) || caster.equals(target) || actual<=0
                || plugin.service().classOf(caster.getUniqueId())!=PlayerClass.CLERIC) return;
        double useful=ledger.healed(target.getUniqueId(),actual,System.currentTimeMillis());
        if(useful<.5) return;
        award(caster,ClassDevelopment.Source.SUPPORT,Math.min(30,(int)Math.floor(useful*5)),Math.min(3,Math.max(1,(int)useful)));
    }
    private void award(Player p, ClassDevelopment.Source source, int xp, int units) {
        if(!plugin.ready(p) || plugin.store().build(p.getUniqueId())==null) return;
        long now=System.currentTimeMillis();
        int accepted=ledger.accept(p.getUniqueId(),xp,now);
        if(accepted<=0)return;
        plugin.store().awardClassXp(p.getUniqueId(),source,accepted);
        plugin.store().advanceMilestone(p.getUniqueId(),plugin.store().classOf(p.getUniqueId()),units);
        plugin.requestSave();
    }
    void ward(Player target,double amount,int seconds) {
        if(supportAllowed(target)) shields.put(target.getUniqueId(),new Shield(Math.min(4,amount),System.currentTimeMillis()+seconds*1000L));
    }
    void clear(UUID id) { shields.remove(id); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void environmental(org.bukkit.event.entity.EntityDamageEvent event) {
        if(!(event instanceof EntityDamageByEntityEvent) && event.getEntity() instanceof Player p && event.getFinalDamage()>0)
            ledger.forgetWound(p.getUniqueId());
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void regain(EntityRegainHealthEvent event) {
        if(event.getEntity() instanceof Player p) ledger.healed(p.getUniqueId(),event.getAmount(),System.currentTimeMillis());
    }
    @EventHandler public void quit(PlayerQuitEvent e) {
        ledger.forgetWound(e.getPlayer().getUniqueId()); shields.remove(e.getPlayer().getUniqueId());
        // Недавний PvP не очищается релогом; таблица ограничена по размеру.
    }
}
