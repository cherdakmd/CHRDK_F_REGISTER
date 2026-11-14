package ru.cherdakmd.chrdkskills;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Boss;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;
import ru.cherdakmd.chrdkregister.api.RankBonus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Настоящие PDC-зелья XP: покупка в /skills выдаёт те же предметы, что и дроп с мобов. */
final class SkillXpItems implements Listener {
    private final SkillsPlugin plugin;
    private final SkillXpStore store;
    private final NamespacedKey token;
    private final Random random = new Random();
    /** Серия убийств без дропа по игрокам (мягкий pity); после рестарта начинается заново. */
    private final Map<java.util.UUID, Long> streaks = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<java.util.UUID, Long> eldest) {
            return size() > 10_000;
        }
    };

    SkillXpItems(SkillsPlugin plugin, SkillXpStore store) {
        this.plugin = plugin;
        this.store = store;
        token = new NamespacedKey(plugin, "skill_xp_v1");
    }

    private boolean enabled() {
        return plugin.config().enabled() && store.available()
                && plugin.getConfig().getBoolean("xp-potions.enabled", true);
    }

    SkillType skill(ItemStack item) {
        if (item == null || item.getType() != Material.POTION || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(token, PersistentDataType.STRING);
        return id == null ? null : SkillType.byId(id);
    }

    ItemStack create(SkillType skill) {
        ItemStack item = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) item.getItemMeta();
        meta.setBasePotionType(PotionType.WATER);
        meta.setColor(Color.fromRGB(80 + skill.ordinal() * 17 % 176, 190, 235 - skill.ordinal() * 13 % 150));
        String name = plugin.messages().plain("skill." + skill.id() + ".name");
        meta.displayName(Component.text("Зелье опыта: " + name, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Базовый заряд: 250 XP", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Сила зависит от престижа и ранга", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(token, PersistentDataType.STRING, skill.id());
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        SkillType skill = skill(event.getItem());
        if (skill == null) return;
        event.setCancelled(true);
        if (!enabled() || player.getGameMode() != GameMode.SURVIVAL
                || plugin.coreApi() == null || !plugin.coreApi().isAuthenticated(player)) {
            plugin.messages().send(player, "xp-potion-unavailable"); return;
        }
        RankBonus rank = rank(player);
        long now = System.currentTimeMillis();
        long cooldown = store.cooldownLeft(player.getUniqueId(), now);
        if (cooldown > 0) {
            plugin.messages().send(player, "xp-potion-cooldown", "time", cooldown); return;
        }
        long xp = SkillXpPolicy.xp(plugin.skills().totalPrestige(player), rank);
        long cooldownMillis = Math.max(0, plugin.getConfig().getInt("xp-potions.cooldown-seconds", 0)) * 1000L;
        if (!store.consume(player.getUniqueId(), now, cooldownMillis)) {
            plugin.messages().send(player, "xp-potion-unavailable"); return;
        }
        var award = plugin.skills().grantXp(player, skill, xp);
        if (award.xp() <= 0L) {
            plugin.messages().send(player, "xp-potion-unavailable"); return;
        }
        ItemStack consumed = event.getItem().clone();
        consumed.setAmount(1);
        if (!player.getInventory().removeItem(consumed).isEmpty()) return;
        plugin.messages().send(player, "xp-potion-used", "skill", plugin.messages().mini("skill." + skill.id() + ".name"),
                "xp", xp);
    }

    /**
     * Дроп зелья с игрового действия: добыча, рыбалка, варка, чары, защита.
     * Зелье всегда соответствует навыку действия; серия без дропа и pity
     * общие на игрока — лимитов на количество зелий нет.
     */
    void rollDrop(Player player, SkillType skill, double tier) {
        if (!enabled() || player == null || skill == null) return;
        long streak = streaks.merge(player.getUniqueId(), 1L, Long::sum);
        boolean pity = SkillXpPolicy.pityReached(streak);
        if (!pity && random.nextDouble() >= SkillXpPolicy.dropChance(streak, tier, rank(player))) return;
        streaks.remove(player.getUniqueId());
        player.getWorld().dropItemNaturally(player.getLocation(), create(skill));
    }

    /** Дроп с мобов: боевое зелье по способу убийства — «Стрельба» или «Ближний бой». */
    void rollDrop(Player killer, LivingEntity entity) {
        if (entity.hasMetadata("NPC")) return;
        rollDrop(killer, rangedKill(entity) ? SkillType.ARCHERY : SkillType.COMBAT, mobTier(entity));
    }

    /** Убийство снарядом (лук, арбалет, трезубец) — «Стрельба», иначе «Ближний бой». */
    private static boolean rangedKill(LivingEntity entity) {
        return entity.getLastDamageCause() instanceof EntityDamageByEntityEvent byEntity
                && byEntity.getDamager() instanceof Projectile;
    }

    /**
     * Тир моба для шанса дропа: 2,0 — босс или больше 60 HP; 1,5 — сильный
     * (больше 20 HP); 1,0 — обычный. Именованные мобы не дают бонус: имя
     * ставится биркой и открывало бы фарм через переименование.
     */
    static double mobTier(LivingEntity entity) {
        if (entity instanceof Boss) return 2.0;
        var health = entity.getAttribute(Attribute.MAX_HEALTH);
        double hp = health == null ? 20.0 : health.getValue();
        return hp > 60.0 ? 2.0 : hp > 20.0 ? 1.5 : 1.0;
    }

    private RankBonus rank(Player player) {
        try {
            RankBonus rank = plugin.coreApi() == null ? RankBonus.NONE : plugin.coreApi().rankBonus(player.getName());
            return rank == null ? RankBonus.NONE : rank;
        } catch (RuntimeException | LinkageError error) {
            return RankBonus.NONE;
        }
    }
}
