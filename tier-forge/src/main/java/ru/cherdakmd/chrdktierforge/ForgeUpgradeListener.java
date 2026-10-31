package ru.cherdakmd.chrdktierforge;

import ru.cherdakmd.chrdkregister.api.PassivePotions;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Trident;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Wither;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Событийная половина кузнечных свойств: бой, добыча, прочность, пассивный пульс
 * и «привязка души». Атрибутная половина живёт в {@link TierItems}. Уровни
 * читаются из PDC предмета в руке/на теле; снаряд хранит снимок бонуса выстрела.
 * Снимок инструмента добычи живёт не дольше следующего тика.
 * С 1.4.91 есть кэш «есть ли ковка в экипировке» для пульса
 * ({@link ForgeGearCache}) плюс кулдауны по игрокам и очередь воскрешения.
 */
public final class ForgeUpgradeListener implements Listener {

    /** Потолки суммарных шансов/срезов со всех вещей — защита от абсолюта. */
    static final double CAP_CRIT = 0.60;
    static final double CAP_THORNS = 0.40;
    static final double CAP_RESILIENCE = 0.30;
    static final double CAP_ORE_DOUBLE = 0.60;
    static final double CAP_PARRY = 0.25;
    static final double CAP_RAGE = 0.30;
    static final double CAP_PROC = 0.75; // поджог/замедление/яд/ослепление/ловец
    /** Регенерация пульсом: не больше этого за раз (и не мертвецам). */
    static final double CAP_REGEN_HEAL = 1.0;
    /** Адреналин/рубеж: кулдауны. */
    static final long ADRENALINE_CD_MS = 10_000L;
    static final long LASTSTAND_CD_MS = 60_000L;

    private final TierForgePlugin plugin;
    private final NamespacedKey shotBonusKey;
    private final Random random = new Random();
    private record BreakSnapshot(BlockBreakEvent source, Material type,
                                 Map<ForgeUpgrade.Def, Integer> effects, ItemStack identity) { }
    private final ForgeBreakCache<BreakSnapshot> breakCache = new ForgeBreakCache<>(4096);
    private boolean breakCleanupQueued;
    private final Map<UUID, Long> adrenalineCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastStandCooldown = new ConcurrentHashMap<>();
    /** Где умер владелец — туда вернём вещи, если он выйдет, не дождавшись респауна (1.4.86). */
    /**
     * Флаг реентера внутри «Забойщика»: ломаемые нами блоки не рождают новых залпов.
     * ThreadLocal, а не общий boolean: на Folia события разных игроков идут в разных
     * региональных потоках, и общий флаг одной ветки глушил бы чужую (1.4.82).
     */
    private final ThreadLocal<Boolean> zoneBreaking = ThreadLocal.withInitial(() -> Boolean.FALSE);
    /** Последнее боевое действие игрока (дал или получил урон) — для «Ветра». */
    private final Map<UUID, Long> lastCombat = new ConcurrentHashMap<>();
    /** Флаг реентера внутри «Медовника»/«Стойкого»; ThreadLocal — по той же причине. */
    private final ThreadLocal<Boolean> effectShifting = ThreadLocal.withInitial(() -> Boolean.FALSE);
    /** 1.4.91: не сканировать PDC экипировки у тех, у кого ковки заведомо нет. */
    private static final long GEAR_CACHE_TTL_MS = 30_000L;
    private final ForgeGearCache gearCache = new ForgeGearCache(GEAR_CACHE_TTL_MS);

    public ForgeUpgradeListener(TierForgePlugin plugin) {
        this.plugin = plugin;
        this.shotBonusKey = new NamespacedKey(plugin, "shot_bonus_v1");
    }

    /** Reload должен сразу забыть отрицательный ответ, полученный при выключенных свойствах. */
    void invalidateGearCache() {
        gearCache.clear();
    }

    void invalidateGearCache(UUID player) {
        gearCache.invalidate(player);
    }

    /* ------------------------------- атака игрока ------------------------------- */

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerHit(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Projectile shot
                && shot.getShooter() instanceof Player shooter) {
            TierShotBonus bonus = shotBonus(shot);
            if (!bonus.equals(TierShotBonus.NONE)) {
                lastCombat.put(shooter.getUniqueId(), System.currentTimeMillis());
                event.setDamage(bonus.apply(event.getDamage()));
            }
            return;
        }
        if (!(event.getDamager() instanceof Player attacker)) return;
        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        Map<ForgeUpgrade.Def, Integer> ups = plugin.items().effectsOf(weapon);
        if (ups.isEmpty()) return;
        lastCombat.put(attacker.getUniqueId(), System.currentTimeMillis());

        double damage = event.getDamage();

        // Крит: шанс — уровни ×3%, удар ×1.5
        int crit = levelOf(ups, ForgeUpgrade.Effect.CRIT_CHANCE);
        if (crit > 0 && random.nextDouble()
                < Math.min(CAP_CRIT, crit * fraction(ups, ForgeUpgrade.Effect.CRIT_CHANCE))) {
            damage *= 1.5;
        }
        if (event.getEntity() instanceof LivingEntity target) {
            // Внезапный: +% по цели с полным здоровьем
            int ambush = levelOf(ups, ForgeUpgrade.Effect.FIRST_STRIKE);
            if (ambush > 0 && target.getHealth() >= maxHealth(target) * 0.999) {
                damage *= 1.0 + ambush * fraction(ups, ForgeUpgrade.Effect.FIRST_STRIKE);
            }
            // Добивание: цель ниже 30% здоровья → +% за уровень
            int execute = levelOf(ups, ForgeUpgrade.Effect.EXECUTE);
            if (execute > 0 && target.getHealth() > 0
                    && target.getHealth() < 0.3 * maxHealth(target)) {
                damage *= 1.0 + execute * fraction(ups, ForgeUpgrade.Effect.EXECUTE);
            }
            // Ломатель: +% по бронированным (≥10 ед. брони — разбойники и тяжёлая техника)
            int pierce = levelOf(ups, ForgeUpgrade.Effect.ARMOR_PIERCE);
            if (pierce > 0 && armorValue(target) >= 10.0) {
                damage *= 1.0 + pierce * fraction(ups, ForgeUpgrade.Effect.ARMOR_PIERCE);
            }
            // Кара: нежить получает +% за уровень
            int smite = levelOf(ups, ForgeUpgrade.Effect.SMITE);
            if (smite > 0 && isUndead(target)) {
                damage *= 1.0 + smite * fraction(ups, ForgeUpgrade.Effect.SMITE);
            }
            // Калечащий: +% по целям, уже страдающим от злых чар
            int crippler = levelOf(ups, ForgeUpgrade.Effect.DEBUFF_DAMAGE);
            if (crippler > 0 && hasHarmfulEffect(target)) {
                damage *= 1.0 + crippler * fraction(ups, ForgeUpgrade.Effect.DEBUFF_DAMAGE);
            }
            // Дуэлянт: +% по игрокам
            int duelist = levelOf(ups, ForgeUpgrade.Effect.PVP_DAMAGE);
            if (duelist > 0 && target instanceof Player) {
                damage *= 1.0 + duelist * fraction(ups, ForgeUpgrade.Effect.PVP_DAMAGE);
            }
            // Зверолов: +% по животным
            int tamer = levelOf(ups, ForgeUpgrade.Effect.ANIMAL_DAMAGE);
            if (tamer > 0 && target instanceof Animals) {
                damage *= 1.0 + tamer * fraction(ups, ForgeUpgrade.Effect.ANIMAL_DAMAGE);
            }
            // Ярость: +0.75%/ур за каждое потерянное сердце нападающего (потолок 30%)
            int rage = levelOf(ups, ForgeUpgrade.Effect.RAGE);
            if (rage > 0 && !attacker.isDead()) {
                double missingHearts = Math.max(0.0, (maxHealth(attacker) - attacker.getHealth()) / 2.0);
                double bonus = Math.min(CAP_RAGE,
                        missingHearts * rage * fraction(ups, ForgeUpgrade.Effect.RAGE));
                damage *= 1.0 + bonus;
            }
            // Громовержец: прок грозы — плоский дозаряд и раскат над целью
            int storm = levelOf(ups, ForgeUpgrade.Effect.THUNDER);
            if (storm > 0 && random.nextDouble()
                    < Math.min(CAP_PROC, storm * fraction(ups, ForgeUpgrade.Effect.THUNDER))) {
                damage += storm * 1.0; // +0.5❤ за уровень поверх расчётного удара
                target.getWorld().playSound(target.getLocation(),
                        Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8f, 1.2f);
            }
        }
        event.setDamage(damage);

        if (event.getEntity() instanceof LivingEntity target) {
            // Проки: поджог/замедление/яд/ослепление — длительность растёт с уровнем
            procPotion(ups, ForgeUpgrade.Effect.IGNITE, attacker, target);
            procPotion(ups, ForgeUpgrade.Effect.SLOW_HIT, attacker, target);
            procPotion(ups, ForgeUpgrade.Effect.POISON_HIT, attacker, target);
            procPotion(ups, ForgeUpgrade.Effect.BLIND_HIT, attacker, target);
            // Проки третьей ковки
            procPotion(ups, ForgeUpgrade.Effect.WITHER_HEX, attacker, target);
            procFreeze(ups, attacker, target);
            procCleanse(ups, attacker, target);
            procHook(ups, attacker, target);
        }

        // Кровопийца: % от финального урона назад в здоровье
        int lifesteal = levelOf(ups, ForgeUpgrade.Effect.LIFESTEAL);
        if (lifesteal > 0) {
            double healed = event.getFinalDamage() * lifesteal * fraction(ups, ForgeUpgrade.Effect.LIFESTEAL);
            heal(attacker, healed);
        }
    }

    /** Общий прок по оружию: шанс (ур × perLevel, потолок 75%) → эффект на цель. */
    private void procPotion(Map<ForgeUpgrade.Def, Integer> ups, ForgeUpgrade.Effect effect,
                            Player attacker, LivingEntity target) {
        int level = levelOf(ups, effect);
        if (level <= 0) {
            return;
        }
        double chance = Math.min(CAP_PROC, level * fraction(ups, effect));
        if (random.nextDouble() >= chance) {
            return;
        }
        int durationTicks = 20 * (1 + level);
        switch (effect) {
            case IGNITE -> target.setFireTicks(Math.max(target.getFireTicks(), durationTicks));
            case SLOW_HIT -> applyFresh(target, PotionEffectType.SLOWNESS, durationTicks, 0);
            case POISON_HIT -> applyFresh(target, PotionEffectType.POISON, durationTicks, 0);
            case BLIND_HIT -> applyFresh(target, PotionEffectType.BLINDNESS, durationTicks, 0);
            case WITHER_HEX -> applyFresh(target, PotionEffectType.WITHER, durationTicks, 0);
            default -> { }
        }
    }

    /** Морозильник: прок заморозки — весь уровень уходит в тики холода. */
    private void procFreeze(Map<ForgeUpgrade.Def, Integer> ups, Player attacker, LivingEntity target) {
        int level = levelOf(ups, ForgeUpgrade.Effect.FREEZE_HIT);
        if (level <= 0 || random.nextDouble()
                >= Math.min(CAP_PROC, level * fraction(ups, ForgeUpgrade.Effect.FREEZE_HIT))) {
            return;
        }
        target.setFreezeTicks(Math.max(target.getFreezeTicks(), 40 * level));
    }

    /** Гаситель: прок снимает с цели один случайный положительный эффект. */
    private void procCleanse(Map<ForgeUpgrade.Def, Integer> ups, Player attacker, LivingEntity target) {
        int level = levelOf(ups, ForgeUpgrade.Effect.CLEANSE_HIT);
        if (level <= 0 || random.nextDouble()
                >= Math.min(CAP_PROC, level * fraction(ups, ForgeUpgrade.Effect.CLEANSE_HIT))) {
            return;
        }
        java.util.List<PotionEffect> boons = new ArrayList<>();
        for (PotionEffect active : target.getActivePotionEffects()) {
            if (active.getType().getEffectCategory() == PotionEffectType.Category.BENEFICIAL) {
                boons.add(active);
            }
        }
        if (!boons.isEmpty()) {
            target.removePotionEffect(boons.get(random.nextInt(boons.size())).getType());
        }
    }

    /** Крюк: прок подтягивает цель к нападающему. */
    private void procHook(Map<ForgeUpgrade.Def, Integer> ups, Player attacker, LivingEntity target) {
        int level = levelOf(ups, ForgeUpgrade.Effect.HOOK);
        if (level <= 0 || random.nextDouble()
                >= Math.min(CAP_PROC, level * fraction(ups, ForgeUpgrade.Effect.HOOK))) {
            return;
        }
        org.bukkit.util.Vector pull = attacker.getLocation().toVector()
                .subtract(target.getLocation().toVector()).normalize().multiply(0.6);
        target.setVelocity(target.getVelocity().add(pull));
    }

    /** Есть ли на цели хотя бы один негативный эффект (для «Калечащего»). */
    private static boolean hasHarmfulEffect(LivingEntity target) {
        for (PotionEffect active : target.getActivePotionEffects()) {
            if (active.getType().getEffectCategory() == PotionEffectType.Category.HARMFUL) {
                return true;
            }
        }
        return false;
    }

    /** Не накладываем эффект поверх более сильного действующего. */
    static void applyFresh(LivingEntity target, PotionEffectType type, int ticks, int amplifier) {
        PotionEffect current = target.getPotionEffect(type);
        if (current != null && !ForgeWorkMath.refreshPotion(current.getAmplifier(),
                current.getDuration(), amplifier, ticks)) {
            return;
        }
        target.addPotionEffect(new PotionEffect(type, ticks, amplifier, false, true, true));
    }

    /** Рука выстрела берётся из события, поэтому вторая рука и залп арбалета тоже корректны. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBowShot(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player && event.getProjectile() instanceof Projectile shot) {
            shot.getPersistentDataContainer().set(shotBonusKey, PersistentDataType.STRING,
                    bonusOf(event.getBow()).encode());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTridentLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof Trident trident && trident.getShooter() instanceof Player) {
            trident.getPersistentDataContainer().set(shotBonusKey, PersistentDataType.STRING,
                    bonusOf(trident.getItemStack()).encode());
        }
    }

    private TierShotBonus bonusOf(ItemStack weapon) {
        if (weapon == null || (weapon.getType() != Material.BOW
                && weapon.getType() != Material.CROSSBOW && weapon.getType() != Material.TRIDENT)) {
            return TierShotBonus.NONE;
        }
        TierDef.Snapshot tier = rangedTier(weapon);
        return TierShotBonus.capture(tier == null ? 0 : tier.percent(), plugin.items().effectsOf(weapon));
    }

    private TierShotBonus shotBonus(Projectile shot) {
        String saved = shot.getPersistentDataContainer().get(shotBonusKey, PersistentDataType.STRING);
        if (saved != null) return TierShotBonus.decode(saved);
        // Старые снаряды: только собственное сохранённое оружие, никогда текущая рука игрока.
        if (shot instanceof Trident trident) return bonusOf(trident.getItemStack());
        if (shot instanceof AbstractArrow arrow) return bonusOf(arrow.getWeapon());
        return TierShotBonus.NONE;
    }

    /**
     * Тир оружия, которым выпущен снаряд. Урон стрел идёт мимо атрибута
     * ATTACK_DAMAGE, поэтому бонус тира считается здесь. Смотрим только на
     * действительно стрелковое/метательное оружие: меч в руке в момент прилёта
     * чужой стрелы бонуса не даёт.
     */
    private TierDef.Snapshot rangedTier(ItemStack weapon) {
        if (weapon == null || weapon.getType().isAir()) {
            return null;
        }
        Material type = weapon.getType();
        if (type != Material.BOW && type != Material.CROSSBOW && type != Material.TRIDENT) {
            return null;
        }
        String tierId = plugin.items().tierOf(weapon);
        return tierId == null ? null : plugin.config().find(tierId);
    }

    /* ------------------------------- игрока бьют ------------------------------- */

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerHurt(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        lastCombat.put(victim.getUniqueId(), System.currentTimeMillis());
        double thornChance = 0;
        double reduction = 0;
        double parryChance = 0;
        double catchChance = 0;
        double aegisChance = 0;
        double wardChance = 0;
        double mirrorChance = 0;
        double explosionDown = 0;
        double magicDown = 0;
        int adrenaline = 0;
        boolean lastStand = false;
        for (ItemStack piece : victim.getInventory().getArmorContents()) {
            Map<ForgeUpgrade.Def, Integer> ups = plugin.items().effectsOf(piece);
            thornChance += sumFraction(ups, ForgeUpgrade.Effect.THORNS);
            reduction += sumFraction(ups, ForgeUpgrade.Effect.DAMAGE_TAKEN_PERCENT);
            parryChance += sumFraction(ups, ForgeUpgrade.Effect.PARRY);
            catchChance += sumFraction(ups, ForgeUpgrade.Effect.CATCHER);
            aegisChance += sumFraction(ups, ForgeUpgrade.Effect.ABSORB_PROC);
            wardChance += sumFraction(ups, ForgeUpgrade.Effect.RESIST_PROC);
            mirrorChance += sumFraction(ups, ForgeUpgrade.Effect.PROJECTILE_REFLECT);
            explosionDown += sumFraction(ups, ForgeUpgrade.Effect.EXPLOSION_DAMAGE_DOWN);
            magicDown += sumFraction(ups, ForgeUpgrade.Effect.MAGIC_DAMAGE_DOWN);
            adrenaline += levelOf(ups, ForgeUpgrade.Effect.ADRENALINE);
            lastStand |= levelOf(ups, ForgeUpgrade.Effect.LAST_STAND) > 0;
        }

        // Зеркало: снаряд уходит обратно в стрелка
        if (mirrorChance > 0 && event.getDamager() instanceof Projectile shot
                && shot.getShooter() instanceof LivingEntity shooter
                && !shooter.equals(victim)
                && random.nextDouble() < Math.min(CAP_PROC, mirrorChance)) {
            event.setCancelled(true);
            shooter.damage(Math.max(1.0, event.getFinalDamage() * 0.5), victim);
            return;
        }

        // Парирование: полностью гасим удар с суммарным шансом (потолок 25%)
        if (parryChance > 0 && random.nextDouble() < Math.min(CAP_PARRY, parryChance)) {
            event.setDamage(0);
            return;
        }

        if (reduction > 0) {
            event.setDamage(event.getDamage() * (1.0 - Math.min(CAP_RESILIENCE, reduction)));
        }
        // Сапёр/Антимаг: срезы по причине урона (потолок 60%)
        switch (event.getCause()) {
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> {
                if (explosionDown > 0) {
                    event.setDamage(event.getDamage() * (1.0 - Math.min(0.6, explosionDown)));
                }
            }
            case MAGIC, DRAGON_BREATH -> {
                if (magicDown > 0) {
                    event.setDamage(event.getDamage() * (1.0 - Math.min(0.6, magicDown)));
                }
            }
            default -> { }
        }
        // Последний рубеж: смертельный удар → 1 HP + Сопротивление II (раз в 60 с).
        // 1.4.98: проверяем ПОСЛЕ парирования и срезов и с учётом поглощения —
        // раньше спасатель срабатывал на удар, который прошёл бы в ноль, сжигал
        // кулдаун и оставлял игрока с 1 HP без причины.
        if (lastStand && SurvivalMath.isLethal(victim.getHealth(),
                victim.getAbsorptionAmount(), event.getFinalDamage())
                && cooldownReady(lastStandCooldown, victim.getUniqueId(), LASTSTAND_CD_MS)) {
            double rescue = Math.min(1.0, maxHealth(victim));
            if (rescue <= 0) {
                return; // вырожденный максимум здоровья: setHealth бросил бы исключение
            }
            event.setDamage(0);
            victim.setHealth(rescue);
            applyFresh(victim, PotionEffectType.RESISTANCE, 100, 1);
            return;
        }

        LivingEntity damager = event.getDamager() instanceof LivingEntity d ? d : null;
        if (damager != null) {
            // Терновник: вернуть четверть полученного урона (килл-кредит — жертве)
            if (thornChance > 0 && random.nextDouble() < Math.min(CAP_THORNS, thornChance)) {
                damager.damage(Math.max(1.0, event.getFinalDamage() * 0.25), victim);
            }
            // Ловец: шанс замедлить нападавшего на 2+ур секунды
            if (catchChance > 0 && random.nextDouble() < Math.min(CAP_PROC, catchChance)) {
                int level = catcherLevel(victim);
                applyFresh(damager, PotionEffectType.SLOWNESS, 20 * (2 + level), 0);
            }
        }
        // Адреналин: HP ниже 30% после удара → Скорость I (раз в 10 с)
        if (adrenaline > 0 && victim.getHealth() > 0
                && victim.getHealth() < 0.3 * maxHealth(victim)
                && cooldownReady(adrenalineCooldown, victim.getUniqueId(), ADRENALINE_CD_MS)) {
            applyFresh(victim, PotionEffectType.SPEED, 20 * (1 + adrenaline), 0);
        }
        // Эгида: шанс Поглощения I на 10 с
        if (aegisChance > 0 && random.nextDouble() < Math.min(CAP_PROC, aegisChance)) {
            applyFresh(victim, PotionEffectType.ABSORPTION, 200, 0);
        }
        // Щитоносец: шанс Сопротивления I на 4 с
        if (wardChance > 0 && random.nextDouble() < Math.min(CAP_PROC, wardChance)) {
            applyFresh(victim, PotionEffectType.RESISTANCE, 80, 0);
        }
    }

    /** Суммарный уровень «Ловца» на броне жертвы (для длительности замедления). */
    private int catcherLevel(Player victim) {
        int total = 0;
        for (ItemStack piece : victim.getInventory().getArmorContents()) {
            total += levelOf(plugin.items().effectsOf(piece), ForgeUpgrade.Effect.CATCHER);
        }
        return total;
    }

    private static boolean cooldownReady(Map<UUID, Long> cools, UUID id, long cooldownMs) {
        long now = System.currentTimeMillis();
        Long until = cools.get(id);
        if (until != null && until > now) {
            return false;
        }
        cools.put(id, now + cooldownMs);
        return true;
    }

    /* ------------------------------- добыча/опыт ------------------------------- */

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        Map<ForgeUpgrade.Def, Integer> ups =
                plugin.items().effectsOf(killer.getInventory().getItemInMainHand());
        int hunter = levelOf(ups, ForgeUpgrade.Effect.HUNTER_XP);
        if (hunter > 0 && event.getDroppedExp() > 0) {
            double bonus = hunter * fraction(ups, ForgeUpgrade.Effect.HUNTER_XP);
            event.setDroppedExp((int) Math.round(event.getDroppedExp() * (1.0 + bonus)));
        }

        // Жатва: шанс дублировать один дроп убитого моба
        int reaper = levelOf(ups, ForgeUpgrade.Effect.LOOT_DOUBLE);
        if (reaper > 0 && !event.getDrops().isEmpty()
                && random.nextDouble() < Math.min(CAP_PROC,
                        reaper * fraction(ups, ForgeUpgrade.Effect.LOOT_DOUBLE))) {
            for (ItemStack drop : event.getDrops()) {
                if (drop != null && !drop.getType().isAir()) {
                    event.getDrops().add(drop.clone());
                    break;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onOreBreak(BlockBreakEvent event) {
        Map<ForgeUpgrade.Def, Integer> ups = plugin.items().effectsOf(
                event.getPlayer().getInventory().getItemInMainHand());
        // Только правка самого события: поздний запрет не оставляет дропа/соседних дыр.
        int geologist = levelOf(ups, ForgeUpgrade.Effect.BLOCK_XP);
        if (geologist > 0 && event.getExpToDrop() > 0) {
            double bonus = geologist * fraction(ups, ForgeUpgrade.Effect.BLOCK_XP);
            event.setExpToDrop(ForgeWorkMath.boostedXp(event.getExpToDrop(), bonus));
        }
    }

    /** Дроп может прийти уже после поломки инструмента: не угадываем источник по новой руке. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void captureBreakTool(BlockBreakEvent event) {
        ForgeBreakCache.Key key = breakKey(event.getPlayer(), event.getBlock());
        // Повторная ломка той же позиции обычным инструментом не наследует старый снимок.
        breakCache.remove(key);
        ItemStack tool = event.getPlayer().getInventory().getItemInMainHand();
        Map<ForgeUpgrade.Def, Integer> effects = plugin.items().effectsOf(tool);
        if (effects.isEmpty()) return;
        ItemStack identity = ItemKind.classify(tool.getType().name()) == ItemKind.PICKAXE
                && ForgeWorkMath.columnSides(sumAbs(effects, ForgeUpgrade.Effect.ZONE_BREAK)) > 0
                ? toolIdentity(tool) : null;
        breakCache.put(key,
                new BreakSnapshot(event, event.getBlock().getType(), Map.copyOf(effects), identity));
        if (!breakCleanupQueued) {
            breakCleanupQueued = true;
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                breakCache.clear(); // в том числе отменённые/no-drop события без BlockDropItemEvent
                breakCleanupQueued = false;
            });
        }
    }

    private static ForgeBreakCache.Key breakKey(Player player, Block block) {
        return new ForgeBreakCache.Key(player.getUniqueId(), block.getWorld().getUID(),
                block.getX(), block.getY(), block.getZ());
    }

    private BreakSnapshot breakSnapshot(BlockDropItemEvent event) {
        BreakSnapshot snapshot = breakCache.get(breakKey(event.getPlayer(), event.getBlock()));
        return snapshot != null && snapshot.type() == event.getBlockState().getType()
                && !snapshot.source().isCancelled() && snapshot.source().isDropItems()
                ? snapshot : null;
    }

    /**
     * Колонна запускается только после настоящей добычи. Следующий тик позволяет
     * увидеть окончательную отмену даже более поздним обработчиком события дропа.
     * Каждый сосед проходит штатный player break: приваты, dropItems, XP и износ.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onColumnDrops(BlockDropItemEvent event) {
        if (zoneBreaking.get() || event.getItems().isEmpty()) return;
        Player player = event.getPlayer();
        BreakSnapshot snapshot = breakSnapshot(event);
        if (snapshot == null || snapshot.identity() == null) return;
        int sides = ForgeWorkMath.columnSides(sumAbs(snapshot.effects(), ForgeUpgrade.Effect.ZONE_BREAK));
        Material type = snapshot.type();
        if (!type.isBlock() || type.getHardness() < 0 || type.getHardness() >= 50) return;
        ItemStack identity = snapshot.identity();
        Block origin = event.getBlock();
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.isCancelled() || event.getItems().isEmpty()
                    || !player.isOnline() || player.isDead()
                    || !player.getWorld().equals(origin.getWorld())
                    || player.getLocation().distanceSquared(origin.getLocation().add(.5, .5, .5)) > 64
                    || (!origin.getType().isAir() && !origin.isLiquid())) return;
            zoneBreaking.set(true);
            try {
                breakColumnSibling(origin, 1, type, identity, player);
                if (sides >= 2) breakColumnSibling(origin, -1, type, identity, player);
            } finally {
                zoneBreaking.remove();
            }
        });
    }

    /** Износ меняется при каждой штатной ломке; остальные свойства должны совпадать. */
    private static ItemStack toolIdentity(ItemStack tool) {
        ItemStack identity = tool.clone();
        ItemMeta meta = identity.getItemMeta();
        if (meta instanceof Damageable damage) {
            damage.setDamage(0);
            identity.setItemMeta(meta);
        }
        return identity;
    }

    private void breakColumnSibling(Block origin, int dy, Material type, ItemStack identity,
                                    Player player) {
        Block sibling = origin.getRelative(0, dy, 0);
        if (sibling.getType() != type) return;
        ItemStack current = player.getInventory().getItemInMainHand();
        if (!identity.isSimilar(toolIdentity(current))) return; // сломался/сменился инструмент
        int sides = ForgeWorkMath.columnSides(sumAbs(plugin.items().effectsOf(current),
                ForgeUpgrade.Effect.ZONE_BREAK));
        if (sides < (dy < 0 ? 2 : 1)) return; // reload мог отключить свойство
        player.breakBlock(sibling);
    }

    /** Все дополнительные Item остаются в отменяемом списке, не спавнятся заранее. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockDrops(BlockDropItemEvent event) {
        BreakSnapshot snapshot = breakSnapshot(event);
        if (snapshot == null || event.getItems().isEmpty()) return;
        Map<ForgeUpgrade.Def, Integer> ups = snapshot.effects();
        int smelter = levelOf(ups, ForgeUpgrade.Effect.SMELT_ORE);
        int prospector = levelOf(ups, ForgeUpgrade.Effect.ORE_DOUBLE);
        int gardener = levelOf(ups, ForgeUpgrade.Effect.CROP_DOUBLE);
        int logger = levelOf(ups, ForgeUpgrade.Effect.LOG_DOUBLE);
        int goldbug = levelOf(ups, ForgeUpgrade.Effect.GOLD_FIND);
        int delver = levelOf(ups, ForgeUpgrade.Effect.DEEP_DOUBLE);
        if (smelter <= 0 && prospector <= 0 && gardener <= 0 && logger <= 0
                && goldbug <= 0 && delver <= 0) return;
        Material blockType = event.getBlockState().getType();
        boolean crop = isCropBlock(blockType);
        boolean log = isLogBlock(blockType);
        boolean deep = delver > 0 && event.getBlock().getY() < 0;
        // Один бросок на блок, как прежде; бонус руды не проходит второе умножение глубиной.
        boolean oreBonus = prospector > 0 && isOre(blockType) && random.nextDouble()
                < Math.min(CAP_ORE_DOUBLE, sumFraction(ups, ForgeUpgrade.Effect.ORE_DOUBLE));
        double smeltChance = sumFraction(ups, ForgeUpgrade.Effect.SMELT_ORE);
        double cropChance = Math.min(CAP_PROC, sumFraction(ups, ForgeUpgrade.Effect.CROP_DOUBLE));
        double logChance = Math.min(CAP_PROC, sumFraction(ups, ForgeUpgrade.Effect.LOG_DOUBLE));
        double deepChance = Math.min(CAP_PROC, sumFraction(ups, ForgeUpgrade.Effect.DEEP_DOUBLE));
        List<Item> additions = new ArrayList<>();
        boolean ordinaryLoot = false;
        for (Item dropEntity : event.getItems()) {
            ItemStack stack = dropEntity.getItemStack();
            // Чужие награды, именованные/PDC-предметы и нестандартные стопки не переписываем.
            if (stack.getType().isAir() || stack.hasItemMeta() || stack.getAmount() <= 0
                    || stack.getAmount() > stack.getMaxStackSize()
                    || !dropEntity.getPersistentDataContainer().isEmpty()) continue;
            ordinaryLoot = true;
            Material result = smeltResultOf(stack.getType());
            if (smelter > 0 && result != null && random.nextDouble() < smeltChance) {
                stack = new ItemStack(result, stack.getAmount());
            }
            // Сам блок руды (Silk Touch/древние обломки) нельзя размножить и поставить вновь.
            boolean resource = !isOre(stack.getType());
            int total = ForgeWorkMath.dropTotal(stack.getAmount(), oreBonus && resource,
                    crop && gardener > 0 && random.nextDouble() < cropChance,
                    log && logger > 0 && random.nextDouble() < logChance,
                    deep && resource && random.nextDouble() < deepChance);
            List<Integer> parts = DropAmounts.split(total, stack.getMaxStackSize());
            ItemStack first = stack.clone();
            first.setAmount(parts.getFirst());
            dropEntity.setItemStack(first);
            for (int index = 1; index < parts.size(); index++) {
                ItemStack extra = stack.clone();
                extra.setAmount(parts.get(index));
                additions.add(detachedDrop(dropEntity.getLocation(), extra, dropEntity));
            }
        }
        if (ordinaryLoot && goldbug > 0 && isStoneForGold(blockType)
                && random.nextDouble() < sumFraction(ups, ForgeUpgrade.Effect.GOLD_FIND)) {
            additions.add(detachedDrop(event.getBlock().getLocation().add(.5, .5, .5),
                    new ItemStack(Material.GOLD_NUGGET), null));
        }
        // Сервер сам добавит сущности в мир, только если окончательное событие разрешено.
        event.getItems().addAll(additions);
    }

    static Item detachedDrop(org.bukkit.Location location, ItemStack stack, Item source) {
        Item extra = location.getWorld().createEntity(location, Item.class);
        extra.setItemStack(stack);
        if (source != null) {
            extra.setVelocity(source.getVelocity());
            extra.setPickupDelay(source.getPickupDelay());
            extra.setOwner(source.getOwner());
            extra.setThrower(source.getThrower());
        }
        return extra;
    }

    /** Культуры, чей дроп считается урожаем (для «Земледела»). */
    static boolean isCropBlock(Material type) {
        if (type == null) {
            return false;
        }
        return switch (type) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS, NETHER_WART, COCOA, MELON, PUMPKIN_STEM,
                    SWEET_BERRY_BUSH -> true;
            default -> false;
        };
    }

    /** Брёвна и стволы (для «Лесника») — по суффиксу имени материала. */
    static boolean isLogBlock(Material type) {
        if (type == null) {
            return false;
        }
        String name = type.name();
        return name.endsWith("_LOG") || name.endsWith("_WOOD")
                || name.endsWith("_STEM") || name.endsWith("_HYPHAE");
    }

    /** Каменные породы, в которых «Златолюб» находит крупицу золота. */
    static boolean isStoneForGold(Material type) {
        if (type == null) {
            return false;
        }
        return switch (type) {
            case STONE, COBBLESTONE, DEEPSLATE, COBBLED_DEEPSLATE, ANDESITE, DIORITE,
                    GRANITE, TUFF -> true;
            default -> false;
        };
    }

    /** Во что переплавить дроп: сырьё/руды → слитки, древние обломки → незерит-обрывок. */
    static Material smeltResultOf(Material drop) {
        if (drop == null) {
            return null;
        }
        return switch (drop) {
            case RAW_IRON, IRON_ORE, DEEPSLATE_IRON_ORE -> Material.IRON_INGOT;
            case RAW_GOLD, GOLD_ORE, DEEPSLATE_GOLD_ORE -> Material.GOLD_INGOT;
            case RAW_COPPER, COPPER_ORE, DEEPSLATE_COPPER_ORE -> Material.COPPER_INGOT;
            case ANCIENT_DEBRIS -> Material.NETHERITE_SCRAP;
            default -> null;
        };
    }

    /* ------------------------------- прочность/опыт-починка ------------------------------- */

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        Map<ForgeUpgrade.Def, Integer> ups = plugin.items().effectsOf(event.getItem());
        int tempered = levelOf(ups, ForgeUpgrade.Effect.DURABILITY_SAVE);
        if (tempered > 0
                && random.nextDouble() < tempered * fraction(ups, ForgeUpgrade.Effect.DURABILITY_SAVE)) {
            event.setCancelled(true); // закалка сработала: прочность не тратится
        }
    }

    /** Самопочинка: каждая единица полученного опыта с шансом ур% точит экипировку. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onExpGain(PlayerExpChangeEvent event) {
        int amount = event.getAmount();
        if (amount <= 0) {
            return;
        }
        // Arrays.asList — armorContents содержит null-ячейки, List.of бросил бы NPE
        java.util.List<ItemStack> pieces = new ArrayList<>(java.util.Arrays.asList(
                event.getPlayer().getInventory().getArmorContents()));
        pieces.add(event.getPlayer().getInventory().getItemInMainHand());
        pieces.add(event.getPlayer().getInventory().getItemInOffHand());
        for (ItemStack piece : pieces) {
            Map<ForgeUpgrade.Def, Integer> ups = plugin.items().effectsOf(piece);
            int selfmend = levelOf(ups, ForgeUpgrade.Effect.XP_REPAIR);
            if (selfmend <= 0 || !(piece.getItemMeta() instanceof Damageable state) || state.getDamage() <= 0) {
                continue;
            }
            double perPoint = selfmend * fraction(ups, ForgeUpgrade.Effect.XP_REPAIR);
            int repairs = ForgeWorkMath.repairs(amount, state.getDamage(), perPoint,
                    random::nextDouble);
            if (repairs > 0) {
                ItemMeta meta = piece.getItemMeta();
                ((Damageable) meta).setDamage(Math.max(0, ((Damageable) meta).getDamage() - repairs));
                piece.setItemMeta(meta);
            }
        }

        // Эрудит: +% к получаемому опыту со всей экипировки
        double scholarBonus = 0;
        for (ItemStack piece : pieces) {
            scholarBonus += sumFraction(plugin.items().effectsOf(piece), ForgeUpgrade.Effect.XP_GAIN);
        }
        if (scholarBonus > 0) {
            event.setAmount(ForgeWorkMath.boostedXp(amount, scholarBonus));
        }
    }

    /** Моряк: шанс вытащить второй трофей тем же забросом. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH
                || !(event.getCaught() instanceof Item caught)) {
            return;
        }
        Map<ForgeUpgrade.Def, Integer> ups =
                plugin.items().effectsOf(event.getPlayer().getInventory().getItemInMainHand());
        int sailor = levelOf(ups, ForgeUpgrade.Effect.FISH_DOUBLE);
        if (sailor <= 0 || random.nextDouble()
                >= Math.min(CAP_PROC, sailor * fraction(ups, ForgeUpgrade.Effect.FISH_DOUBLE))) {
            return;
        }
        caught.getWorld().dropItemNaturally(caught.getLocation(),
                caught.getItemStack().clone());
    }

    /** Медовник/Стойкий: масштабируем длительность новых эффектов на владельце брони. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (effectShifting.get() || !(event.getEntity() instanceof Player player)
                || PassivePotions.isApplying(player)
                || event.getAction() != EntityPotionEffectEvent.Action.ADDED
                || event.getCause() == EntityPotionEffectEvent.Cause.BEACON) {
            return;
        }
        PotionEffect fresh = event.getNewEffect();
        if (fresh == null || fresh.isInfinite()) {
            return;
        }
        double longer = 0;
        double shorter = 0;
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            Map<ForgeUpgrade.Def, Integer> ups = plugin.items().effectsOf(piece);
            longer += sumFraction(ups, ForgeUpgrade.Effect.GOOD_EFFECTS_LONGER);
            shorter += sumFraction(ups, ForgeUpgrade.Effect.BAD_EFFECTS_SHORTER);
        }
        PotionEffectType.Category category = fresh.getType().getEffectCategory();
        double multiplier = switch (category) {
            case BENEFICIAL -> longer > 0 ? 1.0 + longer : 1.0;
            case HARMFUL -> shorter > 0 ? Math.max(0.1, 1.0 - Math.min(CAP_PROC, shorter)) : 1.0;
            case NEUTRAL -> 1.0;
        };
        if (Math.abs(multiplier - 1.0) < 0.001) {
            return;
        }
        event.setCancelled(true); // иначе подадим стоковую длительность
        effectShifting.set(true); // наш же повторный эффект не масштабируем вновь
        try {
            int duration = Math.max(1, (int) Math.round(fresh.getDuration() * multiplier));
            player.addPotionEffect(new PotionEffect(fresh.getType(), duration,
                    fresh.getAmplifier(), fresh.isAmbient(), fresh.hasParticles(), fresh.hasIcon()));
        } finally {
            effectShifting.set(false);
        }
    }

    /* ------------------------------- привязка души ------------------------------- */

    /**
     * Смерть: «привязанные» вещи остаются в инвентаре штатным механизмом Paper
     * (1.4.93). Предмет убирается из {@code getDrops()} и кладётся в
     * {@code getItemsToKeep()} — он сохраняет свой слот (броня остаётся надетой),
     * не дублируется при {@code keepInventory} от другого плагина и не требует
     * аварийной выкладки в мир, если игрок вышел с экрана смерти.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        gearCache.invalidate(event.getEntity().getUniqueId());
        if (event.getKeepInventory()) {
            return; // инвентарь и так сохраняется — второй раз не добавляем
        }
        List<ItemStack> keepsakes = new ArrayList<>();
        event.getDrops().removeIf(drop -> {
            if (drop != null
                    && levelOf(plugin.items().effectsOf(drop), ForgeUpgrade.Effect.SOULBOUND) > 0) {
                keepsakes.add(drop);
                return true;
            }
            return false;
        });
        if (!keepsakes.isEmpty()) {
            event.getItemsToKeep().addAll(keepsakes);
        }
    }

    /* ------------------------------- пассивный пульс ------------------------------- */

    /**
     * Пульс раз в 2 секунды (вызывается таймером плагина): регенерация
     * «Теплокровности», ночное зрение «Светильника», магнит дропа. Расчёты
     * дешёвые — из PDC экипировки онлайн-игроков.
     */
    public void pulse() {
        long now = System.currentTimeMillis();
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            List<PotionEffect> passive = new ArrayList<>();
            if (player.isDead()) {
                PassivePotions.replace(plugin, player, passive, 40);
                continue;
            }
            // 1.4.91: у кого ковки нет — скан PDC не нужен (кэш сбрасывают события
            // инвентаря, TTL 30 с страхует от пропущенного события).
            UUID playerId = player.getUniqueId();
            Boolean forged = gearCache.fresh(playerId, now);
            if (Boolean.FALSE.equals(forged)) {
                PassivePotions.replace(plugin, player, passive, 40);
                continue;
            }
            if (forged == null) {
                boolean hasForge = hasForgeGear(player);
                gearCache.put(playerId, hasForge, now);
                if (!hasForge) {
                    PassivePotions.replace(plugin, player, passive, 40);
                    continue;
                }
            }
            double regen = 0;
            double magnetBonus = 0;
            boolean nightVision = false;
            boolean noFreeze = false;
            double satiate = 0;
            int haste = 0;
            boolean pathSpeed = false;
            boolean roamSpeed = false;
            for (ItemStack piece : gearPieces(player)) {
                Map<ForgeUpgrade.Def, Integer> ups = plugin.items().effectsOf(piece);
                regen += sumAbs(ups, ForgeUpgrade.Effect.REGEN_PULSE);
                magnetBonus += sumAbs(ups, ForgeUpgrade.Effect.MAGNET);
                nightVision |= levelOf(ups, ForgeUpgrade.Effect.NIGHT_VISION) > 0;
                noFreeze |= levelOf(ups, ForgeUpgrade.Effect.NO_FREEZE) > 0;
                satiate += sumAbs(ups, ForgeUpgrade.Effect.FEED_PULSE);
                haste += levelOf(ups, ForgeUpgrade.Effect.PULSE_HASTE);
                pathSpeed |= levelOf(ups, ForgeUpgrade.Effect.PATH_SPEED) > 0;
                roamSpeed |= levelOf(ups, ForgeUpgrade.Effect.ROAM_SPEED) > 0;
                // Мастеровой: предмет с этим свойством точит сам себя
                int mendPoints = (int) Math.round(sumAbs(ups, ForgeUpgrade.Effect.PULSE_MEND));
                if (mendPoints > 0 && piece != null
                        && piece.getItemMeta() instanceof Damageable wear && wear.getDamage() > 0) {
                    ItemMeta meta = piece.getItemMeta();
                    ((Damageable) meta).setDamage(Math.max(0, wear.getDamage() - mendPoints));
                    piece.setItemMeta(meta);
                }
            }

            if (regen > 0 && player.getHealth() > 0 && player.getHealth() < maxHealth(player)) {
                heal(player, Math.min(CAP_REGEN_HEAL, regen));
            }
            if (nightVision) passive.add(new PotionEffect(PotionEffectType.NIGHT_VISION, 1, 0, false, false, false));
            if (magnetBonus > 0) pullDrops(player, magnetBonus);
            // Шуба: замерзания не бывает вовсе
            if (noFreeze && player.getFreezeTicks() > 0) {
                player.setFreezeTicks(0);
            }
            // Кормилец добавляет насыщение, не пункты голода; потолок — 6 и food level.
            if (satiate > 0) {
                float current = player.getSaturation();
                float next = ForgeWorkMath.feed(current, player.getFoodLevel(), satiate);
                if (next > current) player.setSaturation(next);
            }
            // Трудоголик: Спешка I/II, пока инструмент с клеймом в руках
            if (haste > 0) {
                passive.add(new PotionEffect(PotionEffectType.HASTE, 1, Math.min(1, haste - 1), false, true, true));
            }
            // Тропинка: Speed I на тропинках и траве
            if (pathSpeed && isWalkPath(player)) {
                passive.add(new PotionEffect(PotionEffectType.SPEED, 1, 0, false, true, true));
            }
            // Ветер: Speed I после пяти секунд вне боя
            if (roamSpeed) {
                Long last = lastCombat.get(player.getUniqueId());
                if (last == null || last < System.currentTimeMillis() - 5000L) {
                    passive.add(new PotionEffect(PotionEffectType.SPEED, 1, 0, false, true, true));
                }
            }
            PassivePotions.replace(plugin, player, passive, 40);
        }
    }

    /** Стоит ли игрок на тропинке/траве («Тропинка»). */
    private static boolean isWalkPath(Player player) {
        Material below = player.getLocation().getBlock().getRelative(0, -1, 0).getType();
        return below == Material.DIRT_PATH || below == Material.GRASS_BLOCK;
    }

    /** Экипировка, которую видит пульс: броня, основная и левая рука. */
    private static java.util.List<ItemStack> gearPieces(Player player) {
        // Arrays.asList — armorContents содержит null-ячейки, List.of бросил бы NPE
        java.util.List<ItemStack> pieces = new ArrayList<>(java.util.Arrays.asList(
                player.getInventory().getArmorContents()));
        pieces.add(player.getInventory().getItemInMainHand());
        pieces.add(player.getInventory().getItemInOffHand());
        return pieces;
    }

    /** Есть ли хоть одно кузнечное улучшение в надетой экипировке (1.4.91). */
    private boolean hasForgeGear(Player player) {
        for (ItemStack piece : gearPieces(player)) {
            if (!plugin.items().effectsOf(piece).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Магнит: подтягиваем до 6 ближайших дропов к игроку (радиус 1.5 + 0.5×ур).
     * С 1.4.82 — импульсом скорости, а не {@code teleport}: дроп не проваливается
     * в блок/под пол и не «моргает» сквозь стены.
     */
    private void pullDrops(Player player, double bonus) {
        double radius = ForgeWorkMath.magnetRadius(bonus);
        int pulled = 0;
        for (org.bukkit.entity.Entity entity : player.getNearbyEntities(radius, radius / 2, radius)) {
            if (!(entity instanceof Item drop) || drop.isDead() || drop.getPickupDelay() > 0) {
                continue;
            }
            org.bukkit.util.Vector pull = player.getLocation().toVector()
                    .subtract(drop.getLocation().toVector());
            double distance = pull.length();
            if (distance < 1.2) {
                continue; // уже у ног — подбирать будет ваниль
            }
            double speed = Math.min(0.85, 0.22 + distance * 0.14);
            drop.setVelocity(pull.normalize().multiply(speed));
            if (++pulled >= 6) {
                break;
            }
        }
    }

    /**
     * Выход игрока: чистим кулдауны и боевые метки (1.4.82). Очередь «привязки
     * души» не трогаем — она нужна респауну.
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        adrenalineCooldown.remove(player);
        lastStandCooldown.remove(player);
        lastCombat.remove(player);
        gearCache.invalidate(player);
    }

    /* --------------------- инвалидация кэша ковки (1.4.91) --------------------- */

    /** Изменение инвентаря: следующий пульс пересканирует экипировку. */
    private void invalidateGear(org.bukkit.entity.HumanEntity human) {
        if (human instanceof Player player) {
            gearCache.invalidate(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        invalidateGear(event.getWhoClicked());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        invalidateGear(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        gearCache.invalidate(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        gearCache.invalidate(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickupForCache(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            gearCache.invalidate(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDropForCache(PlayerDropItemEvent event) {
        gearCache.invalidate(event.getPlayer().getUniqueId());
    }

    /* ------------------------------- подсчёты ------------------------------- */

    /** Суммарный уровень всех улучшений с нужным эффектом (наборы могут наслаиваться). */
    static int levelOf(Map<ForgeUpgrade.Def, Integer> ups, ForgeUpgrade.Effect effect) {
        int total = 0;
        for (Map.Entry<ForgeUpgrade.Def, Integer> entry : ups.entrySet()) {
            if (entry.getKey().effect() == effect) {
                total += entry.getValue();
            }
        }
        return total;
    }

    /**
     * Средняя доля за один уровень по эффекту: Σ(уровень×perLevel)/Σуровней/100.
     *
     * <p>Вызовы всегда умножают её на суммарный уровень, поэтому произведение
     * точно равно Σ(уровень×perLevel)/100. Это важно с 1.4.118: у уникального
     * свойства тира и кузнечного улучшения может совпасть эффект, и тогда доли
     * должны складываться, а не подменять друг друга (раньше брался perLevel
     * первого попавшегося определения).</p>
     */
    static double fraction(Map<ForgeUpgrade.Def, Integer> ups, ForgeUpgrade.Effect effect) {
        double weighted = 0;
        int levels = 0;
        for (Map.Entry<ForgeUpgrade.Def, Integer> entry : ups.entrySet()) {
            if (entry.getKey().effect() == effect) {
                weighted += entry.getKey().perLevel() * entry.getValue();
                levels += entry.getValue();
            }
        }
        return levels == 0 ? 0 : weighted / levels / 100.0;
    }

    /** Шанс-эффект со всех вещей: сумма уровень×perLevel/100 (для броневого стека). */
    private static double sumFraction(Map<ForgeUpgrade.Def, Integer> ups, ForgeUpgrade.Effect effect) {
        double total = 0;
        for (Map.Entry<ForgeUpgrade.Def, Integer> entry : ups.entrySet()) {
            if (entry.getKey().effect() == effect) {
                total += entry.getValue() * entry.getKey().perLevel() / 100.0;
            }
        }
        return total;
    }

    /** Абсолютная величина со всех вещей: сумма уровень×perLevel (не процент). */
    private static double sumAbs(Map<ForgeUpgrade.Def, Integer> ups, ForgeUpgrade.Effect effect) {
        double total = 0;
        for (Map.Entry<ForgeUpgrade.Def, Integer> entry : ups.entrySet()) {
            if (entry.getKey().effect() == effect) {
                total += entry.getValue() * entry.getKey().perLevel();
            }
        }
        return total;
    }

    static boolean isUndead(LivingEntity entity) {
        return entity instanceof Zombie // обычный зомби, кадавр, утопленник, зомби-житель, пиглин
                || entity instanceof AbstractSkeleton // скелет, зимогор, визер-скелет, болотник
                || entity instanceof Phantom
                || entity instanceof Wither;
    }

    static boolean isOre(Material type) {
        if (type == null) {
            return false;
        }
        String name = type.name();
        return name.endsWith("_ORE") || type == Material.ANCIENT_DEBRIS;
    }

    private static double maxHealth(LivingEntity entity) {
        org.bukkit.attribute.AttributeInstance attribute =
                entity.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? 20.0 : attribute.getValue();
    }

    /** Армор-значение цели (для «Ломателя»; нет атрибута — 0). */
    private static double armorValue(LivingEntity entity) {
        org.bukkit.attribute.AttributeInstance attribute = entity.getAttribute(Attribute.ARMOR);
        return attribute == null ? 0.0 : attribute.getValue();
    }

    private void heal(Player player, double amount) {
        if (amount <= 0 || player.isDead()) {
            return;
        }
        double max = maxHealth(player);
        double next = Math.min(max, player.getHealth() + amount);
        if (next <= player.getHealth()) {
            return;
        }
        try {
            player.setHealth(next);
        } catch (IllegalArgumentException ignored) {
            // здоровье подвинулось под нами — пропускаем лечение тихо
        }
    }
}
