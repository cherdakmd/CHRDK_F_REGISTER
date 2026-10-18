package ru.cherdakmd.chrdktierforge;

import org.bukkit.Material;
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
 * читаются из PDC предмета в руке/на теле в момент события — кэшей самих предметов
 * нет. С 1.4.91 есть только кэш «есть ли ковка в экипировке» для пульса
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
    private final Random random = new Random();
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
    }

    /* ------------------------------- атака игрока ------------------------------- */

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerHit(EntityDamageByEntityEvent event) {
        Player attacker;
        boolean projectileHit = false;
        if (event.getDamager() instanceof Player melee) {
            attacker = melee;
        } else if (event.getDamager() instanceof Projectile shot
                && shot.getShooter() instanceof Player shooter) {
            attacker = shooter;
            projectileHit = true;
        } else {
            return;
        }
        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        Map<ForgeUpgrade.Def, Integer> ups = plugin.items().upgradesOf(weapon);
        if (ups.isEmpty()) {
            return;
        }
        lastCombat.put(attacker.getUniqueId(), System.currentTimeMillis());

        // Снаряды («Снайпер»): оружие — лук в момент попадания; проки ближнего боя не работают
        if (projectileHit) {
            int snipe = levelOf(ups, ForgeUpgrade.Effect.SNIPE);
            if (snipe > 0) {
                event.setDamage(event.getDamage()
                        * (1.0 + snipe * fraction(ups, ForgeUpgrade.Effect.SNIPE)));
            }
            return;
        }

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
        if (current != null && current.getDuration() > ticks
                && current.getAmplifier() >= amplifier) {
            return;
        }
        target.addPotionEffect(new PotionEffect(type, ticks, amplifier, false, true, true));
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
            Map<ForgeUpgrade.Def, Integer> ups = plugin.items().upgradesOf(piece);
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

        // Последний рубеж: смертельный удар → 1 HP + Сопротивление II (раз в 60 с)
        if (lastStand && event.getFinalDamage() >= victim.getHealth()
                && cooldownReady(lastStandCooldown, victim.getUniqueId(), LASTSTAND_CD_MS)) {
            event.setDamage(0);
            victim.setHealth(Math.min(1.0, maxHealth(victim)));
            applyFresh(victim, PotionEffectType.RESISTANCE, 100, 1);
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
            total += levelOf(plugin.items().upgradesOf(piece), ForgeUpgrade.Effect.CATCHER);
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
                plugin.items().upgradesOf(killer.getInventory().getItemInMainHand());
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
        Material type = event.getBlock().getType();
        ItemStack tool = event.getPlayer().getInventory().getItemInMainHand();
        Map<ForgeUpgrade.Def, Integer> ups = plugin.items().upgradesOf(tool);
        if (ups.isEmpty()) {
            return;
        }

        // Геолог: +% опыта с блока
        int geologist = levelOf(ups, ForgeUpgrade.Effect.BLOCK_XP);
        if (geologist > 0 && event.getExpToDrop() > 0) {
            double bonus = geologist * fraction(ups, ForgeUpgrade.Effect.BLOCK_XP);
            event.setExpToDrop((int) Math.round(event.getExpToDrop() * (1.0 + bonus)));
        }

        // Рудачир: шанс дубля дропа руды
        int prospector = levelOf(ups, ForgeUpgrade.Effect.ORE_DOUBLE);
        if (prospector > 0 && isOre(type)
                && random.nextDouble() < Math.min(CAP_ORE_DOUBLE,
                        prospector * fraction(ups, ForgeUpgrade.Effect.ORE_DOUBLE))) {
            for (ItemStack drop : event.getBlock().getDrops(tool, event.getPlayer())) {
                if (drop != null && !drop.getType().isAir()) {
                    event.getBlock().getWorld().dropItemNaturally(
                            event.getBlock().getLocation().add(0.5, 0.5, 0.5), drop.clone());
                }
            }
        }

        // Забойщик: кирка выбивает колонну того же блока (ур I — сверху, II — и снизу)
        int hewer = levelOf(ups, ForgeUpgrade.Effect.ZONE_BREAK);
        if (hewer > 0 && !zoneBreaking.get()
                && ItemKind.classify(tool.getType().name()) == ItemKind.PICKAXE
                && type.isBlock() && type.getHardness() >= 0 && type.getHardness() < 50) {
            zoneBreaking.set(true);
            try {
                breakColumnSibling(event.getBlock(), 1, type, tool, event.getPlayer());
                if (hewer >= 2) {
                    breakColumnSibling(event.getBlock(), -1, type, tool, event.getPlayer());
                }
            } finally {
                zoneBreaking.set(false);
            }
        }
    }

    /**
     * Добить соседний по вертикали блок того же типа этим же инструментом (1.4.92).
     *
     * <p>Раньше блок ломался напрямую через {@code breakNaturally} — в обход
     * {@code BlockBreakEvent}. На сервере с приватами это позволяло выбить колонну
     * под чужим регионом, а лог-плагины не видели второй блок. Теперь запрос
     * проходит обычным событием: защита и права могут его отменить.
     */
    private void breakColumnSibling(Block origin, int dy, Material type, ItemStack tool,
                                    Player player) {
        Block sibling = origin.getRelative(0, dy, 0);
        if (sibling.getType() != type) {
            return;
        }
        BlockBreakEvent request = new BlockBreakEvent(sibling, player);
        org.bukkit.Bukkit.getPluginManager().callEvent(request);
        if (request.isCancelled()) {
            return;
        }
        if (sibling.getType() != type) {
            return; // другой плагин мог заменить блок, пока шёл обработчик
        }
        sibling.breakNaturally(tool);
    }

    /**
     * Плавильня/Земледел/Лесник/Златолюб/Глубинник: дроп блоков может обернуться
     * слитками, удвоиться (по типу блока) или породить золотой самородок.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockDrops(BlockDropItemEvent event) {
        ItemStack tool = event.getPlayer().getInventory().getItemInMainHand();
        Map<ForgeUpgrade.Def, Integer> ups = plugin.items().upgradesOf(tool);
        if (ups.isEmpty()) {
            return;
        }
        int smelter = levelOf(ups, ForgeUpgrade.Effect.SMELT_ORE);
        int gardener = levelOf(ups, ForgeUpgrade.Effect.CROP_DOUBLE);
        int logger = levelOf(ups, ForgeUpgrade.Effect.LOG_DOUBLE);
        int goldbug = levelOf(ups, ForgeUpgrade.Effect.GOLD_FIND);
        int delver = levelOf(ups, ForgeUpgrade.Effect.DEEP_DOUBLE);
        if (smelter <= 0 && gardener <= 0 && logger <= 0 && goldbug <= 0 && delver <= 0) {
            return;
        }
        Material blockType = event.getBlockState().getType();
        boolean crop = isCropBlock(blockType);
        boolean log = isLogBlock(blockType);
        boolean deep = delver > 0 && event.getBlock().getY() < 0;
        double smeltChance = smelter * fraction(ups, ForgeUpgrade.Effect.SMELT_ORE);
        double cropChance = Math.min(CAP_PROC, gardener * fraction(ups, ForgeUpgrade.Effect.CROP_DOUBLE));
        double logChance = Math.min(CAP_PROC, logger * fraction(ups, ForgeUpgrade.Effect.LOG_DOUBLE));
        double deepChance = Math.min(CAP_PROC, delver * fraction(ups, ForgeUpgrade.Effect.DEEP_DOUBLE));

        for (Item dropEntity : event.getItems()) {
            ItemStack stack = dropEntity.getItemStack();
            Material smelted = smeltResultOf(stack.getType());
            if (smelted != null && smelter > 0 && random.nextDouble() < smeltChance) {
                dropEntity.setItemStack(new ItemStack(smelted, stack.getAmount()));
                stack = dropEntity.getItemStack();
            }
            if (crop && gardener > 0 && random.nextDouble() < cropChance) {
                doubleDrop(dropEntity);
            }
            if (log && logger > 0 && random.nextDouble() < logChance) {
                doubleDrop(dropEntity);
            }
            if (deep && random.nextDouble() < deepChance) {
                doubleDrop(dropEntity);
            }
        }
        // Златолюб: крупица золота поверх дропа камня
        if (goldbug > 0 && isStoneForGold(blockType)
                && random.nextDouble()
                        < goldbug * fraction(ups, ForgeUpgrade.Effect.GOLD_FIND)) {
            event.getBlock().getWorld().dropItemNaturally(
                    event.getBlock().getLocation().add(0.5, 0.5, 0.5),
                    new ItemStack(Material.GOLD_NUGGET));
        }
    }

    /**
     * Удвоить дроп в существующей Item-сущности, не превышая maxStackSize:
     * излишек выкладывается отдельными стопками рядом (1.4.92).
     */
    private static void doubleDrop(Item dropEntity) {
        ItemStack stack = dropEntity.getItemStack();
        List<Integer> parts = DropAmounts.split(stack.getAmount() * 2,
                stack.getType().getMaxStackSize());
        if (parts.isEmpty()) {
            return;
        }
        ItemStack first = stack.clone();
        first.setAmount(parts.get(0));
        dropEntity.setItemStack(first);
        for (int i = 1; i < parts.size(); i++) {
            ItemStack extra = stack.clone();
            extra.setAmount(parts.get(i));
            dropEntity.getWorld().dropItemNaturally(dropEntity.getLocation(), extra);
        }
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
        Map<ForgeUpgrade.Def, Integer> ups = plugin.items().upgradesOf(event.getItem());
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
            Map<ForgeUpgrade.Def, Integer> ups = plugin.items().upgradesOf(piece);
            int selfmend = levelOf(ups, ForgeUpgrade.Effect.XP_REPAIR);
            if (selfmend <= 0 || !(piece.getItemMeta() instanceof Damageable state) || state.getDamage() <= 0) {
                continue;
            }
            double perPoint = selfmend * fraction(ups, ForgeUpgrade.Effect.XP_REPAIR);
            int repairs = 0;
            for (int point = 0; point < amount; point++) {
                if (random.nextDouble() < perPoint) {
                    repairs++;
                }
            }
            if (repairs > 0) {
                ItemMeta meta = piece.getItemMeta();
                ((Damageable) meta).setDamage(Math.max(0, ((Damageable) meta).getDamage() - repairs));
                piece.setItemMeta(meta);
            }
        }

        // Эрудит: +% к получаемому опыту со всей экипировки
        double scholarBonus = 0;
        for (ItemStack piece : pieces) {
            scholarBonus += sumFraction(plugin.items().upgradesOf(piece), ForgeUpgrade.Effect.XP_GAIN);
        }
        if (scholarBonus > 0) {
            event.setAmount(Math.max(1, (int) Math.round(amount * (1.0 + scholarBonus))));
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
                plugin.items().upgradesOf(event.getPlayer().getInventory().getItemInMainHand());
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
            Map<ForgeUpgrade.Def, Integer> ups = plugin.items().upgradesOf(piece);
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
                    && levelOf(plugin.items().upgradesOf(drop), ForgeUpgrade.Effect.SOULBOUND) > 0) {
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
            if (player.isDead()) {
                continue;
            }
            // 1.4.91: у кого ковки нет — скан PDC не нужен (кэш сбрасывают события
            // инвентаря, TTL 30 с страхует от пропущенного события).
            UUID playerId = player.getUniqueId();
            Boolean forged = gearCache.fresh(playerId, now);
            if (Boolean.FALSE.equals(forged)) {
                continue;
            }
            if (forged == null) {
                boolean hasForge = hasForgeGear(player);
                gearCache.put(playerId, hasForge, now);
                if (!hasForge) {
                    continue;
                }
            }
            double regen = 0;
            double magnetLevels = 0;
            boolean nightVision = false;
            boolean noFreeze = false;
            double satiate = 0;
            int haste = 0;
            boolean pathSpeed = false;
            boolean roamSpeed = false;
            for (ItemStack piece : gearPieces(player)) {
                Map<ForgeUpgrade.Def, Integer> ups = plugin.items().upgradesOf(piece);
                regen += sumAbs(ups, ForgeUpgrade.Effect.REGEN_PULSE);
                magnetLevels += levelOf(ups, ForgeUpgrade.Effect.MAGNET);
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
            if (nightVision && !player.hasPotionEffect(PotionEffectType.NIGHT_VISION)) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,
                        20 * 15, 0, false, false, false));
            }
            if (magnetLevels > 0) {
                pullDrops(player, magnetLevels);
            }
            // Шуба: замерзания не бывает вовсе
            if (noFreeze && player.getFreezeTicks() > 0) {
                player.setFreezeTicks(0);
            }
            // Кормилец: тихий паёк, пока сытость не восстановилась
            if (satiate > 0 && player.getSaturation() < 6.0f) {
                player.setSaturation((float) Math.min(player.getFoodLevel(),
                        player.getSaturation() + satiate));
            }
            // Трудоголик: Спешка I/II, пока инструмент с клеймом в руках
            if (haste > 0) {
                applyFresh(player, PotionEffectType.HASTE, 20 * 15, Math.min(1, haste - 1));
            }
            // Тропинка: Speed I на тропинках и траве
            if (pathSpeed && isWalkPath(player)) {
                applyFresh(player, PotionEffectType.SPEED, 60, 0);
            }
            // Ветер: Speed I после пяти секунд вне боя
            if (roamSpeed) {
                Long last = lastCombat.get(player.getUniqueId());
                if (last == null || last < System.currentTimeMillis() - 5000L) {
                    applyFresh(player, PotionEffectType.SPEED, 60, 0);
                }
            }
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
            if (!plugin.items().upgradesOf(piece).isEmpty()) {
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
    private void pullDrops(Player player, double levels) {
        double radius = Math.min(5.5, 1.5 + 0.5 * levels);
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
    private static int levelOf(Map<ForgeUpgrade.Def, Integer> ups, ForgeUpgrade.Effect effect) {
        int total = 0;
        for (Map.Entry<ForgeUpgrade.Def, Integer> entry : ups.entrySet()) {
            if (entry.getKey().effect() == effect) {
                total += entry.getValue();
            }
        }
        return total;
    }

    /** Доля от единицы за один уровень по КОНКРЕТНОМУ свойству (perLevel/100). */
    private static double fraction(Map<ForgeUpgrade.Def, Integer> ups, ForgeUpgrade.Effect effect) {
        for (Map.Entry<ForgeUpgrade.Def, Integer> entry : ups.entrySet()) {
            if (entry.getKey().effect() == effect) {
                return entry.getKey().perLevel() / 100.0;
            }
        }
        return 0;
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
