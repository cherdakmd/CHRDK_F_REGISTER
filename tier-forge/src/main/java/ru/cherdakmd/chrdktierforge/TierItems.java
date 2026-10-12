package ru.cherdakmd.chrdktierforge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Применение тира к предмету: PDC-метка, модификаторы атрибутов, окраска имени
 * (translatable — следует языку клиента), лор-плашка, блеск у легенд.
 * Только серверный рантайм.
 */
public final class TierItems {

    public static final String PDC_KEY = "item_tier";

    private final TierForgePlugin plugin;
    private final NamespacedKey key;

    public TierItems(TierForgePlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, PDC_KEY);
    }

    /** Читает id тира предмета (null — предмет без тира). */
    public String tierOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        String tier = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return TierDef.isTier(tier) ? tier : null;
    }

    /** Полное применение тира: модификаторы, PDC, имя, лор, блеск. */
    public void apply(ItemStack item, TierDef.Snapshot tier) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, tier.id());

        clearTierModifiers(meta);
        applyModifiers(item, meta, tier);
        decorateName(item, meta, tier);
        replaceLore(meta, tier);
        if ("legendary".equals(tier.id())) {
            meta.setEnchantmentGlintOverride(true);
        }
        item.setItemMeta(meta);
    }

    /** Снимает тир (модификаторы, метка, блеск нашего происхождения). */
    public void clear(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().remove(key);
        clearTierModifiers(meta);
        item.setItemMeta(meta);
    }

    /* ------------------------------- атрибуты ------------------------------- */

    private void applyModifiers(ItemStack item, ItemMeta meta, TierDef.Snapshot tier) {
        ItemKind kind = ItemKind.classify(item.getType().name());
        if (kind == null) return;
        int percent = tier.percent();
        if (percent == 0) return;
        EquipmentSlotGroup slot = slotGroupFor(kind);
        if (kind.isWeapon()) {
            add(meta, Attribute.ATTACK_DAMAGE, "attack", TierMath.damageScalar(percent), slot);
        }
        if (kind.isArmor()) {
            add(meta, Attribute.ARMOR, "armor", TierMath.defenseScalar(percent) * 0.5, slot);
            add(meta, Attribute.ARMOR_TOUGHNESS, "toughness",
                    TierMath.toughnessScalar(percent), slot);
            if (percent > 0) {
                add(meta, Attribute.MOVEMENT_SPEED, "movespeed",
                        TierMath.movementScalar(percent), slot);
            }
        }
        if (kind.isTool()) {
            add(meta, Attribute.ATTACK_DAMAGE, "attack_tool",
                    TierMath.damageScalar(percent), slot);
            add(meta, Attribute.ATTACK_SPEED, "attackspeed",
                    TierMath.attackSpeedScalar(percent), slot);
        }
    }

    private void add(ItemMeta meta, Attribute attribute, String tag, double amount,
                     EquipmentSlotGroup slot) {
        AttributeModifier modifier = new AttributeModifier(modifierKey(tag), amount,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1, slot);
        meta.addAttributeModifier(attribute, modifier);
    }

    private NamespacedKey modifierKey(String tag) {
        return new NamespacedKey(plugin, "tier_" + tag);
    }

    private void clearTierModifiers(ItemMeta meta) {
        for (String tag : List.of("attack", "armor", "toughness", "movespeed",
                "attack_tool", "attackspeed")) {
            for (Attribute attribute : List.of(Attribute.ATTACK_DAMAGE, Attribute.ARMOR,
                    Attribute.ARMOR_TOUGHNESS, Attribute.MOVEMENT_SPEED, Attribute.ATTACK_SPEED)) {
                meta.removeAttributeModifier(attribute, modifierKey(tag));
            }
        }
        // снятие нашего блеска: если глайнт стоял через оверрайд — вернуть к стандартному
        if (Boolean.TRUE.equals(meta.getEnchantmentGlintOverride())) {
            meta.setEnchantmentGlintOverride(null);
        }
    }

    private EquipmentSlotGroup slotGroupFor(ItemKind kind) {
        return switch (kind) {
            case HELMET -> EquipmentSlotGroup.HEAD;
            case CHESTPLATE -> EquipmentSlotGroup.CHEST;
            case LEGGINGS -> EquipmentSlotGroup.LEGS;
            case BOOTS -> EquipmentSlotGroup.FEET;
            default -> EquipmentSlotGroup.MAINHAND;
        };
    }

    /* ------------------------------- имя и лор ------------------------------- */

    private void decorateName(ItemStack item, ItemMeta meta, TierDef.Snapshot tier) {
        String color = plugin.messages().raw("tier." + tier.id() + ".color");
        String translation = "item.minecraft."
                + item.getType().name().toLowerCase(Locale.ROOT);
        Component name = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                .deserialize(color + "<lang:" + translation + ">");
        if (meta.hasCustomName() && meta.customName() != null) {
            // кастомное имя игрока сохраняем, красим в цвет тира
            Component colored = net.kyori.adventure.text.minimessage.MiniMessage
                    .miniMessage().deserialize(color).append(meta.customName());
            meta.displayName(colored);
        } else {
            meta.displayName(name);
        }
    }

    private void replaceLore(ItemMeta meta, TierDef.Snapshot tier) {
        List<Component> lore = meta.hasLore() && meta.lore() != null
                ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.removeIf(this::isTierLine);
        lore.add(tierLine(tier));
        meta.lore(lore);
    }

    /** Цветная плашка тира в лоре (собирается напрямую: цвет — тег MiniMessage). */
    private Component tierLine(TierDef.Snapshot tier) {
        String color = plugin.messages().raw("tier." + tier.id() + ".color");
        String name = plugin.messages().raw("tier." + tier.id() + ".name");
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(color + name);
    }

    private boolean isTierLine(Component line) {
        String plain = PlainTextComponentSerializer.plainText().serialize(line);
        for (String id : TierDef.LADDER) {
            if (plain.startsWith(plugin.messages().raw("tier." + id + ".name"))) {
                return true;
            }
        }
        return false;
    }
}
