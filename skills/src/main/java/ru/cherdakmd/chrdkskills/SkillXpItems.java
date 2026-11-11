package ru.cherdakmd.chrdkskills;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;
import ru.cherdakmd.chrdkregister.api.ForgeMaterialSource;
import ru.cherdakmd.chrdkregister.api.RankBonus;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Настоящие PDC-зелья XP: магазин выдаёт те же предметы, что и дроп с мобов. */
final class SkillXpItems implements Listener, ForgeMaterialSource {
    static final String SKU_PREFIX = "skill_xp_";
    private final SkillsPlugin plugin;
    private final SkillXpStore store;
    private final NamespacedKey token;
    private final Random random = new Random();

    SkillXpItems(SkillsPlugin plugin, SkillXpStore store) {
        this.plugin = plugin;
        this.store = store;
        token = new NamespacedKey(plugin, "skill_xp_v1");
    }

    @Override public String provider() { return "CHRDK_Skills"; }
    @Override public boolean available(String sku) {
        return enabled() && skill(sku) != null;
    }
    @Override public ItemStack prototype(String sku) {
        SkillType skill = skill(sku);
        return skill == null ? null : create(skill);
    }

    private boolean enabled() {
        return plugin.config().enabled() && store.available()
                && plugin.getConfig().getBoolean("xp-potions.enabled", true);
    }

    private SkillType skill(String sku) {
        return sku == null || !sku.startsWith(SKU_PREFIX) ? null : SkillType.byId(sku.substring(SKU_PREFIX.length()));
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
                Component.text("Сила и лимит зависят от прогресса и ранга", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(token, PersistentDataType.STRING, skill.id());
        item.setItemMeta(meta);
        return item;
    }

    int dailyLimit(Player player, RankBonus rank) {
        return SkillXpPolicy.dailyUses(plugin.skills().totalLevel(player), plugin.skills().totalPrestige(player), rank);
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
        int limit = dailyLimit(player, rank);
        long now = System.currentTimeMillis();
        long cooldown = store.cooldownLeft(player.getUniqueId(), now);
        if (store.used(player.getUniqueId()) >= limit) {
            plugin.messages().send(player, "xp-potion-daily-limit", "limit", limit); return;
        }
        if (cooldown > 0) {
            plugin.messages().send(player, "xp-potion-cooldown", "time", cooldown); return;
        }
        long xp = SkillXpPolicy.xp(plugin.skills().totalPrestige(player), rank);
        if (!store.consume(player.getUniqueId(), limit, now, 60_000L)) {
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
                "xp", xp, "left", Math.max(0, limit - store.used(player.getUniqueId())));
    }

    void rollDrop(Player killer, LivingEntity entity) {
        if (!enabled() || entity.hasMetadata("NPC")) return;
        RankBonus rank = rank(killer);
        double baseChance = plugin.getConfig().getDouble("xp-potions.mob-drop-chance", 0.002);
        double chance = Math.min(0.01, baseChance * SkillXpPolicy.dropChance(rank) / 0.002);
        if (random.nextDouble() >= chance) return;
        SkillType skill = SkillType.values()[random.nextInt(SkillType.values().length)];
        entity.getWorld().dropItemNaturally(entity.getLocation(), create(skill));
    }

    private RankBonus rank(Player player) {
        try {
            RankBonus rank = plugin.coreApi() == null ? RankBonus.NONE : plugin.coreApi().rankBonus(player.getName());
            return rank == null ? RankBonus.NONE : rank;
        } catch (RuntimeException | LinkageError error) {
            return RankBonus.NONE;
        }
    }

    static String sku(SkillType skill) { return SKU_PREFIX + skill.id().toLowerCase(Locale.ROOT); }
}
