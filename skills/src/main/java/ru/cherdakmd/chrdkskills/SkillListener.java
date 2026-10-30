package ru.cherdakmd.chrdkskills;

import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.data.Ageable;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Событийный источник опыта и перки, которые срабатывают в момент действия.
 *
 * <p>Три правила, без которых прокачка превращается в станок:</p>
 * <ol>
 *   <li><b>Не считаем поставленное.</b> Блок, поставленный игроком недавно, при
 *       разрушении не даёт опыта и дропа ({@link PlacedBlocks});</li>
 *   <li><b>Не считаем креатив.</b> В креативе и спектаторе опыт не начисляется
 *       ({@code limits.count-creative: false});</li>
 *   <li><b>Земледелие — только созревшее.</b> Ломать росток бессмысленно.</li>
 * </ol>
 *
 * <p>Вехи и уровни обсчитывает {@link SkillService}: здесь только события.</p>
 */
public final class SkillListener implements Listener {

    /** Максимум единиц урона за одно событие — защита от «одного удара на 400». */
    private static final long MAX_COMBAT_UNITS = 10L;
    /** Сколько секунд помнить, кто открыл варочную стойку. */
    private static final long BREW_MEMORY_MILLIS = 60_000L;

    private final SkillsPlugin plugin;
    private final SkillService service;
    private PlacedBlocks placedBlocks;
    private final Random random = new Random();
    /** Кто открыл варочную стойку: ключ блока → uuid и время. */
    private final Map<String, BrewOwner> brewOwners = new ConcurrentHashMap<>();

    public SkillListener(SkillsPlugin plugin, SkillService service) {
        this.plugin = plugin;
        this.service = service;
        this.placedBlocks = new PlacedBlocks(
                plugin.config().placedBlockTtlSeconds() * 1000L,
                plugin.config().placedBlockMaxEntries());
    }

    /** Пересоздаёт антинакрутку после reload: TTL и лимит берутся из конфига. */
    public void reloadAntiCheat() {
        this.placedBlocks = new PlacedBlocks(
                plugin.config().placedBlockTtlSeconds() * 1000L,
                plugin.config().placedBlockMaxEntries());
    }

    /* ------------------------------- блоки и урожай ---------------------------- */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!honest(player)) {
            return;
        }
        Block block = event.getBlock();
        Material type = block.getType();
        if (placedBlocks.consume(player.getUniqueId(), block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), System.currentTimeMillis())) {
            return;
        }
        SkillType skill = SkillMaterials.blockSkill(type);
        if (skill == null) {
            return;
        }
        if (skill == SkillType.FARMING && !SkillMaterials.isRipe(block)) {
            return;
        }
        plugin.abilities().noteBreak(event, skill);
        service.award(player, skill, 1L);
        doubleDrop(player, block, skill);
        if (skill == SkillType.FARMING) {
            replayCrop(player, event);
        }
    }

    /** Шанс второй порции добычи из того же блока. */
    private void doubleDrop(Player player, Block block, SkillType skill) {
        double chance = service.perksFor(player, skill).value(SkillPerk.Kind.DOUBLE_DROP);
        if (chance <= 0.0 || random.nextDouble() >= chance) {
            return;
        }
        List<ItemStack> drops;
        try {
            drops = new ArrayList<>(block.getDrops(player.getInventory().getItemInMainHand(), player));
        } catch (RuntimeException exception) {
            drops = List.of();
        }
        if (drops.isEmpty() && block.getType().isItem() && !block.getType().isAir()) {
            // Фолбэк для блоков без таблицы дропа (или если состояние уже сменилось).
            drops = List.of(new ItemStack(block.getType()));
        }
        Location dropAt = block.getLocation().add(0.5, 0.5, 0.5);
        for (ItemStack drop : drops) {
            if (drop != null && !drop.getType().isAir()) {
                block.getWorld().dropItemNaturally(dropAt, drop.clone());
            }
        }
    }

    /**
     * Автопосадка: культура возвращается на грядку в нулевом возрасте.
     *
     * <p>Сажаем следующим тиком, а не внутри события: сервер сломает блок уже
     * после {@link BlockBreakEvent}, и посаженное «сразу» просто исчезло бы
     * вместе с ним.</p>
     */
    private void replayCrop(Player player, BlockBreakEvent event) {
        if (!service.perksFor(player, SkillType.FARMING).flag(SkillPerk.Kind.AUTO_REPLANT)) return;
        ru.cherdakmd.chrdkregister.api.CropReplanting.request(plugin, event, () ->
                plugin.config().enabled() && player.getGameMode() == GameMode.SURVIVAL
                && player.hasPermission("chrdkskills.use") && plugin.coreApi() != null
                && plugin.coreApi().isAuthenticated(player)
                && service.perksFor(player, SkillType.FARMING).flag(SkillPerk.Kind.AUTO_REPLANT));
    }

    /** Запоминаем поставленные блоки — смена игрока не возвращает XP и двойной дроп. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        // Свой возраст-0 росток не является поставленной игроком зрелой культурой/рудой.
        // Native-событие остаётся видимым всем защитам, но не урезает XP следующего зрелого урожая.
        if (ru.cherdakmd.chrdkregister.api.CropReplanting.isPlacement(event)) return;
        Block block = event.getBlockPlaced();
        placedBlocks.record(event.getPlayer().getUniqueId(), block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), System.currentTimeMillis());
    }

    /* ---------------------------------- бой ---------------------------------- */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMelee(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)
                || !(event.getEntity() instanceof LivingEntity victim)
                || victim instanceof Player || !hostile(victim)) {
            return;
        }
        if (!honest(player)) {
            return;
        }
        SkillPerks.Active perks = service.perksFor(player, SkillType.COMBAT);
        double bonus = perks.value(SkillPerk.Kind.MELEE_DAMAGE);
        if (bonus > 0.0) {
            event.setDamage(event.getDamage() * (1.0 + bonus));
        }
        long units = Math.min(MAX_COMBAT_UNITS, Math.max(1L, (long) Math.ceil(event.getFinalDamage())));
        service.award(player, SkillType.COMBAT, units);
        double lifesteal = perks.value(SkillPerk.Kind.LIFESTEAL);
        if (lifesteal > 0.0) {
            heal(player, event.getFinalDamage() * lifesteal);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArrow(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof AbstractArrow arrow)
                || !(arrow.getShooter() instanceof Player player)
                || !(event.getEntity() instanceof LivingEntity victim)
                || victim instanceof Player || !hostile(victim)) {
            return;
        }
        if (!honest(player)) {
            return;
        }
        double bonus = service.perksFor(player, SkillType.ARCHERY).value(SkillPerk.Kind.ARROW_DAMAGE);
        if (bonus > 0.0) {
            event.setDamage(event.getDamage() * (1.0 + bonus));
        }
        long units = Math.min(MAX_COMBAT_UNITS, Math.max(1L, (long) Math.ceil(event.getFinalDamage())));
        service.award(player, SkillType.ARCHERY, units);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDefense(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getDamage() <= 0.0) {
            return;
        }
        if (!honest(player)) {
            return;
        }
        // Снижение действует на любой урон, включая падение и огонь, но опыт
        // даёт только удар моба: иначе стояние в лаве становится станком.
        double reduction = Math.min(0.9, service.perksFor(player, SkillType.DEFENSE)
                .value(SkillPerk.Kind.DAMAGE_REDUCTION));
        if (reduction > 0.0) {
            event.setDamage(event.getDamage() * (1.0 - reduction));
        }
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return;
        }
        if (byEntity.getDamager() instanceof Player) {
            return;
        }
        if (byEntity.getDamager() instanceof AbstractArrow arrow
                && arrow.getShooter() instanceof Player) {
            return;
        }
        if (!(byEntity.getDamager() instanceof LivingEntity
                || byEntity.getDamager() instanceof AbstractArrow)) {
            return;
        }
        long units = Math.min(MAX_COMBAT_UNITS,
                Math.max(1L, (long) Math.ceil(event.getFinalDamage() / 2.0)));
        service.award(player, SkillType.DEFENSE, units);
    }

    /**
     * Шанс сохранить стрелу — перк «колчан».
     *
     * <p>Возврат делается предметом через тик, а не флагом события: начиная с
     * 1.21.9 {@code setConsumeArrow} объявлен нерабочим, и «сохранение» через
     * него превратилось бы в пустышку. Возвращаем именно тот расходник, что
     * улетел (стрела со зельем остаётся стрелой со зельем).</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player) || !honest(player)) {
            return;
        }
        double chance = service.perksFor(player, SkillType.ARCHERY)
                .value(SkillPerk.Kind.ARROW_KEEP);
        if (!roll(chance) || hasInfinity(event.getBow())) {
            // с «Бесконечностью» стрела и так не тратится — возвращать нечего
            return;
        }
        ItemStack consumable = event.getConsumable();
        ItemStack refund = consumable != null && !consumable.getType().isAir()
                ? consumable.clone()
                : new ItemStack(Material.ARROW);
        refund.setAmount(1);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            player.getInventory().addItem(refund).values().forEach(rest ->
                    player.getWorld().dropItemNaturally(player.getLocation(), rest));
            plugin.messages().send(player, "arrow-kept");
        });
    }

    /* --------------------------------- рыбалка -------------------------------- */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH
                || !(event.getCaught() instanceof Item caught)) {
            return;
        }
        Player player = event.getPlayer();
        if (!honest(player)) {
            return;
        }
        service.award(player, SkillType.FISHING, 1L);
        SkillPerks.Active perks = service.perksFor(player, SkillType.FISHING);
        // Два независимых шанса: «лишняя рыба» и «клад» на 100-м уровне. Оба
        // дают копию пойманного предмета, но бросаются отдельно — иначе
        // шестой перк ничего бы не добавлял.
        boolean extra = false;
        if (roll(perks.value(SkillPerk.Kind.EXTRA_CATCH))) {
            extra = true;
        }
        if (roll(perks.value(SkillPerk.Kind.DOUBLE_DROP))) {
            extra = true;
        }
        if (extra) {
            ItemStack bonus = caught.getItemStack().clone();
            player.getWorld().dropItemNaturally(player.getLocation(), bonus);
            plugin.messages().send(player, "extra-catch");
        }
    }

    /* ------------------------------- зелья и чары ----------------------------- */

    /** Запоминаем владельца варочной стойки: BrewEvent не знает игрока. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpenBrewing(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || !(event.getInventory().getHolder() instanceof BrewingStand stand)) {
            return;
        }
        long now = System.currentTimeMillis();
        brewOwners.entrySet().removeIf(entry -> now - entry.getValue().at() > BREW_MEMORY_MILLIS);
        brewOwners.put(brewKey(stand.getLocation()), new BrewOwner(player.getUniqueId(), now));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        BrewOwner owner = brewOwners.remove(brewKey(event.getBlock().getLocation()));
        if (owner == null) {
            return;
        }
        Player player = plugin.getServer().getPlayer(owner.player());
        if (player == null || !honest(player)) {
            return;
        }
        service.award(player, SkillType.ALCHEMY, 1L);
        double chance = service.perksFor(player, SkillType.ALCHEMY)
                .value(SkillPerk.Kind.EXTRA_POTION);
        ItemStack ingredient = event.getContents().getIngredient();
        if (roll(chance) && ingredient != null && !ingredient.getType().isAir()) {
            // Вторую порцию нельзя положить в слот топлива, поэтому выдаём
            // игроку предметом — так перк не ломает состояние стойки.
            ItemStack extra = ingredient.clone();
            extra.setAmount(1);
            player.getInventory().addItem(extra).values().forEach(rest ->
                    player.getWorld().dropItemNaturally(player.getLocation(), rest));
            plugin.messages().send(player, "extra-potion");
        }
    }

    /** Удлинение выпитых зелий — перк алхимии. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotion(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || event.getAction() != EntityPotionEffectEvent.Action.ADDED
                || event.getCause() != EntityPotionEffectEvent.Cause.POTION_DRINK
                || event.getNewEffect() == null) {
            return;
        }
        double bonus = service.perksFor(player, SkillType.ALCHEMY)
                .value(SkillPerk.Kind.POTION_DURATION);
        if (bonus <= 0.0) {
            return;
        }
        PotionEffectType type = event.getNewEffect().getType();
        int amplifier = event.getNewEffect().getAmplifier();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            PotionEffect current = player.getPotionEffect(type);
            if (current == null || current.getAmplifier() != amplifier) {
                return;
            }
            int duration = (int) Math.min(Integer.MAX_VALUE, Math.round(
                    current.getDuration() * (1.0 + bonus)));
            player.addPotionEffect(new PotionEffect(type, duration, current.getAmplifier(),
                    current.isAmbient(), current.hasParticles(), current.hasIcon()));
        });
    }

    /** Чары: опыт за зачарование, шанс +1 к чаре и возврат уровней. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        Player player = event.getEnchanter();
        if (!honest(player)) {
            return;
        }
        SkillPerks.Active perks = service.perksFor(player, SkillType.ENCHANTING);
        service.award(player, SkillType.ENCHANTING, 1L);
        double bonusChance = perks.value(SkillPerk.Kind.ENCHANT_BONUS);
        if (bonusChance > 0.0 && random.nextDouble() < bonusChance
                && !event.getEnchantsToAdd().isEmpty()) {
            List<Enchantment> keys = new ArrayList<>(event.getEnchantsToAdd().keySet());
            Enchantment chosen = keys.get(random.nextInt(keys.size()));
            int level = event.getEnchantsToAdd().getOrDefault(chosen, 1);
            int next = Math.min(chosen.getMaxLevel(), level + 1);
            if (next > level) {
                event.getEnchantsToAdd().put(chosen, next);
                plugin.messages().send(player, "enchant-bonus");
            }
        }
        double refundChance = perks.value(SkillPerk.Kind.XP_REFUND);
        if (refundChance > 0.0 && event.getExpLevelCost() > 1
                && random.nextDouble() < refundChance) {
            int refund = Math.max(1, event.getExpLevelCost() / 2);
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> player.giveExpLevels(refund));
            plugin.messages().send(player, "enchant-refund", "levels", String.valueOf(refund));
        }
    }

    /* --------------------------------- служебное ------------------------------ */

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        service.rememberName(event.getPlayer());
        plugin.effects().apply(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        ru.cherdakmd.chrdkregister.api.CropReplanting.cancelPlayer(event.getPlayer().getUniqueId());
        plugin.effects().clear(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void replantTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        ru.cherdakmd.chrdkregister.api.CropReplanting.cancelPlayer(event.getPlayer().getUniqueId());
    }
    @EventHandler public void replantDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        ru.cherdakmd.chrdkregister.api.CropReplanting.cancelPlayer(event.getEntity().getUniqueId());
    }

    /**
     * Есть ли на луке «Бесконечность». Смотрим ключ зачарования, а не константу
     * {@code Enchantment.INFINITY}: в новых API зачарования — реестр, и
     * статическая константа может сменить имя, а ключ {@code minecraft:infinity}
     * стабилен.
     */
    private static boolean hasInfinity(ItemStack bow) {
        if (bow == null || bow.getType().isAir()) {
            return false;
        }
        for (Enchantment enchantment : bow.getEnchantments().keySet()) {
            if ("infinity".equals(enchantment.getKey().getKey())) {
                return true;
            }
        }
        return false;
    }

    /** Бросок на шанс; значения вне 0..1 трактуются как «всегда/никогда». */
    private boolean roll(double chance) {
        return chance > 0.0 && random.nextDouble() < Math.min(1.0, chance);
    }

    private boolean honest(Player player) {
        if (player == null || !plugin.config().enabled()) {
            return false;
        }
        GameMode mode = player.getGameMode();
        return plugin.config().countCreative()
                || (mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR);
    }

    private void heal(Player player, double amount) {
        AttributeInstance health = player.getAttribute(Attribute.MAX_HEALTH);
        double max = health != null ? health.getValue() : 20.0;
        if (player.getHealth() >= max || amount <= 0.0) {
            return;
        }
        try {
            player.setHealth(Math.min(max, player.getHealth() + amount));
        } catch (IllegalArgumentException ignored) {
            // здоровье изменилось под нами — лечение пропускаем
        }
    }

    private static boolean hostile(LivingEntity entity) {
        return entity instanceof Monster || entity instanceof Slime
                || entity instanceof Phantom || entity instanceof Ghast
                || entity instanceof Boss;
    }

    private static String brewKey(Location location) {
        if (location == null || location.getWorld() == null) {
            return "?";
        }
        return location.getWorld().getName() + ':' + location.getBlockX() + ':'
                + location.getBlockY() + ':' + location.getBlockZ();
    }

    /** Кто и когда открыл стойку. */
    private record BrewOwner(UUID player, long at) {
    }
}
