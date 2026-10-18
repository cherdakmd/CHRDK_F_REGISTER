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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Применение тира к предмету: PDC-метка, модификаторы атрибутов, окраска имени
 * (translatable — следует языку клиента), лор-плашка, блеск у легенд.
 * С 1.4.55 — ещё и кузнечные слои: случайная кличка из пула 35 имён (один раз,
 * живёт в PDC), уровни улучшений (up_&lt;id&gt;, атрибуты fu_&lt;id&gt;),
 * лор-строки свойств с маркером ✦. Только серверный рантайм.
 */
public final class TierItems {

    public static final String PDC_KEY = "item_tier";
    /** Флаг «неопознанного» предмета: редкость скрыта до попадания в инвентарь. */
    public static final String PDC_UNIDENTIFIED = "item_unidentified";
    /** Источник выдачи неопознанного предмета ("craft" / "drop") — нужен для статистики. */
    public static final String PDC_SOURCE = "item_tier_source";
    /** Случайная кличка предмета из пула (одна и навсегда, если игрок не переименует). */
    public static final String PDC_FORGE_NAME = "forge_name";
    /** Префикс PDC-уровня улучшения: up_edge, up_lifesteal, … */
    public static final String PDC_UPGRADE_PREFIX = "up_";
    /** Маркер лор-строк улучшений (пересборка лора снимает строки по этому знаку). */
    public static final String FORGE_LORE_MARKER = "✦";

    private final TierForgePlugin plugin;
    private final NamespacedKey key;
    private final NamespacedKey unidentifiedKey;
    private final NamespacedKey sourceKey;
    private final NamespacedKey forgeNameKey;
    private final Random random = new Random();

    public TierItems(TierForgePlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, PDC_KEY);
        this.unidentifiedKey = new NamespacedKey(plugin, PDC_UNIDENTIFIED);
        this.sourceKey = new NamespacedKey(plugin, PDC_SOURCE);
        this.forgeNameKey = new NamespacedKey(plugin, PDC_FORGE_NAME);
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
        clearForgeModifiers(meta);
        ensureForgeName(item, meta);
        applyModifiers(item, meta, tier);
        applyUpgradeModifiers(item, meta);
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

    /** Снимает тир (модификаторы, метка, блеск нашего происхождения). Улучшения/кличку оставляет. */
    public void clear(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().remove(key);
        clearTierModifiers(meta);
        clearForgeModifiers(meta);
        item.setItemMeta(meta);
    }

    /* ----------------------------- улучшения (PDC) ----------------------------- */

    private NamespacedKey upgradeKey(String id) {
        return new NamespacedKey(plugin, PDC_UPGRADE_PREFIX + id);
    }

    /** Уровень улучшения на предмете (0 — не стоит). */
    public int upgradeLevel(ItemStack item, String upgradeId) {
        if (item == null || upgradeId == null || !item.hasItemMeta()) {
            return 0;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return 0;
        Integer level = meta.getPersistentDataContainer().get(upgradeKey(upgradeId), PersistentDataType.INTEGER);
        return level == null ? 0 : level;
    }

    /** Все улучшения предмета (Def → уровень), в порядке реестра. */
    public Map<ForgeUpgrade.Def, Integer> upgradesOf(ItemStack item) {
        Map<ForgeUpgrade.Def, Integer> result = new LinkedHashMap<>();
        if (item == null || !item.hasItemMeta()) {
            return result;
        }
        for (ForgeUpgrade.Def def : ForgeUpgrade.ALL) {
            int level = upgradeLevel(item, def.id());
            if (level > 0) {
                result.put(def, level);
            }
        }
        return result;
    }

    /**
     * Поднимает уровень улучшения и пересобирает предмет по его текущему тиру
     * (атрибуты/лор/имя). Безопасно вызывать только для тир-предметов.
     */
    public void bumpUpgrade(ItemStack item, ForgeUpgrade.Def def, int newLevel) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(upgradeKey(def.id()), PersistentDataType.INTEGER, newLevel);
        item.setItemMeta(meta);
        String tierId = tierOf(item);
        TierDef.Snapshot snapshot = tierId == null ? null : plugin.config().find(tierId);
        if (snapshot != null) {
            apply(item, snapshot);
        }
    }

    /**
     * Переносит «кузнечное состояние» предмета (кличка из пула + уровни всех
     * улучшений) на другой предмет — для незеритового апгрейда на кузнечном
     * столе (1.4.80). Тир наносится отдельно через {@link #apply}.
     */
    public void copyForgeState(ItemStack from, ItemStack to) {
        if (from == null || to == null || !from.hasItemMeta()) {
            return;
        }
        ItemMeta source = from.getItemMeta();
        ItemMeta target = to.getItemMeta();
        if (source == null || target == null) {
            return;
        }
        String forgeName = source.getPersistentDataContainer()
                .get(forgeNameKey, PersistentDataType.STRING);
        if (forgeName != null && !forgeName.isBlank()) {
            target.getPersistentDataContainer()
                    .set(forgeNameKey, PersistentDataType.STRING, forgeName);
        }
        for (ForgeUpgrade.Def def : ForgeUpgrade.ALL) {
            Integer level = source.getPersistentDataContainer()
                    .get(upgradeKey(def.id()), PersistentDataType.INTEGER);
            if (level != null && level > 0) {
                target.getPersistentDataContainer()
                        .set(upgradeKey(def.id()), PersistentDataType.INTEGER, level);
            }
        }
        to.setItemMeta(target);
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

    /** Атрибутная половина улучшений (событийная половина живёт в ForgeUpgradeListener). */
    private void applyUpgradeModifiers(ItemStack item, ItemMeta meta) {
        ItemKind kind = ItemKind.classify(item.getType().name());
        if (kind == null) return;
        EquipmentSlotGroup slot = slotGroupFor(kind);
        for (Map.Entry<ForgeUpgrade.Def, Integer> entry : upgradesOf(item).entrySet()) {
            ForgeUpgrade.Def def = entry.getKey();
            double total = def.perLevel() * entry.getValue();
            String tag = "fu_" + def.id();
            switch (def.effect()) {
                case ATTACK_PERCENT -> add(meta, Attribute.ATTACK_DAMAGE, tag, total / 100.0, slot);
                case ATTACK_SPEED_PERCENT -> add(meta, Attribute.ATTACK_SPEED, tag, total / 100.0, slot);
                case ARMOR_PERCENT -> add(meta, Attribute.ARMOR, tag, total / 100.0, slot);
                case MOVE_SPEED_PERCENT -> add(meta, Attribute.MOVEMENT_SPEED, tag, total / 100.0, slot);
                case MAX_HEALTH -> addAbsolute(meta, Attribute.MAX_HEALTH, tag, total, slot);
                case DIG_SPEED_PERCENT -> add(meta, Attribute.BLOCK_BREAK_SPEED, tag,
                        total / 100.0, slot);
                // --- 1.4.56: атрибутная часть новых фишек ---
                case RAW_DAMAGE -> addAbsolute(meta, Attribute.ATTACK_DAMAGE, tag, total, slot);
                case ATTACK_KNOCKBACK -> addAbsolute(meta, Attribute.ATTACK_KNOCKBACK, tag, total, slot);
                case REACH -> addAbsolute(meta, Attribute.ENTITY_INTERACTION_RANGE, tag, total, slot);
                case SWEEP_RATIO -> addAbsolute(meta, Attribute.SWEEPING_DAMAGE_RATIO, tag, total, slot);
                case KB_RESIST -> addAbsolute(meta, Attribute.KNOCKBACK_RESISTANCE, tag, total, slot);
                case SAFE_FALL -> addAbsolute(meta, Attribute.SAFE_FALL_DISTANCE, tag, total, slot);
                case STEP_HEIGHT -> addAbsolute(meta, Attribute.STEP_HEIGHT, tag, total, slot);
                case OXYGEN -> addAbsolute(meta, Attribute.OXYGEN_BONUS, tag, total, slot);
                case SWIM_SPEED -> addAbsolute(meta, Attribute.WATER_MOVEMENT_EFFICIENCY, tag,
                        total, slot);
                case SNEAK_SPEED -> add(meta, Attribute.SNEAKING_SPEED, tag, total / 100.0, slot);
                case BURN_TIME -> add(meta, Attribute.BURNING_TIME, tag, -total / 100.0, slot);
                case EXPLOSION_RESIST -> addAbsolute(meta, Attribute.EXPLOSION_KNOCKBACK_RESISTANCE,
                        tag, total, slot);
                case LUCK -> addAbsolute(meta, Attribute.LUCK, tag, total, slot);
                case MINING_EFFICIENCY -> addAbsolute(meta, Attribute.MINING_EFFICIENCY, tag,
                        total, slot);
                case BLOCK_REACH -> addAbsolute(meta, Attribute.BLOCK_INTERACTION_RANGE, tag,
                        total, slot);
                case SCALE_UP -> add(meta, Attribute.SCALE, tag, total / 100.0, slot);
                // --- 1.4.57: атрибутная часть третьей ковки ---
                case ATTACK_SPEED_FLAT -> addAbsolute(meta, Attribute.ATTACK_SPEED, tag, total, slot);
                case TOUGHNESS_FLAT -> addAbsolute(meta, Attribute.ARMOR_TOUGHNESS, tag, total, slot);
                case JUMP -> add(meta, Attribute.JUMP_STRENGTH, tag, total / 100.0, slot);
                case GRAVITY_DOWN -> add(meta, Attribute.GRAVITY, tag, -total / 100.0, slot);
                case FALL_DAMAGE_DOWN -> add(meta, Attribute.FALL_DAMAGE_MULTIPLIER, tag,
                        -total / 100.0, slot);
                case WALK_EFFICIENCY -> addAbsolute(meta, Attribute.MOVEMENT_EFFICIENCY, tag,
                        total, slot);
                default -> {
                    // событийные свойства (крит/вампиризм/терновник/…) — атрибутов не требуют
                }
            }
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

    /** MAX_HEALTH кладётся ADD_NUMBER (абсолютные сердца) — у неё свой конструктор-путь. */
    private void addAbsolute(ItemMeta meta, Attribute attribute, String tag, double amount,
                             EquipmentSlotGroup slot) {
        AttributeModifier modifier = new AttributeModifier(modifierKey(tag), amount,
                AttributeModifier.Operation.ADD_NUMBER, slot);
        try {
            meta.addAttributeModifier(attribute, modifier);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Tier attribute rejected on " + attribute
                    + ": " + ex.getMessage());
        }
    }

    private NamespacedKey modifierKey(String tag) {
        return new NamespacedKey(plugin, tag.startsWith("fu_") ? tag : "tier_" + tag);
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

    /** Все атрибуты, которые могут нести модификаторы улучшений. */
    private static final List<Attribute> FORGE_ATTRIBUTES = List.of(
            Attribute.ATTACK_DAMAGE, Attribute.ATTACK_SPEED, Attribute.ARMOR,
            Attribute.MOVEMENT_SPEED, Attribute.MAX_HEALTH, Attribute.BLOCK_BREAK_SPEED,
            Attribute.ATTACK_KNOCKBACK, Attribute.ENTITY_INTERACTION_RANGE,
            Attribute.SWEEPING_DAMAGE_RATIO, Attribute.KNOCKBACK_RESISTANCE,
            Attribute.SAFE_FALL_DISTANCE, Attribute.STEP_HEIGHT, Attribute.OXYGEN_BONUS,
            Attribute.WATER_MOVEMENT_EFFICIENCY, Attribute.SNEAKING_SPEED,
            Attribute.BURNING_TIME, Attribute.EXPLOSION_KNOCKBACK_RESISTANCE,
            Attribute.LUCK, Attribute.MINING_EFFICIENCY, Attribute.BLOCK_INTERACTION_RANGE,
            Attribute.SCALE, Attribute.ARMOR_TOUGHNESS, Attribute.JUMP_STRENGTH,
            Attribute.GRAVITY, Attribute.FALL_DAMAGE_MULTIPLIER,
            Attribute.MOVEMENT_EFFICIENCY);

    /** Снимает модификаторы улучшений (fu_<id>) со всех атрибутов. */
    private void clearForgeModifiers(ItemMeta meta) {
        if (!meta.hasAttributeModifiers()) {
            return;
        }
        for (ForgeUpgrade.Def def : ForgeUpgrade.ALL) {
            NamespacedKey fuKey = modifierKey("fu_" + def.id());
            for (Attribute attribute : FORGE_ATTRIBUTES) {
                Collection<AttributeModifier> mods = meta.getAttributeModifiers(attribute);
                if (mods == null) {
                    continue;
                }
                for (AttributeModifier existing : mods) {
                    if (existing.getKey().equals(fuKey)) {
                        meta.removeAttributeModifier(attribute, existing);
                    }
                }
            }
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

    /**
     * Разовая выдача клички: если пул включён, у предмета подходящего вида ещё нет
     * клички в PDC и игрок её не переименовывал — тянем случайное имя из пула 35.
     */
    private void ensureForgeName(ItemStack item, ItemMeta meta) {
        if (!plugin.config().namesEnabled() || plugin.namePool() == null) {
            return;
        }
        if (meta.getPersistentDataContainer().has(forgeNameKey, PersistentDataType.STRING)) {
            return; // кличка уже есть — перекаты/апгрейды её не трогают
        }
        if (meta.hasCustomName() && meta.customName() != null) {
            return; // имя игрока свято
        }
        ItemKind kind = ItemKind.classify(item.getType().name());
        String picked = plugin.namePool().pick(kind, random);
        if (picked != null && !picked.isBlank()) {
            meta.getPersistentDataContainer().set(forgeNameKey, PersistentDataType.STRING, picked.trim());
        }
    }

    /** Кличка предмета из PDC (пустая строка — не окрещён). */
    public String forgeNameOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return "";
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return "";
        String name = meta.getPersistentDataContainer().get(forgeNameKey, PersistentDataType.STRING);
        return name == null ? "" : name;
    }

    private void decorateName(ItemStack item, ItemMeta meta, TierDef.Snapshot tier) {
        String color = plugin.messages().raw("tier." + tier.id() + ".color");
        String forgeName = meta.getPersistentDataContainer().get(forgeNameKey, PersistentDataType.STRING);
        if (forgeName != null && !forgeName.isBlank() && !(meta.hasCustomName() && meta.customName() != null)) {
            // «кличка» вместо стандартного имени материала (стиль — как у наковальн-переименования)
            meta.displayName(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize(color + forgeName));
            return;
        }
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
        lore.removeIf(this::isForgeLine);
        lore.add(tierLine(tier));
        // строки свойств улучшений — после плашки тира, в порядке реестра
        for (Map.Entry<ForgeUpgrade.Def, Integer> entry : upgradesFromMeta(meta).entrySet()) {
            lore.add(upgradeLine(entry.getKey(), entry.getValue()));
        }
        meta.lore(lore);
    }

    /** Улучшения уже из меты (вызывается из replaceLore после записи PDC). */
    private Map<ForgeUpgrade.Def, Integer> upgradesFromMeta(ItemMeta meta) {
        Map<ForgeUpgrade.Def, Integer> result = new LinkedHashMap<>();
        for (ForgeUpgrade.Def def : ForgeUpgrade.ALL) {
            Integer level = meta.getPersistentDataContainer().get(upgradeKey(def.id()), PersistentDataType.INTEGER);
            if (level != null && level > 0) {
                result.put(def, level);
            }
        }
        return result;
    }

    /** Цветная плашка тира в лоре (собирается напрямую: цвет — тег MiniMessage). */
    private Component tierLine(TierDef.Snapshot tier) {
        String color = plugin.messages().raw("tier." + tier.id() + ".color");
        String name = plugin.messages().raw("tier." + tier.id() + ".name");
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(color + name);
    }

    /** Строка свойства улучшения: «✦ Кромка III — +6% урона». */
    private Component upgradeLine(ForgeUpgrade.Def def, int level) {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                "<gold>" + FORGE_LORE_MARKER + " " + def.displayName() + " "
                        + ForgeUpgrade.roman(level) + "</gold>"
                        + " <gray>— " + ForgeUpgrade.describe(def, level) + "</gray>");
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

    /** Своя строка улучшения — по маркеру ✦ (пересборка снимает прежние). */
    private boolean isForgeLine(Component line) {
        return PlainTextComponentSerializer.plainText().serialize(line).trim()
                .startsWith(FORGE_LORE_MARKER);
    }
}
