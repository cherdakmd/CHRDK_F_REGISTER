package ru.cherdakmd.chrdkskills;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import ru.cherdakmd.chrdkregister.api.RankBonus;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Кристаллы опыта навыков: стакуемые расходники с использованием по правому
 * клику. Покупка в /skills выдаёт те же предметы, что и редкий дроп с игровых
 * действий и мобов.
 *
 * <p>Старые зелья опыта (метка {@code skill_xp_v1} на {@code Material.POTION})
 * остаются рабочими: правый клик тратит их мгновенно, как кристалл, а при
 * входе игрока нестакуемые зелья из инвентаря конвертируются в кристаллы.</p>
 */
final class SkillXpItems implements Listener {
    private final SkillsPlugin plugin;
    private final SkillXpStore store;
    private final NamespacedKey token;
    private final Random random = new Random();
    /** Серия действий без дропа по игрокам (мягкий pity); после рестарта начинается заново. */
    private final Map<UUID, Long> streaks = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID, Long> eldest) {
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

    /**
     * Стакуемый материал кристалла навыка — по теме его иконки в /skills.
     * Все материалы стакуются до 64 и не несут ванильного правого клика,
     * который имел бы смысл (саженец и незерский нарост сажаются — это гасит
     * обработчик кристалла).
     */
    static Material materialOf(SkillType skill) {
        return switch (skill) {
            case MINING -> Material.RAW_IRON;
            case WOODCUTTING -> Material.OAK_SAPLING;
            case DIGGING -> Material.CLAY_BALL;
            case FARMING -> Material.WHEAT;
            case FISHING -> Material.TROPICAL_FISH;
            case COMBAT -> Material.IRON_NUGGET;
            case ARCHERY -> Material.FEATHER;
            case DEFENSE -> Material.IRON_BARS;
            case ALCHEMY -> Material.NETHER_WART;
            case ENCHANTING -> Material.LAPIS_LAZULI;
        };
    }

    /** Навык предмета по PDC (кристалл или старое зелье); чужие предметы — {@code null}. */
    SkillType skill(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(token, PersistentDataType.STRING);
        return id == null ? null : SkillType.byId(id);
    }

    /** Кристалл опыта навыка: имя, заряд и подсказка использования. */
    ItemStack create(SkillType skill) {
        ItemStack item = new ItemStack(materialOf(skill));
        ItemMeta meta = item.getItemMeta();
        String name = plugin.messages().plain("skill." + skill.id() + ".name");
        meta.displayName(Component.text("Кристалл опыта: " + name, NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Базовый заряд: 250 XP", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Сила зависит от престижа и ранга", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("ПКМ — использовать", NamedTextColor.GREEN)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(token, PersistentDataType.STRING, skill.id());
        item.setItemMeta(meta);
        return item;
    }

    /** Использование правым кликом: мгновенный расход одной штуки из стака. */
    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        SkillType skill = skill(event.getItem());
        if (skill == null || player == null) {
            return;
        }
        // Не даём сработать ванильному правому клику (посадка, еда, блок и т.п.).
        event.setCancelled(true);
        use(player, skill, event.getHand());
    }

    /**
     * Старые зелья опыта: выпивание перехватывается и превращается в тот же
     * мгновенный расход. Новые кристаллы выпить нельзя — их правый клик
     * перехватывает {@link #onInteract}.
     */
    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        SkillType skill = skill(event.getItem());
        if (skill == null) {
            return;
        }
        event.setCancelled(true);
        use(player, skill, event.getHand());
    }

    /** Общий расход: проверки, пауза, опыт и списание одной штуки из руки. */
    private void use(Player player, SkillType skill, EquipmentSlot hand) {
        if (!enabled() || player.getGameMode() != GameMode.SURVIVAL
                || plugin.coreApi() == null || !plugin.coreApi().isAuthenticated(player)) {
            plugin.messages().send(player, "xp-potion-unavailable");
            return;
        }
        RankBonus rank = rank(player);
        long now = System.currentTimeMillis();
        long cooldown = store.cooldownLeft(player.getUniqueId(), now);
        if (cooldown > 0) {
            plugin.messages().send(player, "xp-potion-cooldown", "time", cooldown);
            return;
        }
        long xp = Math.round(SkillXpPolicy.xp(plugin.skills().rebirths(player), rank)
                * AttributeMath.xpFactor(plugin.skills().attribute(player, PlayerAttribute.WISDOM)));
        long cooldownMillis = Math.max(0, plugin.getConfig().getInt("xp-potions.cooldown-seconds", 0)) * 1000L;
        if (!store.consume(player.getUniqueId(), now, cooldownMillis)) {
            plugin.messages().send(player, "xp-potion-unavailable");
            return;
        }
        var award = plugin.skills().grantXp(player, skill, xp);
        if (award.xp() <= 0L) {
            plugin.messages().send(player, "xp-potion-unavailable");
            return;
        }
        ItemStack held = player.getInventory().getItem(hand);
        if (held != null && held.getAmount() > 0) {
            held.setAmount(held.getAmount() - 1);
        }
        plugin.messages().send(player, "xp-potion-used",
                "skill", plugin.messages().mini("skill." + skill.id() + ".name"), "xp", xp);
    }

    /**
     * Конвертация старых зелий в кристаллы при входе: зелья не стакуются и
     * засоряют инвентарь. Зелья в сундуках и шалкерах не трогаются — правый
     * клик тратит их так же, как кристалл.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Map<SkillType, Integer> legacy = new EnumMap<>(SkillType.class);
        var inventory = player.getInventory();
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            SkillType skill = legacyPotion(contents[slot]);
            if (skill == null) {
                continue;
            }
            legacy.merge(skill, contents[slot].getAmount(), Integer::sum);
            inventory.setItem(slot, null);
        }
        ItemStack offhand = inventory.getItemInOffHand();
        SkillType offhandSkill = legacyPotion(offhand);
        if (offhandSkill != null) {
            legacy.merge(offhandSkill, offhand.getAmount(), Integer::sum);
            inventory.setItemInOffHand(null);
        }
        if (legacy.isEmpty()) {
            return;
        }
        List<ItemStack> crystals = new ArrayList<>();
        for (Map.Entry<SkillType, Integer> entry : legacy.entrySet()) {
            int remaining = entry.getValue();
            while (remaining > 0) {
                ItemStack crystal = create(entry.getKey());
                crystal.setAmount(Math.min(remaining, 64));
                remaining -= crystal.getAmount();
                crystals.add(crystal);
            }
        }
        Map<Integer, ItemStack> leftover = inventory.addItem(crystals.toArray(ItemStack[]::new));
        for (ItemStack item : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
        }
    }

    /** Старое зелье опыта — метка PDC на нестакуемом {@code Material.POTION}. */
    private SkillType legacyPotion(ItemStack item) {
        return item != null && item.getType() == Material.POTION ? skill(item) : null;
    }

    /**
     * Дроп кристалла с игрового действия: добыча, рыбалка, варка, чары, защита.
     * Кристалл всегда соответствует навыку действия; серия без дропа и pity
     * общие на игрока — лимитов на количество кристаллов нет.
     */
    void rollDrop(Player player, SkillType skill, double tier) {
        if (!enabled() || player == null || skill == null) return;
        long streak = streaks.merge(player.getUniqueId(), 1L, Long::sum);
        boolean pity = SkillXpPolicy.pityReached(streak);
        if (!pity && random.nextDouble() >= SkillXpPolicy.dropChance(streak, tier, rank(player))) return;
        streaks.remove(player.getUniqueId());
        player.getWorld().dropItemNaturally(player.getLocation(), create(skill));
    }

    /** Дроп с мобов: боевой кристалл по способу убийства — «Стрельба» или «Ближний бой». */
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
