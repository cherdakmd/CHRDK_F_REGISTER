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
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import ru.cherdakmd.chrdkenchants.EnchantService;
import ru.cherdakmd.chrdkenchants.EnchantsPlugin;

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

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
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
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND && event.getHand() != EquipmentSlot.OFF_HAND) {
            return;
        }
        Player player = event.getPlayer();
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
        plugin.messages().send(player, "activated",
                "name", plugin.messages().raw("art." + id + ".name"));
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
        };
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
        EnchantsPlugin enchants = EnchantsPlugin.getPlugin(EnchantsPlugin.class);
        EnchantService service = enchants == null ? null : enchants.service();
        if (service == null) {
            plugin.messages().send(player, "enchant-failed");
            return false;
        }
        EnchantService.RollResult roll = service.rollOnto(target, 2);
        if (roll == null) {
            plugin.messages().send(player, "enchant-no-room");
            return false;
        }
        String name = enchants.messages().raw("en." + roll.def().id() + ".name");
        player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                .deserialize("<light_purple>✦ Хартия вписала: <yellow>" + name
                        + " <white>" + roman(roll.level()) + "</white></yellow></light_purple>"));
        return true;
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
