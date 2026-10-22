package ru.cherdakmd.chrdktreasures;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkTierApi;
import ru.cherdakmd.chrdkregister.item.ItemAttributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Сборка и распознавание именных находок.
 *
 * <p>Атрибутные эффекты запекаются в мету предмета: пока предмет в руке или
 * надет, Minecraft сам считает бонус, и плагину не нужен пульс. Ключ
 * модификатора включает ид предмета и вид эффекта — две находки с одним видом
 * бонуса не затирают друг друга (у брони такой баг был бы особенно заметен).</p>
 *
 * <p>Событийные эффекты (вампиризм, поджог, двойная руда, ауры) остаются на
 * стороне слушателей: их нельзя выразить атрибутом.</p>
 */
public final class TreasureItems {

    private static final Map<String, EquipmentSlotGroup> SLOTS = slotGroups();

    private final TreasuresPlugin plugin;
    private final NamespacedKey idKey;
    /** 1.4.141: тир находки — бросок на каждый созданный предмет. */
    private final Random random = new Random();

    public TreasureItems(TreasuresPlugin plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "treasure_id");
    }

    private static Map<String, EquipmentSlotGroup> slotGroups() {
        Map<String, EquipmentSlotGroup> out = new LinkedHashMap<>();
        out.put("HELMET", EquipmentSlotGroup.HEAD);
        out.put("CHESTPLATE", EquipmentSlotGroup.CHEST);
        out.put("LEGGINGS", EquipmentSlotGroup.LEGS);
        out.put("BOOTS", EquipmentSlotGroup.FEET);
        out.put("SHIELD", EquipmentSlotGroup.OFFHAND);
        return out;
    }

    /** Собирает предмет по каталогу. {@code null}, если материал неизвестен. */
    public ItemStack create(TreasureItemDef def) {
        return create(def, true);
    }

    /**
     * Собирает предмет по каталогу.
     *
     * @param withTier накладывать ли тир CHRDK_TierForge по шансу редкости;
     *                 витрина передаёт {@code false}, чтобы не переролливать
     *                 редкость на каждое открытие меню
     */
    public ItemStack create(TreasureItemDef def, boolean withTier) {
        if (def == null) {
            return null;
        }
        Material material = Material.matchMaterial(def.material());
        if (material == null || material.isAir()) {
            plugin.getLogger().warning("[CHRDK_Treasures] Неизвестный материал " + def.material()
                    + " у находки " + def.id());
            return null;
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        String name = plugin.messages().raw("treasure." + def.id() + ".name");
        meta.displayName(plugin.messages().get("item-name-" + def.tier().id(),
                "name", Messages.trusted(name)));

        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        String desc = plugin.messages().raw("treasure." + def.id() + ".desc");
        if (!desc.isBlank()) {
            lore.add(plugin.messages().get("item-desc", "text", Messages.trusted(desc)));
            lore.add(net.kyori.adventure.text.Component.empty());
        }
        for (TreasureEffect effect : def.effects()) {
            lore.add(plugin.messages().get("item-effect-" + key(effect.kind()),
                    "value", format(effect)));
        }
        if (def.ability() != null) {
            Messages.Trusted abilityName = Messages.trusted(plugin.messages().raw(
                    def.ability().kind().textKey()));
            String abilityKey = bowLike(def.material()) ? "item-ability-bow" : "item-ability";
            lore.add(plugin.messages().get(abilityKey,
                    "name", abilityName,
                    "cooldown", String.valueOf(def.ability().cooldownSeconds())));
        }
        if (def.curse() != null) {
            lore.add(plugin.messages().get("item-curse",
                    "name", Messages.trusted(plugin.messages().raw(
                            def.curse().kind().textKey()))));
        }
        if (def.region() != null) {
            lore.add(plugin.messages().get("item-set",
                    "set", Messages.trusted(plugin.messages().raw(
                            "region." + def.region().id())),
                    "pieces", String.valueOf(TreasureSet.FULL_PIECES)));
        }
        lore.add(net.kyori.adventure.text.Component.empty());
        lore.add(plugin.messages().get("item-tier-" + def.tier().id()));
        meta.lore(lore);

        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        if (def.tier() == TreasureTier.LEGENDARY || def.tier() == TreasureTier.MYTHICAL) {
            meta.setEnchantmentGlintOverride(true);
        }
        applyAttributes(item, meta, def);
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, def.id());
        item.setItemMeta(meta);
        if (withTier) {
            applyTier(item, def);
        }
        return item;
    }

    /**
     * Накладывает тир по шансу редкости находки (1.4.141).
     *
     * <p>Шанс живёт в {@code tiers.chance-percent.<редкость>}: обычная находка
     * получает тир в 20 % случаев, мифическая — всегда. Тир-фордж может быть
     * не установлен или выключен: тогда предмет просто остаётся без тира, и
     * клад работает как раньше.</p>
     */
    private void applyTier(ItemStack item, TreasureItemDef def) {
        TreasuresConfig config = plugin.config();
        if (config == null || !config.tiersEnabled() || item == null) {
            return;
        }
        double chance = config.tierChance(def.tier());
        if (chance <= 0.0 || random.nextDouble() * 100.0 >= chance) {
            return;
        }
        ChrdkTierApi api = plugin.tierApi();
        if (api == null) {
            return;
        }
        api.applyRandomTier(item, "treasure");
    }

    /**
     * Предмет из лут-таблицы с особым именем: «Кровавый суп» остаётся миской,
     * но приходит со своим названием и пометкой, что это находка из клада.
     * Ид в PDC не ставится — такие предметы не входят в коллекцию.
     */
    public ItemStack named(Material material, String name, int amount) {
        if (material == null || material.isAir() || name == null || name.isBlank()) {
            return null;
        }
        ItemStack item = new ItemStack(material, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(plugin.messages().get("loot-item-name",
                "name", Messages.escape(name)));
        meta.lore(List.of(plugin.messages().get("loot-item-lore")));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Атрибутные эффекты находки. Перед первым своим модификатором в мету
     * возвращаются ванильные значения материала (1.4.140): компонент атрибутов
     * пишется целиком, и без этого базовый урон меча/топора терялся — предмет
     * с «+12% к урону» бил на «полсердечка».
     */
    private void applyAttributes(ItemStack item, ItemMeta meta, TreasureItemDef def) {
        EquipmentSlotGroup slot = slotFor(def);
        boolean defaultsKept = false;
        for (TreasureEffect effect : def.effects()) {
            Attribute attribute = attributeOf(effect.kind());
            if (attribute == null) {
                continue;
            }
            if (!defaultsKept) {
                ItemAttributes.keep(item, meta);
                defaultsKept = true;
            }
            AttributeModifier.Operation operation = effect.kind().percent()
                    ? AttributeModifier.Operation.MULTIPLY_SCALAR_1
                    : AttributeModifier.Operation.ADD_NUMBER;
            NamespacedKey key = new NamespacedKey(plugin,
                    "treasure_" + def.id() + "_" + key(effect.kind()));
            try {
                meta.addAttributeModifier(attribute,
                        new AttributeModifier(key, effect.value(), operation, slot));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("[CHRDK_Treasures] Атрибут " + attribute
                        + " отклонён у " + def.id() + ": " + exception.getMessage());
            }
        }
    }

    /** Лук и арбалет: их способность срабатывает только в приседе, иначе ломается выстрел. */
    public static boolean bowLike(String material) {
        return material != null && (material.endsWith("_BOW") || material.equals("BOW")
                || material.equals("CROSSBOW"));
    }

    private static EquipmentSlotGroup slotFor(TreasureItemDef def) {
        String name = def.material().toUpperCase(Locale.ROOT);
        for (Map.Entry<String, EquipmentSlotGroup> entry : SLOTS.entrySet()) {
            if (name.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return EquipmentSlotGroup.MAINHAND;
    }

    private static Attribute attributeOf(TreasureEffect.Kind kind) {
        return switch (kind) {
            case ATTACK_DAMAGE -> Attribute.ATTACK_DAMAGE;
            case ARMOR -> Attribute.ARMOR;
            case ARMOR_TOUGHNESS -> Attribute.ARMOR_TOUGHNESS;
            case MAX_HEALTH -> Attribute.MAX_HEALTH;
            case MOVEMENT_SPEED -> Attribute.MOVEMENT_SPEED;
            case MINING_SPEED -> Attribute.BLOCK_BREAK_SPEED;
            case LUCK -> Attribute.LUCK;
            case KNOCKBACK_RESISTANCE -> Attribute.KNOCKBACK_RESISTANCE;
            default -> null;
        };
    }

    /** Текст величины для лора: доли — процентами, остальное — числом. */
    public static String format(TreasureEffect effect) {
        if (effect.kind().percent()) {
            return Math.round(effect.value() * 100.0) + " %";
        }
        if (effect.kind().aura()) {
            int level = Math.max(1, (int) Math.round(effect.value()));
            return level >= 3 ? "III" : level == 2 ? "II" : "I";
        }
        double value = effect.value();
        if (Math.abs(value - Math.rint(value)) < 1e-9) {
            return String.valueOf((int) Math.rint(value));
        }
        return String.valueOf(Math.round(value * 100.0) / 100.0);
    }

    /**
     * Возвращает находке ванильную базу атрибутов, если она потерялась до
     * 1.4.140. Чинит уже выданные предметы: вызывается при входе игрока.
     *
     * @return {@code true} — предмет изменён
     */
    public boolean repairAttributes(ItemStack item) {
        if (item == null || idOf(item) == null) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !ItemAttributes.keep(item, meta)) {
            return false;
        }
        item.setItemMeta(meta);
        return true;
    }

    /** Чинит все находки в инвентаре игрока (включая броню и левую руку). */
    public boolean repairAttributes(Player player) {
        if (player == null) {
            return false;
        }
        boolean changed = false;
        for (ItemStack item : player.getInventory().getContents()) {
            if (repairAttributes(item)) {
                changed = true;
            }
        }
        return changed;
    }

    /** Ид находки в предмете или {@code null}. */
    public String idOf(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    /** Определение находки по предмету или {@code null}. */
    public TreasureItemDef defOf(ItemStack item) {
        return TreasureRegistry.find(idOf(item));
    }

    /**
     * Находки в инвентаре: рюкзак, броня, руки. Нужны сетам — четыре предмета
     * одного региона не обязаны быть надеты, их можно нести с собой.
     * Дубликаты одного ида не учитываются дважды.
     */
    public List<TreasureItemDef> carried(Player player) {
        if (player == null) {
            return List.of();
        }
        Map<String, TreasureItemDef> found = new LinkedHashMap<>();
        for (ItemStack item : player.getInventory().getContents()) {
            collect(found, item);
        }
        for (ItemStack item : player.getInventory().getArmorContents()) {
            collect(found, item);
        }
        collect(found, player.getInventory().getItemInOffHand());
        collect(found, player.getInventory().getItemInMainHand());
        return List.copyOf(found.values());
    }

    /**
     * Находки в руках и на броне. Дубликаты одного ида не учитываются дважды:
     * второй такой же клинок в инвентаре не должен усиливать вампиризм.
     */
    public List<TreasureItemDef> equipped(Player player) {
        if (player == null) {
            return List.of();
        }
        Map<String, TreasureItemDef> found = new LinkedHashMap<>();
        collect(found, player.getInventory().getItemInMainHand());
        collect(found, player.getInventory().getItemInOffHand());
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            collect(found, armor);
        }
        return List.copyOf(found.values());
    }

    private void collect(Map<String, TreasureItemDef> found, ItemStack item) {
        TreasureItemDef def = defOf(item);
        if (def != null) {
            found.putIfAbsent(def.id(), def);
        }
    }

    /** Суммарная величина эффекта по всем надетым находкам. */
    public double sum(Player player, TreasureEffect.Kind kind) {
        double total = 0.0;
        for (TreasureItemDef def : equipped(player)) {
            total += def.value(kind);
        }
        return total;
    }

    /** Есть ли эффект на любой надетой находке. */
    public boolean worn(Player player, TreasureEffect.Kind kind) {
        for (TreasureItemDef def : equipped(player)) {
            if (def.has(kind)) {
                return true;
            }
        }
        return false;
    }

    private static String key(TreasureEffect.Kind kind) {
        return kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
