package ru.cherdakmd.chrdkartifacts;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Objects;
import java.util.Random;

/**
 * Активные артефакты: ПКМ предметом → проверки (вкл, авторизация, кулдаун, уровни опыта)
 * → скрипт эффекта из {@link ArtifactEffects.ActiveKind}. Хартия чар — живая зависимость
 * от CHRDK_Enchants (выкатывает случайную кастомную чару на предмет в свободной руке).
 */
public final class ActiveEffectsListener implements Listener {

    private static final int TARGET_REACH = 30;
    private static final int DASH_BLOCKS = 8;

    private final ArtifactsPlugin plugin;
    private final Random random = new Random();

    public ActiveEffectsListener(ArtifactsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * ПКМ активным артефактом. Клик по интерактивному блоку (сундук, дверь,
     * верстак, наковальня…) и клик в приседе артефакт не перехватывает — их
     * обслуживает игра. В остальных случаях событие отменяется, чтобы артефакт
     * нельзя было случайно поставить блоком или съесть, а уже потом решаются
     * права, авторизация, кулдаун и цена в уровнях.
     */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (!ArtifactUseRules.isRightClick(action)) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND && event.getHand() != EquipmentSlot.OFF_HAND) {
            return;
        }
        ItemStack held = event.getItem();
        String id = plugin.items().idOf(held);
        if (id == null) {
            return;
        }
        ArtifactDef def = ArtifactRegistry.byId(id);
        if (def == null || def.mode() != ArtifactDef.Mode.ACTIVE) {
            return;
        }
        Player player = event.getPlayer();
        Block clicked = event.getClickedBlock();
        boolean interactable = clicked != null && clicked.getType().isInteractable();
        if (ArtifactUseRules.passesToBlock(action, interactable, player.isSneaking())) {
            return;
        }
        event.setCancelled(true);
        if (!player.hasPermission("chrdkartifacts.use") || !plugin.config().isEnabled(id)) {
            return;
        }
        if (plugin.config().requiresAuth()
                && (plugin.coreApi() == null || !plugin.coreApi().isAuthenticated(player))) {
            plugin.messages().send(player, "need-auth");
            return;
        }
        long now = System.currentTimeMillis();
        if (plugin.cooldowns().isCoolingDown(player.getUniqueId(), id, now)) {
            plugin.messages().send(player, "on-cooldown", "seconds",
                    String.valueOf(plugin.cooldowns().remainingSeconds(player.getUniqueId(), id, now)));
            return;
        }
        int xpCost = plugin.config().xpCostLevels(def);
        if (xpCost > 0 && player.getLevel() < xpCost) {
            plugin.messages().send(player, "not-enough-levels", "need", String.valueOf(xpCost));
            return;
        }
        ItemStack target = counterpartHandItem(event.getHand(), player);
        boolean ok = runEffect(def, player, target);
        if (!ok) {
            return;
        }
        if (xpCost > 0) {
            player.setLevel(player.getLevel() - xpCost);
        }
        plugin.cooldowns().start(player.getUniqueId(), id, now, plugin.config().cooldownSeconds(def));
        plugin.messages().send(player, "activated", "name", plugin.items().nameOf(def));
    }

    /**
     * Артефакт — не строительный материал. Приседание перед блоком отдаёт клик
     * игре, поэтому размещение (руда, обсидиан, снег, паутина…) отсекается
     * отдельно: иначе предмет с меткой PDC исчез бы навсегда.
     */
    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (plugin.items().idOf(event.getItemInHand()) == null) {
            return;
        }
        event.setCancelled(true);
        plugin.messages().send(event.getPlayer(), "item-protected");
    }

    /** Съедобные материалы (например, плод хоруса) тоже не должны уничтожать артефакт. */
    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (plugin.items().idOf(event.getItem()) == null) {
            return;
        }
        event.setCancelled(true);
        plugin.messages().send(event.getPlayer(), "item-protected");
    }

    /** Предмет «клиента» для эффектов вроде хартии: предмет в другой руке, если он не артефакт. */
    private ItemStack counterpartHandItem(EquipmentSlot hand, Player player) {
        ItemStack other = hand == EquipmentSlot.HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (other == null || plugin.items().idOf(other) != null) {
            return null;
        }
        return other;
    }

    private boolean runEffect(ArtifactDef def, Player player, ItemStack target) {
        return switch (Objects.requireNonNull(ArtifactEffects.activeKind(def.id()))) {
            case BOLT_STORM -> boltStorm(player);
            case DASH -> dash(player);
            case FEAST -> feast(player);
            case FULL_HEAL -> fullHeal(player);
            case RANDOM_TELEPORT -> randomTeleport(player);
            case REVEAL -> reveal(player);
            case ENCHANT_ROLL -> enchantRoll(player, target);
            case DARK_BARGAIN -> darkBargain(player);
            case BONE_CALL -> boneCall(player);
            case WILD_TELEPORT -> wildTeleport(player);
            case GRIEF_STORM -> griefStorm(player);
            case MOROK_STING -> morokSting(player);
            case NIGHT_CLOCK -> nightClock(player);
            case SOUL_COUPON -> soulCoupon(player);
            case CLEANSE_CURSE -> cleanseCurse(player);
            case BLOOD_TOTEM -> bloodTotem(player);
            case HOME_TELEPORT -> homeTeleport(player);
            case FROST_WAVE -> frostWave(player);
            case REVEAL_BOSSES -> revealBosses(player);
            case ABYSS_POWER -> abyssPower(player);
            case ENDER_SHIFT -> enderShift(player);
            case ETERNITY_HOUR -> eternityHour(player);
            case CURSE_TOME -> curseTome(player);
            case BLOOD_DONATE -> bloodDonate(player);
            case MOON_SHATTER -> moonShatter(player);
            case LAZY_HAT -> lazyHat(player);
            case SOUL_SNARE -> soulSnare(player);
            case FOG_BOMB -> fogBomb(player);
            case VOID_KEY -> voidKey(player);
            case VERMIN_CALL -> verminCall(player);
        };
    }

    /* -------------------- добрая волна (1.4.58): скрипты -------------------- */

    /** Свиток Очищения: смыть все злые эффекты и огонь с себя. */
    private boolean cleanseCurse(Player player) {
        int removed = 0;
        for (PotionEffect effect : player.getActivePotionEffects()) {
            if (effect.getType().getEffectCategory() == PotionEffectType.Category.HARMFUL) {
                player.removePotionEffect(effect.getType());
                removed++;
            }
        }
        if (player.getFireTicks() > 0) {
            player.setFireTicks(0);
            removed++;
        }
        if (removed == 0) {
            plugin.messages().send(player, "cleanse-none");
            return false;
        }
        plugin.messages().send(player, "cleansed", "count", String.valueOf(removed));
        return true;
    }

    /** Тотем Крови: щит поглощения и сопротивление впрок. */
    private boolean bloodTotem(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 20 * 60, 3,
                true, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 20 * 60, 0,
                true, false, true));
        return true;
    }

    /** Сфера Побега: домой — к кровати / точке возрождения / спавну мира. */
    private boolean homeTeleport(Player player) {
        Location home = player.getRespawnLocation();
        if (home == null) {
            home = player.getWorld().getSpawnLocation();
        }
        if (home == null) {
            plugin.messages().send(player, "home-missing");
            return false;
        }
        player.teleport(home.clone().add(0.5, 0.05, 0.5));
        return true;
    }

    /** Ледяная Стена: мобов вокруг сковывает льдом. */
    private boolean frostWave(Player player) {
        int hit = 0;
        for (org.bukkit.entity.Entity entity : player.getNearbyEntities(10, 6, 10)) {
            if (entity instanceof org.bukkit.entity.Monster mob && !mob.isDead()) {
                mob.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20 * 6, 3,
                        true, false, true));
                mob.setFreezeTicks(Math.max(mob.getFreezeTicks(), 20 * 6));
                hit++;
            }
        }
        player.getWorld().playSound(player.getLocation(),
                org.bukkit.Sound.BLOCK_GLASS_BREAK, 1.0f, 0.6f);
        return true;
    }

    /** Глаз Начала: все боссы поблизости светятся на минуту. */
    private boolean revealBosses(Player player) {
        final java.util.Set<org.bukkit.entity.EntityType> bosses = java.util.EnumSet.of(
                org.bukkit.entity.EntityType.ENDER_DRAGON, org.bukkit.entity.EntityType.WITHER,
                org.bukkit.entity.EntityType.WARDEN, org.bukkit.entity.EntityType.ELDER_GUARDIAN,
                org.bukkit.entity.EntityType.WITHER_SKELETON);
        int revealed = 0;
        for (org.bukkit.entity.Entity entity : player.getNearbyEntities(64, 24, 64)) {
            if (bosses.contains(entity.getType()) && entity instanceof LivingEntity living) {
                living.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 20 * 60, 0,
                        true, false, true));
                revealed++;
            }
        }
        plugin.messages().send(player, "revealed", "count", String.valueOf(revealed));
        return true;
    }

    /** Корона Бездны: Сила II на полминуты — но голова слегка кружится. */
    private boolean abyssPower(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 20 * 30, 1,
                true, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 20 * 6, 0,
                true, false, true));
        return true;
    }

    /** Эндер-Сдвиг: телепорт по взгляду на 12 блоков, даже сквозь воздух. */
    private boolean enderShift(Player player) {
        Vector dir = player.getEyeLocation().getDirection().normalize();
        Location from = player.getLocation();
        Location spot = null;
        for (double d = 1; d <= 12; d += 0.5) {
            Location probe = from.clone().add(dir.clone().multiply(d));
            if (probe.getBlock().isPassable() && probe.clone().add(0, 1, 0).getBlock().isPassable()) {
                spot = probe;
            }
        }
        if (spot == null || spot.distanceSquared(from) < 1) {
            plugin.messages().send(player, "dash-blocked");
            return false;
        }
        player.teleport(spot.setDirection(from.getDirection()));
        player.getWorld().playSound(spot, org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
        return true;
    }

    /** Осколок Вечности: запас здоровья и щит на полторы минуты. */
    private boolean eternityHour(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.HEALTH_BOOST, 20 * 90, 1,
                true, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 20 * 90, 1,
                true, false, true));
        return true;
    }

    /* -------------------- тёмная волна (1.4.58): скрипты -------------------- */

    /** Книга Проклятий: чужие глаза и ноги отнимаются на десять секунд. */
    private boolean curseTome(Player player) {
        int hit = 0;
        for (org.bukkit.entity.Entity entity : player.getNearbyEntities(15, 8, 15)) {
            if (entity instanceof Player other && !other.equals(player)) {
                other.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 200, 0,
                        true, false, true));
                other.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 200, 1,
                        true, false, true));
                hit++;
            }
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 200, 1,
                true, false, true));
        return true;
    }

    /** Жертвенный Кубок: за щеку здоровья — щит и регенерацию. */
    private boolean bloodDonate(Player player) {
        if (player.getHealth() <= 6.0) {
            plugin.messages().send(player, "blood-donate-low");
            return false;
        }
        player.setHealth(player.getHealth() - 4.0);
        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 20 * 90, 2,
                true, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 20 * 5, 0,
                true, false, true));
        return true;
    }

    /** Расколотая Луна: миру — полночь, чужим — свечение позора. */
    private boolean moonShatter(Player player) {
        player.getWorld().setTime(18000);
        for (Player other : player.getWorld().getPlayers()) {
            if (!other.equals(player)) {
                other.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 20 * 15, 0,
                        true, false, true));
            }
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 20 * 10, 0,
                true, false, true));
        return true;
    }

    /** Шляпа Бездельника: взмыть и медленно опуститься, руки тяжелеют. */
    private boolean lazyHat(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 30, 1,
                true, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 8, 0,
                true, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 20 * 10, 0,
                true, false, true));
        return true;
    }

    /** Силок Души: мобов поблизости подбрасывает и ослабляет. */
    private boolean soulSnare(Player player) {
        for (org.bukkit.entity.Entity entity : player.getNearbyEntities(12, 8, 12)) {
            if (entity instanceof org.bukkit.entity.Monster mob && !mob.isDead()) {
                mob.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 60, 1,
                        true, false, true));
                mob.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 20 * 10, 0,
                        true, false, true));
            }
        }
        return true;
    }

    /** Облака Дурмана: чужих мутит, нас несёт. */
    private boolean fogBomb(Player player) {
        for (org.bukkit.entity.Entity entity : player.getNearbyEntities(10, 6, 10)) {
            if (entity instanceof Player other && !other.equals(player)) {
                other.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 20 * 12, 0,
                        true, false, true));
                other.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20 * 4, 0,
                        true, false, true));
            }
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0,
                true, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 6, 0,
                true, false, true));
        return true;
    }

    /** Ключ Пропасти: форточка в ближайшую пещерную пустоту под ногами. */
    private boolean voidKey(Player player) {
        Location base = player.getLocation().getBlock().getLocation();
        for (int y = base.getBlockY() - 2; y > base.getWorld().getMinHeight() + 1
                && y > base.getBlockY() - 60; y--) {
            Location probe = new Location(base.getWorld(), base.getX(), y, base.getZ());
            if (probe.getBlock().isPassable()
                    && probe.clone().add(0, 1, 0).getBlock().isPassable()
                    && probe.clone().add(0, -1, 0).getBlock().getType().isSolid()) {
                player.teleport(probe.add(0.5, 1.0, 0.5).setDirection(base.getDirection()));
                player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0,
                        true, false, true));
                return true;
            }
        }
        plugin.messages().send(player, "dash-blocked");
        return false;
    }

    /** Свист Гадов: пещерные пауки сбегаются на свист. */
    private boolean verminCall(Player player) {
        for (int i = 0; i < 3; i++) {
            Location loc = player.getLocation().clone().add(
                    random.nextDouble() * 8 - 4, 1, random.nextDouble() * 8 - 4);
            LivingEntity spider = (LivingEntity) player.getWorld().spawnEntity(loc,
                    EntityType.CAVE_SPIDER);
            if (spider instanceof org.bukkit.entity.CaveSpider cave) {
                cave.setTarget(player);
            }
        }
        return true;
    }

    private boolean boltStorm(Player player) {
        Block target = player.getTargetBlock(null, TARGET_REACH);
        Location center = target.getLocation().add(0.5, 1, 0.5);
        for (int i = 0; i < 3; i++) {
            center.getWorld().strikeLightning(center.clone().add(
                    random.nextDouble() * 4 - 2, 0, random.nextDouble() * 4 - 2));
        }
        return true;
    }

    private boolean dash(Player player) {
        Vector dir = player.getEyeLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 0.1) {
            dir = player.getLocation().getDirection().setY(0);
        }
        dir.normalize();
        Location from = player.getLocation();
        Location spot = from.clone();
        for (double d = 1; d <= DASH_BLOCKS; d += 0.5) {
            Location probe = from.clone().add(dir.clone().multiply(d));
            if (probe.getBlock().isPassable() && probe.clone().add(0, 1, 0).getBlock().isPassable()
                    && !probe.clone().add(0, -1, 0).getBlock().isPassable()) {
                spot = probe;
            }
        }
        if (spot.distanceSquared(from) < 1) {
            plugin.messages().send(player, "dash-blocked");
            return false;
        }
        player.teleport(spot.setDirection(from.getDirection()));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 1, true, false, true));
        return true;
    }

    private boolean feast(Player player) {
        player.setFoodLevel(20);
        player.setSaturation(14f);
        player.setExhaustion(0f);
        return true;
    }

    private boolean fullHeal(Player player) {
        double max = player.getAttribute(Attribute.MAX_HEALTH) != null
                ? player.getAttribute(Attribute.MAX_HEALTH).getValue() : 20;
        player.setHealth(Math.max(1, max));
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        player.setFireTicks(0);
        return true;
    }

    private boolean randomTeleport(Player player) {
        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = 60 + random.nextDouble() * 240;
            Location loc = player.getLocation().clone()
                    .add(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);
            loc.setY(player.getWorld().getHighestBlockYAt(loc) + 1);
            if (!loc.getBlock().getType().isSolid() || loc.getY() > player.getWorld().getMaxHeight() - 2) {
                loc.add(0, -1, 0);
            }
            player.teleport(loc);
            return true;
        }
        return true;
    }

    private boolean reveal(Player player) {
        int revealed = 0;
        for (org.bukkit.entity.Entity entity : player.getNearbyEntities(25, 10, 25)) {
            if (entity instanceof LivingEntity living && !(entity instanceof Player)) {
                living.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 600, 0,
                        true, false, true));
                revealed++;
            }
        }
        plugin.messages().send(player, "revealed", "count", String.valueOf(revealed));
        return true;
    }

    private boolean enchantRoll(Player player, ItemStack target) {
        if (target == null || target.getType() == Material.AIR) {
            plugin.messages().send(player, "enchant-needs-item");
            return false;
        }
        EnchantBridge.Result result = EnchantBridge.roll(target);
        switch (result.status()) {
            case UNAVAILABLE -> {
                plugin.messages().send(player, "enchant-failed");
                return false;
            }
            case NO_ROOM -> {
                plugin.messages().send(player, "enchant-no-room");
                return false;
            }
            case OK -> {
                EnchantBridge.Rolled rolled = result.rolled();
                plugin.messages().send(player, "enchant-applied",
                        "name", rolled.name(), "level", roman(rolled.level()));
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private boolean darkBargain(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 300, 1, true, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 1200, 1, true, false, true));
        return true;
    }

    private boolean boneCall(Player player) {
        for (int i = 0; i < 2; i++) {
            Location loc = player.getLocation().clone().add(
                    random.nextDouble() * 6 - 3, 1, random.nextDouble() * 6 - 3);
            Skeleton skeleton = (Skeleton) player.getWorld().spawnEntity(loc, EntityType.SKELETON);
            skeleton.setTarget(player);
        }
        return true;
    }

    private boolean wildTeleport(Player player) {
        double angle = random.nextDouble() * Math.PI * 2;
        double dist = 10 + random.nextDouble() * 24;
        Location loc = player.getLocation().clone()
                .add(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);
        loc.setY(player.getWorld().getHighestBlockYAt(loc) + 1);
        player.teleport(loc.add(0, 1, 0));
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 100, 0, true, false, true));
        return true;
    }

    private boolean griefStorm(Player player) {
        player.getWorld().setStorm(true);
        player.getWorld().setThundering(true);
        player.getWorld().setWeatherDuration(20 * 120);
        player.getWorld().strikeLightning(player.getLocation());
        return true;
    }

    private boolean morokSting(Player player) {
        for (org.bukkit.entity.Entity entity : player.getNearbyEntities(10, 6, 10)) {
            if (entity instanceof LivingEntity living && !(entity instanceof Player)) {
                living.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 200, 3,
                        true, false, true));
            }
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 200, 0, true, false, true));
        return true;
    }

    private boolean nightClock(Player player) {
        player.getWorld().setTime(18000);
        player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 600, 0, true, false, true));
        return true;
    }

    private boolean soulCoupon(Player player) {
        player.giveExpLevels(3);
        if (player.getHealth() > 8) {
            player.setHealth(player.getHealth() - 8);
        } else {
            player.setHealth(Math.max(player.getHealth(), 1));
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 600, 1, true, false, true));
        return true;
    }

    /** Римское число уровня (1..10 достаточно покрывает весь реестр чар). */
    static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> String.valueOf(level);
        };
    }
}
