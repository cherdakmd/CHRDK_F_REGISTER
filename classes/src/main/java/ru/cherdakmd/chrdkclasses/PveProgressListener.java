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
    private record Debt(double amount, long at) { }
    private record Shield(double amount, long until) { }
    private record Rate(long since, int amount) { }
    private final Map<UUID,Hit> hits = new LinkedHashMap<>();
    private final Map<UUID,Debt> debts = new HashMap<>();
    private final Map<UUID,Shield> shields = new HashMap<>();
    private final Map<UUID,Long> pvp = new LinkedHashMap<>();
    private final Map<UUID,Rate> rates = new HashMap<>();
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
    void tag(Arrow arrow) {
        arrow.getPersistentDataContainer().set(projectile,PersistentDataType.BYTE,(byte)1);
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
        if (event.getDamager() instanceof Projectile shot && shot.getPersistentDataContainer().has(projectile,PersistentDataType.BYTE)
                && !(event.getEntity() instanceof Enemy)) {
            event.setCancelled(true); shot.remove(); return;
        }
        Player attacker = attacker(event.getDamager());
        if (event.getEntity() instanceof Player victim && attacker != null) {
            long now=System.currentTimeMillis();
            pvp.put(victim.getUniqueId(),now); pvp.put(attacker.getUniqueId(),now);
            shields.remove(victim.getUniqueId()); shields.remove(attacker.getUniqueId());
            debts.remove(victim.getUniqueId()); debts.remove(attacker.getUniqueId());
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
                debts.put(p.getUniqueId(),new Debt(Math.min(20,debt(p)+Math.min(event.getFinalDamage(),p.getHealth())),now));
        }
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void death(EntityDeathEvent event) {
        LivingEntity mob=event.getEntity();
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
        Debt d=debts.get(p.getUniqueId());
        return d==null || System.currentTimeMillis()-d.at()>60_000 ? 0 : d.amount();
    }
    void healed(Player caster,Player target,double actual) {
        if(!honest(caster) || !supportAllowed(target) || caster.equals(target) || actual<=0
                || plugin.service().classOf(caster.getUniqueId())!=PlayerClass.CLERIC) return;
        double useful=Math.min(actual,debt(target));
        if(useful<.5) return;
        debts.put(target.getUniqueId(),new Debt(Math.max(0,debt(target)-useful),System.currentTimeMillis()));
        award(caster,ClassDevelopment.Source.SUPPORT,Math.min(30,(int)Math.floor(useful*5)),Math.min(3,Math.max(1,(int)useful)));
    }
    private void award(Player p, ClassDevelopment.Source source, int xp, int units) {
        if(!plugin.ready(p) || plugin.store().build(p.getUniqueId())==null) return;
        long now=System.currentTimeMillis();
        Rate rate=rates.get(p.getUniqueId());
        if(rate==null || now-rate.since()>=60_000) rate=new Rate(now,0);
        int accepted=Math.min(xp,500-rate.amount());
        if(accepted<=0) return;
        rates.put(p.getUniqueId(),new Rate(rate.since(),rate.amount()+accepted));
        plugin.store().awardClassXp(p.getUniqueId(),source,accepted);
        plugin.store().advanceMilestone(p.getUniqueId(),plugin.store().classOf(p.getUniqueId()),units);
        plugin.requestSave();
    }
    void ward(Player target,double amount,int seconds) {
        if(supportAllowed(target)) shields.put(target.getUniqueId(),new Shield(Math.min(4,amount),System.currentTimeMillis()+seconds*1000L));
    }
    void clear(UUID id) { shields.remove(id); }
    @EventHandler public void quit(PlayerQuitEvent e) {
        debts.remove(e.getPlayer().getUniqueId()); shields.remove(e.getPlayer().getUniqueId()); rates.remove(e.getPlayer().getUniqueId());
        // Недавний PvP не очищается релогом; таблица ограничена по размеру.
    }
}
