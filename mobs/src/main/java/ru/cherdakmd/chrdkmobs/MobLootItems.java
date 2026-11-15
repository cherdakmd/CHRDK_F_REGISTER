package ru.cherdakmd.chrdkmobs;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Необычный лут мобов: эссенции навыков и тематические сущности вариантов.
 * Использование (правый клик) даёт опыт навыка для прокачки персонажа,
 * сущности вариантов сверх того накладывают полезный эффект. Предметы
 * помечены PDC и без этой пометки не работают.
 */
final class MobLootItems implements Listener {

    /** Виды лута: материал, ключи текстов, навыки для опыта и полезный эффект. */
    enum Kind {
        ESSENCE_COMBAT("essence-combat", Material.BLAZE_POWDER, List.of("combat", "archery", "defense")),
        ESSENCE_GATHER("essence-gather", Material.AMETHYST_SHARD,
                List.of("mining", "woodcutting", "digging", "farming", "fishing")),
        ESSENCE_CRAFT("essence-craft", Material.GLOWSTONE_DUST, List.of("alchemy", "enchanting")),
        VARIANT_CURSED("variant-cursed", Material.ECHO_SHARD, List.of("enchanting")),
        VARIANT_SMOLDERING("variant-smoldering", Material.MAGMA_CREAM, List.of("combat")),
        VARIANT_GROSS("variant-gross", Material.SLIME_BALL, List.of("farming")),
        VARIANT_CRINGE("variant-cringe", Material.GLOW_INK_SAC, List.of("fishing"));

        final String id;
        final Material material;
        final List<String> skills;

        Kind(String id, Material material, List<String> skills) {
            this.id = id;
            this.material = material;
            this.skills = skills;
        }

        /** Сущность варианта (с эффектом) или обычная эссенция навыков. */
        boolean variant() {
            return id.startsWith("variant-");
        }

        /** Полезный эффект сущности варианта; эссенции — {@code null}. */
        PotionEffectType effect() {
            return switch (this) {
                case VARIANT_CURSED -> PotionEffectType.RESISTANCE;
                case VARIANT_SMOLDERING -> PotionEffectType.FIRE_RESISTANCE;
                case VARIANT_GROSS -> PotionEffectType.REGENERATION;
                case VARIANT_CRINGE -> PotionEffectType.SPEED;
                default -> null;
            };
        }

        /** Длительность эффекта в секундах. */
        int durationSeconds() {
            return switch (this) {
                case VARIANT_CURSED -> 90;
                case VARIANT_SMOLDERING -> 180;
                case VARIANT_GROSS -> 8;
                case VARIANT_CRINGE -> 60;
                default -> 0;
            };
        }

        /** Усиление эффекта: 0 — I, 1 — II. */
        int amplifier() {
            return this == VARIANT_GROSS || this == VARIANT_CRINGE ? 1 : 0;
        }

        static Kind byId(String id) {
            if (id == null) {
                return null;
            }
            for (Kind kind : values()) {
                if (kind.id.equals(id)) {
                    return kind;
                }
            }
            return null;
        }
    }

    private final MobsPlugin plugin;
    private final NamespacedKey lootKey;

    MobLootItems(MobsPlugin plugin) {
        this.plugin = plugin;
        this.lootKey = new NamespacedKey(plugin, "loot-v1");
    }

    /** Предмет лута с именем и лором из messages.yml. */
    ItemStack create(Kind kind, int count) {
        ItemStack item = new ItemStack(kind.material, Math.max(1, count));
        ItemMeta meta = item.getItemMeta();
        long xp = kind.variant() ? plugin.config().loot().variantXp() : plugin.config().loot().essenceXp();
        meta.displayName(plugin.messages().get("loot." + kind.id + ".name"));
        meta.lore(plugin.messages().getList("loot." + kind.id + ".lore", "amount", xp));
        meta.getPersistentDataContainer().set(lootKey, PersistentDataType.STRING, kind.id);
        item.setItemMeta(meta);
        return item;
    }

    /** Вид лута по PDC; чужие предметы — {@code null}. */
    Kind kind(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String id = item.getItemMeta().getPersistentDataContainer()
                .get(lootKey, PersistentDataType.STRING);
        return Kind.byId(id);
    }

    /** Использование: опыт навыка (+ полезный эффект у сущностей вариантов). */
    @EventHandler(ignoreCancelled = true)
    public void use(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        Kind kind = kind(item);
        Player player = event.getPlayer();
        if (kind == null || player == null || plugin.config() == null) {
            return;
        }
        long xp = kind.variant() ? plugin.config().loot().variantXp() : plugin.config().loot().essenceXp();
        String skill = kind.skills.get(ThreadLocalRandom.current().nextInt(kind.skills.size()));
        if (SkillsHook.addXp(plugin.getLogger(), player, skill, xp) && plugin.messages() != null) {
            player.sendActionBar(plugin.messages().get("loot-used", "amount", xp));
        }
        if (kind.effect() != null) {
            player.addPotionEffect(new PotionEffect(kind.effect(),
                    kind.durationSeconds() * 20, kind.amplifier()));
        }
        ItemStack hand = player.getInventory().getItem(event.getHand());
        if (hand != null && hand.getAmount() > 0) {
            hand.setAmount(hand.getAmount() - 1);
        }
        // Не даём сработать ванильному правому клику (установка блока и т.п.).
        event.setCancelled(true);
    }

    /** Случайная эссенция навыков для дропа обычного моба. */
    static Kind randomEssence() {
        Kind[] essences = {Kind.ESSENCE_COMBAT, Kind.ESSENCE_GATHER, Kind.ESSENCE_CRAFT};
        return essences[ThreadLocalRandom.current().nextInt(essences.length)];
    }

    /** Вид лута по идентификатору варианта ({@link MobVariant#lootId()}). */
    static Kind forVariant(MobVariant variant) {
        return Kind.byId(variant.lootId());
    }

    /** Сколько предметов лута: диапазон тира (min..max, включительно). */
    static int count(int min, int max) {
        int low = Math.max(1, Math.min(min, max));
        int high = Math.max(low, Math.max(min, max));
        return low == high ? low : low + ThreadLocalRandom.current().nextInt(high - low + 1);
    }
}
