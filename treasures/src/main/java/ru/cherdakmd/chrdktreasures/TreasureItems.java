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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
        lore.add(net.kyori.adventure.text.Component.empty());
        lore.add(plugin.messages().get("item-tier-" + def.tier().id()));
        meta.lore(lore);

        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        if (def.tier() == TreasureTier.LEGENDARY || def.tier() == TreasureTier.MYTHICAL) {
            meta.setEnchantmentGlintOverride(true);
        }
        applyAttributes(meta, def);
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, def.id());
        item.setItemMeta(meta);
        return item;
    }

    private void applyAttributes(ItemMeta meta, TreasureItemDef def) {
        EquipmentSlotGroup slot = slotFor(def);
        for (TreasureEffect effect : def.effects()) {
            Attribute attribute = attributeOf(effect.kind());
            if (attribute == null) {
                continue;
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
        if (effect.kind() == TreasureEffect.Kind.REGENERATION
                || effect.kind() == TreasureEffect.Kind.WATER_BREATHING
                || effect.kind() == TreasureEffect.Kind.FIRE_RESISTANCE) {
            return effect.value() >= 2.0 ? "II" : "I";
        }
        double value = effect.value();
        if (Math.abs(value - Math.rint(value)) < 1e-9) {
            return String.valueOf((int) Math.rint(value));
        }
        return String.valueOf(Math.round(value * 100.0) / 100.0);
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
        return TreasureCatalog.find(idOf(item));
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
