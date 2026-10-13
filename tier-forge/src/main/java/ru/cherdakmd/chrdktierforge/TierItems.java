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
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Применение тира к предмету: PDC-метка, модификаторы атрибутов, окраска имени
 * (translatable — следует языку клиента), лор-плашка, блеск у легенд.
 * Только серверный рантайм.
 */
public final class TierItems {

    public static final String PDC_KEY = "item_tier";
    /** Флаг «неопознанного» предмета: редкость скрыта до попадания в инвентарь. */
    public static final String PDC_UNIDENTIFIED = "item_unidentified";
    /** Источник выдачи неопознанного предмета ("craft" / "drop") — нужен для статистики. */
    public static final String PDC_SOURCE = "item_tier_source";

    private final TierForgePlugin plugin;
    private final NamespacedKey key;
    private final NamespacedKey unidentifiedKey;
    private final NamespacedKey sourceKey;

    public TierItems(TierForgePlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, PDC_KEY);
        this.unidentifiedKey = new NamespacedKey(plugin, PDC_UNIDENTIFIED);
        this.sourceKey = new NamespacedKey(plugin, PDC_SOURCE);
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

    /** Предмет — «неопознанный» (редкость скрыта до попадания в инвентарь)? */
    public boolean isUnidentified(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(unidentifiedKey, PersistentDataType.STRING);
    }

    /** Читает источник строкой ("craft"/"drop", пусто — не задан). */
    public String unidentifiedSource(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return "";
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return "";
        String source = meta.getPersistentDataContainer().get(sourceKey, PersistentDataType.STRING);
        return source == null ? "" : source;
    }

    /** Пометить как неопознанный: стираем «настоящее» имя и даём лор-подсказку. */
    public void markUnidentified(ItemStack item, String source, net.kyori.adventure.text.Component name,
                                 java.util.List<net.kyori.adventure.text.Component> lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(unidentifiedKey, PersistentDataType.STRING, "1");
        if (source != null && !source.isBlank()) {
            meta.getPersistentDataContainer().set(sourceKey, PersistentDataType.STRING, source);
        }
        meta.displayName(name);
        meta.lore(lore == null ? java.util.List.of() : lore);
        item.setItemMeta(meta);
    }

    /** Снять «маску» неопознанного: вернуть стандартное имя/пустой лор (тир наносится отдельно). */
    public void clearUnidentified(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().remove(unidentifiedKey);
        meta.getPersistentDataContainer().remove(sourceKey);
        meta.displayName(null);
        meta.lore(null);
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
        // 1.4.25-аудит: addAttributeModifier кидает при кривой комбинации
        // атрибут/слот/операция — крафт не должен падать из-за одного бонуса
        try {
            meta.addAttributeModifier(attribute, modifier);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Tier attribute rejected on " + attribute
                    + ": " + ex.getMessage());
        }
    }

    private NamespacedKey modifierKey(String tag) {
        return new NamespacedKey(plugin, "tier_" + tag);
    }

    private void clearTierModifiers(ItemMeta meta) {
        if (meta.hasAttributeModifiers()) {
            for (String tag : List.of("attack", "armor", "toughness", "movespeed",
                    "attack_tool", "attackspeed")) {
                NamespacedKey key = modifierKey(tag);
                for (Attribute attribute : List.of(Attribute.ATTACK_DAMAGE, Attribute.ARMOR,
                        Attribute.ARMOR_TOUGHNESS, Attribute.MOVEMENT_SPEED, Attribute.ATTACK_SPEED)) {
                    Collection<AttributeModifier> mods = meta.getAttributeModifiers(attribute);
                    if (mods == null) {
                        continue;
                    }
                    for (AttributeModifier existing : mods) {
                        if (existing.getKey().equals(key)) {
                            meta.removeAttributeModifier(attribute, existing);
                        }
                    }
                }
            }
        }
        // снятие нашего блеска: если глайнт стоял через оверрайд — вернуть к стандартному.
        // На 1.21.5+ геттер падает, если компонента нет — сначала hasEnchantmentGlintOverride()
        if (meta.hasEnchantmentGlintOverride()
                && Boolean.TRUE.equals(meta.getEnchantmentGlintOverride())) {
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
