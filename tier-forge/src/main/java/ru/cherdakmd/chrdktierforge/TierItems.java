package ru.cherdakmd.chrdktierforge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import ru.cherdakmd.chrdkregister.api.ChrdkEnchantsApi;
import ru.cherdakmd.chrdkregister.item.ItemAttributes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Применение тира к предмету: PDC-метка, модификаторы атрибутов, окраска имени
 * (translatable — следует языку клиента), лор-плашка и блеск по стилю тира
 * (с 1.4.119 — свой у каждого, отпечаток в {@code visuals_stamp}).
 * С 1.4.55 — ещё и кузнечные слои: случайная кличка из пула 45 имён (один раз,
 * живёт в PDC), уровни улучшений (up_&lt;id&gt;, атрибуты fu_&lt;id&gt;),
 * лор-строки свойств с маркером ✦. С 1.4.118 — уникальные свойства тира:
 * по 15 на тир, один-три на предмет, в PDC ({@code tier_properties}) и лоре
 * с маркером ✧. Свойство живёт вместе с тиром и пересбрасывается при его
 * смене; событийная половина работает через {@link ForgeUpgradeListener}.
 * С 1.4.124 у каждого предмета свой суффикс id модификаторов
 * ({@code modifier_uid}), поэтому бонусы четырёх деталей брони складываются,
 * а не затирают друг друга. Волна 17 — универсальные аффиксы (15 хороших и
 * 15 плохих, строки ◆/✖, детерминированы по uid и тиру) и случайные ванильные
 * зачарования, которые предмет иногда получает вместе с редкостью.
 * С 1.4.128 (волна 18) — ещё и кастомные чары CHRDK_Enchants через мягкую
 * зависимость {@link ChrdkEnchantsApi}: светлые и, на высоких тирах, проклятия.
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
    /**
     * Битовая карта стоящих улучшений (1.4.82): два long-слова вместо перебора
     * всех 85 PDC-ключей на каждый предмет.
     */
    public static final String PDC_UPGRADE_INDEX = "up_index";
    /** Маркер лор-строк улучшений (пересборка лора снимает строки по этому знаку). */
    public static final String FORGE_LORE_MARKER = "✦";
    /** PDC-строка уникальных свойств тира: id через запятую (1.4.118). */
    public static final String PDC_PROPERTIES = "tier_properties";
    /** Маркер лор-строк свойств тира — по нему лор и пересобирается. */
    public static final String PROPERTY_LORE_MARKER = "✧";
    /** Маркер строки хорошего аффикса (волна 17). */
    public static final String AFFIX_GOOD_MARKER = "◆";
    /** Маркер строки плохого аффикса (дефекта). */
    public static final String AFFIX_BAD_MARKER = "✖";
    /**
     * PDC-метка «зачарования по тиру уже раскатаны»: {@code <тир>:<uid>}.
     * Нужна, чтобы пересборка не добавляла чары повторно и не возвращала их
     * после точильного камня.
     */
    public static final String PDC_ENCHANT_STAMP = "tier_enchant_stamp";
    /**
     * Отпечаток визуального стиля тира (1.4.119): блеск и его источник. По нему
     * старые предметы получают блеск один раз, а правка {@code visuals} в конфиге
     * не требует пересборки всего инвентаря.
     */
    public static final String PDC_VISUALS = "visuals_stamp";
    /**
     * Стабильный суффикс id модификаторов предмета (1.4.124). Без него четыре
     * детали брони носили один и тот же {@code tier_movespeed}, и Minecraft
     * применял только один модификатор — сет не давал скорости и защиты.
     */
    public static final String PDC_MODIFIER_UID = "modifier_uid";
    /** Отпечаток раскладки модификаторов: старые предметы пересобираются один раз. */
    public static final String PDC_MODIFIERS = "modifiers_stamp";

    private final TierForgePlugin plugin;
    private final NamespacedKey key;
    private final NamespacedKey unidentifiedKey;
    private final NamespacedKey sourceKey;
    private final NamespacedKey forgeNameKey;
    private final NamespacedKey indexKey;
    private final NamespacedKey propertiesKey;
    private final NamespacedKey visualsKey;
    private final NamespacedKey modifierUidKey;
    private final NamespacedKey modifiersKey;
    private final NamespacedKey enchantStampKey;
    private final Random random = new Random();

    public TierItems(TierForgePlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, PDC_KEY);
        this.unidentifiedKey = new NamespacedKey(plugin, PDC_UNIDENTIFIED);
        this.sourceKey = new NamespacedKey(plugin, PDC_SOURCE);
        this.forgeNameKey = new NamespacedKey(plugin, PDC_FORGE_NAME);
        this.indexKey = new NamespacedKey(plugin, PDC_UPGRADE_INDEX);
        this.propertiesKey = new NamespacedKey(plugin, PDC_PROPERTIES);
        this.visualsKey = new NamespacedKey(plugin, PDC_VISUALS);
        this.modifierUidKey = new NamespacedKey(plugin, PDC_MODIFIER_UID);
        this.modifiersKey = new NamespacedKey(plugin, PDC_MODIFIERS);
        this.enchantStampKey = new NamespacedKey(plugin, PDC_ENCHANT_STAMP);
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

    /** Есть ли на предмете тир (для моста {@code ChrdkTierApi}). */
    public boolean isTierItem(ItemStack item) {
        return tierOf(item) != null;
    }

    /**
     * Записывает источник тира ({@code item_tier_source}) без пересборки имени:
     * витрина {@code /tiers info} показывает, откуда пришла редкость.
     */
    public void setSource(ItemStack item, String source) {
        if (item == null || source == null || source.isBlank() || !item.hasItemMeta()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(sourceKey, PersistentDataType.STRING,
                source.trim().toLowerCase(java.util.Locale.ROOT));
        item.setItemMeta(meta);
    }

    /** Полное применение тира: модификаторы, PDC, имя, лор, блеск. */
    public void apply(ItemStack item, TierDef.Snapshot tier) {
        apply(item, tier, null, null);
    }

    /**
     * То же, но с заранее выбранными свойствами тира. Нужно превью наковальни:
     * без этого свойства переролливались бы на каждое движение в окне, и хорошее
     * можно было бы «рыбачить» бесплатно. {@code null} — обычный бросок.
     */
    public void apply(ItemStack item, TierDef.Snapshot tier, List<TierProperty> preset) {
        apply(item, tier, preset, null);
    }

    /**
     * Полная пересборка с заранее выбранным суффиксом предмета.
     *
     * <p>Волна 17: аффиксы и зачарования выводятся из {@code modifier_uid}, тира
     * и вида. В превью крафта результат пересобирается на каждое движение
     * ингредиентов — с новым uid это позволяло бы «рыбачить» хорошие аффиксы
     * бесплатно. Поэтому {@code TierCraftListener} держит uid в
     * {@link CraftRollKeeper} и передаёт его сюда; уже записанный в предмет uid
     * всегда важнее переданного.</p>
     *
     * @param uidPreset стабильный суффикс превью; {@code null} — сгенерировать
     */
    public void apply(ItemStack item, TierDef.Snapshot tier, List<TierProperty> preset,
                      String uidPreset) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        ItemKind kind = ItemKind.classify(item.getType().name());
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, tier.id());
        // 1.4.124: id модификаторов уникальны для предмета — иначе четыре детали
        // брони делят один модификатор, и складывается только одна.
        String uid = ensureModifierUid(meta, uidPreset);

        clearTierModifiers(meta);
        clearForgeModifiers(meta);
        ItemAttributes.keep(item, meta);
        ensureForgeName(item, meta);
        refreshUpgradeIndex(meta, kind); // 1.4.82: индекс строится при каждой пересборке
        syncProperties(item, meta, tier, preset); // 1.4.118: свойство живёт вместе с тиром
        applyModifiers(item, meta, tier, uid);
        applyUpgradeModifiers(item, meta, uid);
        applyPropertyModifiers(item, meta, uid);
        applyAffixModifiers(item, meta, uid); // волна 17: хорошие и плохие аффиксы
        applyEnchantments(item, meta, tier, uid); // волна 17: иногда — зачарования
        decorateName(item, meta, tier);
        replaceLore(meta, tier, kind);
        stampVisuals(meta, tier); // 1.4.119: свой блеск у каждого тира
        meta.getPersistentDataContainer().set(modifiersKey, PersistentDataType.STRING,
                modifierStamp(kind, tier));
        ItemAttributes.dropEmpty(meta);
        item.setItemMeta(meta);
        // волна 18: кастомные чары — после setItemMeta, потому что API аддона
        // читает и пишет готовый ItemStack, а не ItemMeta
        applyCustomEnchants(item, tier, uid);
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

    /**
     * Снять «маску» неопознанного: вернуть стандартное имя/пустой лор (тир
     * наносится отдельно). Источник ({@code item_tier_source}) с 1.4.84 остаётся
     * в PDC — его показывает {@code /tiers info}.
     */
    public void clearUnidentified(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().remove(unidentifiedKey);
        meta.displayName(null);
        meta.lore(null);
        item.setItemMeta(meta);
    }

    /** Снимает тир (модификаторы, метка, блеск нашего происхождения). Улучшения/кличку оставляет. */
    public void clear(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().remove(key);
        meta.getPersistentDataContainer().remove(propertiesKey);
        meta.getPersistentDataContainer().remove(modifierUidKey);
        meta.getPersistentDataContainer().remove(modifiersKey);
        clearTierModifiers(meta);
        clearForgeModifiers(meta);
        // 1.4.140: если после снятия наших бонусов компонент атрибутов опустел,
        // он всё равно перекрывал бы ванильные значения — возвращаем базу.
        ItemAttributes.keep(item, meta);
        ItemAttributes.dropEmpty(meta);
        item.setItemMeta(meta);
    }

    /**
     * Догоняющее обновление старого тир-предмета: миграция ключей и формул.
     *
     * <p>С 1.4.117 инструменты не носят урон/скорость атаки, зато получают
     * скорость добычи. С 1.4.124 у каждого предмета свой суффикс id модификаторов
     * (иначе сет брони «склеивался» в один бонус), а формулы стали сильнее —
     * поэтому предмет без актуального {@code modifiers_stamp} пересобирается
     * по атрибутам. С 1.4.265 это не полная выдача: кличка, чары, уровни
     * улучшений и сохранённый набор свойств не переролливаются; свой лор
     * обновляется отдельно, чужие строки сохраняются.</p>
     *
     * @return {@code true} — предмет обновлён (инвентарь стоит перерисовать)
     */
    public boolean refreshTierModifiers(ItemStack item) {
        if (item == null) {
            return false;
        }
        String tierId = tierOf(item);
        if (tierId == null) {
            return false;
        }
        TierDef.Snapshot tier = plugin.config().find(tierId);
        if (tier == null) {
            return false;
        }
        ItemKind kind = ItemKind.classify(item.getType().name());
        if (kind == null) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        boolean modifiers = needsModifierRefresh(meta, kind, tier);
        // 1.4.119: старые предметы получают и новый блеск — лениво, при входе/смене слота.
        boolean visuals = needsVisualRefresh(meta, tier);
        if (!modifiers && !visuals) {
            return false;
        }
        if (modifiers) {
            // Только атрибуты: никакой выдачи имён, свойств или чар при миграции/reload.
            String uid = ensureModifierUid(meta, null);
            clearTierModifiers(meta);
            clearForgeModifiers(meta);
            ItemAttributes.keep(item, meta);
            applyModifiers(item, meta, tier, uid);
            applyUpgradeModifiers(item, meta, uid);
            applyPropertyModifiers(item, meta, uid);
            applyAffixModifiers(item, meta, uid);
            refreshOwnedLore(meta, tier, kind);
            meta.getPersistentDataContainer().set(modifiersKey, PersistentDataType.STRING,
                    modifierStamp(kind, tier));
        }
        stampVisuals(meta, tier);
        // 1.4.140: пустой компонент атрибутов перекрывает ванильные значения —
        // если своих бонусов не осталось, убираем его совсем.
        ItemAttributes.dropEmpty(meta);
        item.setItemMeta(meta);
        return true;
    }

    /**
     * Нужно ли пересобрать атрибуты: устаревший отпечаток раскладки, старые ключи
     * инструментов или отсутствие скорости добычи у инструмента.
     */
    private boolean needsModifierRefresh(ItemMeta meta, ItemKind kind, TierDef.Snapshot tier) {
        if (!modifierStamp(kind, tier).equals(modifiersStamp(meta))) {
            return true; // раскладка до 1.4.124: общие id и старые формулы
        }
        if (hasTierModifier(meta, "attack_tool", Attribute.ATTACK_DAMAGE)
                || hasTierModifier(meta, "attackspeed", Attribute.ATTACK_SPEED)) {
            return true; // ключи версий до 1.4.117 — таких бонусов больше нет ни у кого
        }
        return tier.percent() != 0 && kind.isTool()
                && !hasTierModifier(meta, "minespeed", Attribute.BLOCK_BREAK_SPEED);
    }

    /**
     * Отпечаток раскладки. Волна 17: в него входят и переключатели аффиксов/
     * зачарований — иначе {@code affixes.enabled: false} оставлял бы на старых
     * предметах уже навешенные модификаторы: событийные эффекты гаснут сразу,
     * а атрибуты ждали бы пересборки, которой без смены отпечатка не будет.
     */
    private String modifierStamp(ItemKind kind, TierDef.Snapshot tier) {
        TierForgeConfig config = plugin.config();
        if (config == null) {
            return TierModifierIds.STAMP;
        }
        return TierModifierIds.stamp(config.affixes().enabled(),
                config.affixes().goodLimit(), config.affixes().badLimit(),
                config.enchants().enabled(), kind == null ? "unknown" : kind.name(), tier.percent(),
                config.propertiesEnabled(), config.propertiesPerItem());
    }

    /** Отпечаток раскладки модификаторов на предмете (null — версия до 1.4.124). */
    private String modifiersStamp(ItemMeta meta) {
        return meta.getPersistentDataContainer().get(modifiersKey, PersistentDataType.STRING);
    }

    /**
     * Читает стабильный суффикс предмета, создавая его при первом накате.
     * Уже записанный важнее подсказки из превью, иначе — подсказка, иначе
     * новый случайный.
     */
    private String ensureModifierUid(ItemMeta meta, String preset) {
        String uid = meta.getPersistentDataContainer()
                .get(modifierUidKey, PersistentDataType.STRING);
        if (!TierModifierIds.validUid(uid)) {
            uid = TierModifierIds.validUid(preset) ? preset : TierModifierIds.newUid(random);
            meta.getPersistentDataContainer()
                    .set(modifierUidKey, PersistentDataType.STRING, uid);
        }
        return uid;
    }

    /**
     * Ванильные зачарования предмета (волна 17) — для диагностики
     * {@code /tiers info}. Пустая карта — предмет не зачарован.
     */
    public Map<Enchantment, Integer> enchantmentsOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return Map.of();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasEnchants()) {
            return Map.of();
        }
        return new LinkedHashMap<>(meta.getEnchants());
    }

    /** Есть ли на предмете модификатор тира с данным ключом у атрибута. */
    private boolean hasTierModifier(ItemMeta meta, String tag, Attribute attribute) {
        if (!meta.hasAttributeModifiers()) {
            return false;
        }
        Collection<AttributeModifier> mods = meta.getAttributeModifiers(attribute);
        if (mods == null) {
            return false;
        }
        String uid = meta.getPersistentDataContainer()
                .get(modifierUidKey, PersistentDataType.STRING);
        String modern = uid == null ? null : TierModifierIds.key("tier_" + tag, uid);
        String legacy = "tier_" + tag;
        String namespace = key.getNamespace();
        for (AttributeModifier modifier : mods) {
            if (!modifier.getKey().getNamespace().equals(namespace)) {
                continue;
            }
            String name = modifier.getKey().getKey();
            if (legacy.equals(name) || (modern != null && modern.equals(name))) {
                return true;
            }
        }
        return false;
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

    /**
     * Все улучшения предмета (Def → уровень), в порядке реестра. С 1.4.82 читает
     * {@code up_index} и трогает только стоящие ключи; предметы старых версий без
     * индекса по-прежнему перебираются целиком (и получат индекс при следующей
     * пересборке через {@link #apply}).
     */
    public Map<ForgeUpgrade.Def, Integer> upgradesOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return new LinkedHashMap<>();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return new LinkedHashMap<>();
        }
        return upgradesFromMeta(meta, ItemKind.classify(item.getType().name()));
    }

    /**
     * Улучшения из уже открытой меты: {@code up_index}, старые предметы — перебором.
     *
     * <p>Волна 1.4.141: чужие улучшения отбрасываются. До этого предмет получал
     * «Кромку» вместе с луком или «Снайпера» вместе с мечом, потому что цель
     * {@code WEAPON} покрывала всё оружие; теперь пара «цель + эффект» режется
     * по виду, и старый предмет честно теряет бесполезную строку.</p>
     *
     * <p>Неизвестный вид ({@code null}) ничего не отсекает: у щита или удочки
     * тир-система не знает ролей, и стирать чужие улучшения там нельзя.</p>
     */
    private Map<ForgeUpgrade.Def, Integer> upgradesFromMeta(ItemMeta meta, ItemKind kind) {
        Map<ForgeUpgrade.Def, Integer> result = new LinkedHashMap<>();
        long[] index = meta.getPersistentDataContainer()
                .get(indexKey, PersistentDataType.LONG_ARRAY);
        if (index != null && UpgradeIndex.any(index)) {
            for (int i = 0; i < ForgeUpgrade.ALL.size(); i++) {
                if (!UpgradeIndex.has(index, i)) {
                    continue;
                }
                ForgeUpgrade.Def def = ForgeUpgrade.ALL.get(i);
                Integer level = meta.getPersistentDataContainer()
                        .get(upgradeKey(def.id()), PersistentDataType.INTEGER);
                if (level != null && level > 0 && (kind == null || def.fits(kind))) {
                    result.put(def, level);
                }
            }
            return result;
        }
        for (ForgeUpgrade.Def def : ForgeUpgrade.ALL) {
            Integer level = meta.getPersistentDataContainer()
                    .get(upgradeKey(def.id()), PersistentDataType.INTEGER);
            if (level != null && level > 0 && (kind == null || def.fits(kind))) {
                result.put(def, level);
            }
        }
        return result;
    }

    /* --------------------------- свойства тира (1.4.118) --------------------------- */

    /**
     * Уникальные свойства предмета (пустой список — их нет или они отключены).
     * Свойства принадлежат тиру: чужой тир их не покажет — при смене тира
     * {@link #apply} пересобирает набор.
     */
    public List<TierProperty> propertiesOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return List.of();
        }
        ItemMeta meta = item.getItemMeta();
        return meta == null ? List.of()
                : propertiesFromMeta(meta, ItemKind.classify(item.getType().name()));
    }

    /**
     * Свойства из меты с отсечением чужих виду. Сырой список нужен только
     * сверке тира: если в PDC лежит свойство «не для этого предмета», набор
     * считается устаревшим и переролливается целиком.
     */
    private List<TierProperty> propertiesFromMeta(ItemMeta meta, ItemKind kind) {
        String tier = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return TierPropertySelection.active(rawPropertiesFromMeta(meta), kind, tier,
                plugin.config().propertiesEnabled(), desiredProperties());
    }

    private List<TierProperty> rawPropertiesFromMeta(ItemMeta meta) {
        return TierProperties.parse(meta.getPersistentDataContainer()
                .get(propertiesKey, PersistentDataType.STRING));
    }

    /**
     * Универсальные аффиксы предмета (волна 17). В отличие от свойств тира они
     * не хранятся в PDC: набор детерминированно выводится из {@code modifier_uid},
     * тира и вида предмета. Поэтому предпросмотр наковальни не переролливается,
     * а старые предметы получают аффиксы без миграции.
     */
    public List<TierAffix> affixesOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return List.of();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return List.of();
        }
        return affixesFromMeta(meta, ItemKind.classify(item.getType().name()));
    }

    /** Аффиксы по ещё не записанной в предмет мете (внутри пересборки). */
    private List<TierAffix> affixesFromMeta(ItemMeta meta, ItemKind kind) {
        TierAffixConfig config = plugin.config().affixes();
        if (!config.enabled() || config.goodLimit() + config.badLimit() <= 0
                || meta == null || kind == null) {
            return List.of();
        }
        String uid = meta.getPersistentDataContainer().get(modifierUidKey, PersistentDataType.STRING);
        String tierId = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return TierAffixes.forItem(uid, tierId, kind, config.goodLimit(), config.badLimit());
    }

    /**
     * Все кузнечные эффекты предмета: улучшения ({@code up_*}), уникальные
     * свойства тира и универсальные аффиксы (волна 17). Свойство и аффикс
     * приходят как «улучшение» I уровня, поэтому событийная половина
     * ({@link ForgeUpgradeListener}) обрабатывает их тем же кодом и под теми же
     * потолками, что и ковку.
     */
    public Map<ForgeUpgrade.Def, Integer> effectsOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return new LinkedHashMap<>();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return new LinkedHashMap<>();
        }
        ItemKind kind = ItemKind.classify(item.getType().name());
        Map<ForgeUpgrade.Def, Integer> result = new LinkedHashMap<>(upgradesFromMeta(meta, kind));
        for (TierProperty property : propertiesFromMeta(meta, kind)) {
            result.put(property.asDef(), 1);
        }
        // волна 17: аффиксы идут тем же путём, что свойства и ковка
        for (TierAffix affix : affixesFromMeta(meta, kind)) {
            result.put(affix.asDef(), 1);
        }
        return result;
    }

    /**
     * Бросок свойств по конфигу: столько, сколько разрешает {@code properties.per-item},
     * из пула тира под вид предмета. Пустой список — свойства выключены или вид
     * предмета не подошёл. Используется превью крафта/наковальни/кузнечного стола.
     */
    public List<TierProperty> rollFor(TierDef.Snapshot tier, ItemKind kind) {
        if (tier == null || kind == null || !plugin.config().propertiesEnabled()) {
            return List.of();
        }
        return TierProperties.roll(tier.id(), kind, desiredProperties(), random);
    }

    /** Сколько свойств положено предмету по конфигу (1..MAX_PER_ITEM). */
    private int desiredProperties() {
        return Math.max(1, Math.min(TierProperties.MAX_PER_ITEM,
                plugin.config().propertiesPerItem()));
    }

    /**
     * Сводит свойства с тиром. Тот же тир — набор сохраняется (per-item вырос —
     * добираем новые, не трогая старые); другой тир или неподходящий вид — бросок
     * заново. Поэтому свойство не «прилипает» к чужому тиру ни при перекате, ни
     * при слиянии, ни на наковальне.
     *
     * @param preset заранее выбранный набор превью (наковальня); {@code null} —
     *               взять текущий из PDC или бросить заново
     */
    private void syncProperties(ItemStack item, ItemMeta meta, TierDef.Snapshot tier,
                                List<TierProperty> preset) {
        ItemKind kind = ItemKind.classify(item.getType().name());
        if (!plugin.config().propertiesEnabled() || kind == null) {
            if (!rawPropertiesFromMeta(meta).isEmpty()) {
                meta.getPersistentDataContainer().remove(propertiesKey);
            }
            return;
        }
        List<TierProperty> current = preset != null && !preset.isEmpty()
                ? preset : rawPropertiesFromMeta(meta);
        int wanted = desiredProperties();
        // Сырой набор сверяем и с тиром, и с видом: свойство «меча» на лопате
        // (или наоборот) — повод перероллить, а не тащить его в лор.
        boolean sameTier = !current.isEmpty();
        for (TierProperty property : current) {
            if (!property.tier().equals(tier.id()) || !property.fits(kind)) {
                sameTier = false;
                break;
            }
        }
        List<TierProperty> result;
        if (sameTier) {
            result = new ArrayList<>(current.subList(0, Math.min(current.size(), wanted)));
            if (result.size() < wanted) {
                result.addAll(TierProperties.roll(tier.id(), kind, wanted - result.size(),
                        random, result));
            }
        } else {
            result = TierProperties.roll(tier.id(), kind, wanted, random);
        }
        if (result.isEmpty()) {
            meta.getPersistentDataContainer().remove(propertiesKey);
        } else {
            meta.getPersistentDataContainer().set(propertiesKey, PersistentDataType.STRING,
                    TierProperties.serialize(result));
        }
    }

    /**
     * Пересобирает {@code up_index} по фактическим PDC-уровням (1.4.82) и
     * заодно стирает улучшения, которые этому виду предмета не подходят
     * (1.4.141): иначе чужая строка осталась бы в лоре навсегда.
     */
    private void refreshUpgradeIndex(ItemMeta meta, ItemKind kind) {
        long[] words = UpgradeIndex.empty();
        for (int i = 0; i < ForgeUpgrade.ALL.size(); i++) {
            ForgeUpgrade.Def def = ForgeUpgrade.ALL.get(i);
            Integer level = meta.getPersistentDataContainer()
                    .get(upgradeKey(def.id()), PersistentDataType.INTEGER);
            if (level == null || level <= 0) {
                continue;
            }
            if (kind != null && !def.fits(kind)) {
                meta.getPersistentDataContainer().remove(upgradeKey(def.id()));
                continue;
            }
            UpgradeIndex.set(words, i);
        }
        if (UpgradeIndex.any(words)) {
            meta.getPersistentDataContainer()
                    .set(indexKey, PersistentDataType.LONG_ARRAY, words);
        } else {
            meta.getPersistentDataContainer().remove(indexKey);
        }
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
        // 1.4.118: свойство тира переезжает вместе с остальным прогрессом. Если тир
        // при этом сменился, apply() всё равно пересбросит набор на новый пул.
        String properties = source.getPersistentDataContainer()
                .get(propertiesKey, PersistentDataType.STRING);
        if (properties != null && !properties.isBlank()) {
            target.getPersistentDataContainer()
                    .set(propertiesKey, PersistentDataType.STRING, properties);
        }
        // Волна 17: uid задаёт аффиксы и зачарования. Незеритовый апгрейд — тот же
        // предмет, поэтому набор должен переехать, а не перероллиться.
        String uid = source.getPersistentDataContainer()
                .get(modifierUidKey, PersistentDataType.STRING);
        if (uid != null && !uid.isBlank()) {
            target.getPersistentDataContainer()
                    .set(modifierUidKey, PersistentDataType.STRING, uid);
            String stamp = source.getPersistentDataContainer()
                    .get(enchantStampKey, PersistentDataType.STRING);
            if (stamp != null && !stamp.isBlank()) {
                target.getPersistentDataContainer()
                        .set(enchantStampKey, PersistentDataType.STRING, stamp);
            }
        }
        to.setItemMeta(target);
    }

    /* ------------------------------- атрибуты ------------------------------- */

    /**
     * Бонусы тира по виду предмета (1.4.117): оружию — урон, броне — защита,
     * инструментам — скорость добычи. Топор получает и урон, и добычу: им и в
     * бою машут, и брёвна рубят. Урон/скорость атаки инструментам больше не
     * выдаются.
     */
    private void applyModifiers(ItemStack item, ItemMeta meta, TierDef.Snapshot tier, String uid) {
        ItemKind kind = ItemKind.classify(item.getType().name());
        if (kind == null) return;
        int percent = tier.percent();
        if (percent == 0) return;
        EquipmentSlotGroup slot = slotGroupFor(kind);
        for (TierBonus bonus : TierBonus.forKind(kind)) {
            switch (bonus) {
                case ATTACK_DAMAGE -> add(item, meta, Attribute.ATTACK_DAMAGE, "attack",
                        TierMath.damageScalar(percent), slot, uid);
                case PROJECTILE_DAMAGE -> {
                    // Урон стрел нельзя повесить атрибутом на лук: ATTACK_DAMAGE
                    // влияет только на ближний бой. Бонус начисляет слушатель
                    // попаданий (ForgeUpgradeListener) по тиру оружия в руке.
                }
                case ARMOR -> add(item, meta, Attribute.ARMOR, "armor",
                        TierMath.armorScalar(percent), slot, uid);
                case ARMOR_TOUGHNESS -> add(item, meta, Attribute.ARMOR_TOUGHNESS, "toughness",
                        TierMath.toughnessScalar(percent), slot, uid);
                case MOVEMENT_SPEED -> {
                    if (percent > 0) {
                        add(item, meta, Attribute.MOVEMENT_SPEED, "movespeed",
                                TierMath.movementScalar(percent), slot, uid);
                    }
                }
                case MINING_SPEED -> add(item, meta, Attribute.BLOCK_BREAK_SPEED, "minespeed",
                        TierMath.miningScalar(percent), slot, uid);
                case LUCK -> {
                    double luck = TierMath.luckAmount(percent);
                    if (luck != 0.0) {
                        addAbsolute(item, meta, Attribute.LUCK, "luck", luck, slot, uid);
                    }
                }
            }
        }
    }

    /** Атрибутная половина улучшений (событийная половина живёт в ForgeUpgradeListener). */
    private void applyUpgradeModifiers(ItemStack item, ItemMeta meta, String uid) {
        ItemKind kind = ItemKind.classify(item.getType().name());
        if (kind == null) return;
        EquipmentSlotGroup slot = slotGroupFor(kind);
        // 1.4.82: читаем уже открытую мету, а не заново собираем её из предмета
        // (раньше на каждую пересборку приходился лишний getItemMeta()).
        for (Map.Entry<ForgeUpgrade.Def, Integer> entry : upgradesFromMeta(meta, kind).entrySet()) {
            ForgeUpgrade.Def def = entry.getKey();
            applyDefModifiers(item, meta, def, def.perLevel() * entry.getValue(),
                    "fu_" + def.id(), slot, uid);
        }
    }

    /** Атрибутная половина свойств тира (событийная живёт в ForgeUpgradeListener). */
    private void applyPropertyModifiers(ItemStack item, ItemMeta meta, String uid) {
        ItemKind kind = ItemKind.classify(item.getType().name());
        if (kind == null) return;
        EquipmentSlotGroup slot = slotGroupFor(kind);
        for (TierProperty property : propertiesFromMeta(meta, kind)) {
            applyDefModifiers(item, meta, property.asDef(), property.value(),
                    "pr_" + property.id(), slot, uid);
        }
    }

    /**
     * Атрибутная часть универсальных аффиксов (волна 17). Работает через тот же
     * {@link #applyDefModifiers}, что ковка и свойства тира, поэтому «+3% урона»
     * от аффикса и «+2% урона» от ковки складываются, а не затирают друг друга.
     */
    private void applyAffixModifiers(ItemStack item, ItemMeta meta, String uid) {
        ItemKind kind = ItemKind.classify(item.getType().name());
        if (kind == null) return;
        EquipmentSlotGroup slot = slotGroupFor(kind);
        for (TierAffix affix : affixesFromMeta(meta, kind)) {
            applyDefModifiers(item, meta, affix.asDef(), affix.value(),
                    TierModifierIds.AFFIX_PREFIX + affix.id(), slot, uid);
        }
    }

    /**
     * Случайные ванильные зачарования (волна 17): один раз на предмет, только
     * если он ещё не зачарован. Бросок детерминирован по uid и тиру, поэтому
     * предпросмотр наковальни и кузнечного стола не переролливается.
     */
    private void applyEnchantments(ItemStack item, ItemMeta meta, TierDef.Snapshot tier, String uid) {
        TierEnchantConfig config = plugin.config().enchants();
        if (!config.enabled() || config.itemLimit() <= 0 || uid == null || uid.isBlank()) {
            return;
        }
        ItemKind kind = ItemKind.classify(item.getType().name());
        if (kind == null) {
            return;
        }
        String marker = tier.id() + ":" + uid;
        String stamped = meta.getPersistentDataContainer()
                .get(enchantStampKey, PersistentDataType.STRING);
        if (marker.equals(stamped)) {
            return;
        }
        if (meta.hasEnchants()) {
            // игрок зачаровал предмет сам — плагин не добавляет и не снимает чары
            meta.getPersistentDataContainer()
                    .set(enchantStampKey, PersistentDataType.STRING, marker);
            return;
        }
        Random enchantRandom = new Random(
                TierAffixes.seed(uid, tier.id(), kind, 0x5DEECE66DL));
        for (TierEnchants.Roll roll : TierEnchants.roll(kind, TierDef.indexOf(tier.id()),
                enchantRandom, config.chancePercent(), config.itemLimit(), config.levelLimit())) {
            Enchantment enchantment = enchantment(roll.key());
            if (enchantment == null || !enchantment.canEnchantItem(item)) {
                continue;
            }
            meta.addEnchant(enchantment, roll.level(), true);
        }
        meta.getPersistentDataContainer()
                .set(enchantStampKey, PersistentDataType.STRING, marker);
    }

    /**
     * Кастомные чары CHRDK_Enchants (волна 18): один-два броска с шансом выше
     * для старших тиров. Бросок детерминирован по uid и тиру, поэтому превью
     * крафта не переролливается.
     *
     * <p>Проклятия выдаются только с {@code enchantments.custom.curse-min-tier}
     * (по умолчанию эпический) и только если сам аддон чар их не выключил:
     * список {@code curseIds()} пуст при {@code rules.curses-enabled: false}.
     * Предмет, у которого кастомные чары уже есть (выданы раньше или наложены
     * игроком), не трогаем — ни добавляем, ни снимаем.</p>
     */
    private void applyCustomEnchants(ItemStack item, TierDef.Snapshot tier, String uid) {
        TierEnchantConfig config = plugin.config().enchants();
        ChrdkEnchantsApi api = plugin.enchantsApi();
        if (api == null || config.customItemLimit() <= 0 || uid == null || uid.isBlank()) {
            return;
        }
        ItemKind kind = ItemKind.classify(item.getType().name());
        // 1.4.142: утилита не зачаровывается вовсе — ни ванильными, ни
        // кастомными чарами. Рог, осколок эха и фонарь душ в ванилле чар не
        // принимают, а «Прочность» на них висела бы мёртвой строкой.
        if (kind == null || kind.isUtility() || api.count(item) > 0) {
            return;
        }
        int tierIndex = TierDef.indexOf(tier.id());
        Random random = new Random(TierAffixes.seed(uid, tier.id(), kind, 0x43A5EEDL));
        List<TierCustomEnchants.Pick> picks = TierCustomEnchants.roll(tierIndex, random,
                api.lightIds(), api.curseIds(), config, api::maxLevel);
        for (TierCustomEnchants.Pick pick : picks) {
            // отказ (вид предмета, лимит аддона, конфликт) — просто пропускаем кандидата
            api.apply(item, pick.id(), pick.level());
        }
    }

    /** Зачарование по ключу реестра ({@code sharpness}); null — такого нет. */
    @SuppressWarnings("deprecation")
    private static Enchantment enchantment(String key) {
        return Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key));
    }

    /**
     * Модификаторы одного эффекта с данным ключом — общий путь ковки и свойств
     * тира: свойство начисляется ровно так же, как улучшение того же эффекта.
     */
    private void applyDefModifiers(ItemStack item, ItemMeta meta, ForgeUpgrade.Def def, double total,
                                   String tag, EquipmentSlotGroup slot, String uid) {
        switch (def.effect()) {
            case ATTACK_PERCENT -> add(item, meta, Attribute.ATTACK_DAMAGE, tag, total / 100.0, slot, uid);
            case ATTACK_SPEED_PERCENT -> add(item, meta, Attribute.ATTACK_SPEED, tag, total / 100.0,
                    slot, uid);
            case ARMOR_PERCENT -> add(item, meta, Attribute.ARMOR, tag, total / 100.0, slot, uid);
            case MOVE_SPEED_PERCENT -> add(item, meta, Attribute.MOVEMENT_SPEED, tag, total / 100.0,
                    slot, uid);
            case MAX_HEALTH -> addAbsolute(item, meta, Attribute.MAX_HEALTH, tag, total, slot, uid);
            case DIG_SPEED_PERCENT -> add(item, meta, Attribute.BLOCK_BREAK_SPEED, tag,
                    total / 100.0, slot, uid);
            // --- 1.4.56: атрибутная часть новых фишек ---
            case RAW_DAMAGE -> addAbsolute(item, meta, Attribute.ATTACK_DAMAGE, tag, total, slot, uid);
            case ATTACK_KNOCKBACK -> addAbsolute(item, meta, Attribute.ATTACK_KNOCKBACK, tag, total,
                    slot, uid);
            case REACH -> addAbsolute(item, meta, Attribute.ENTITY_INTERACTION_RANGE, tag, total,
                    slot, uid);
            case SWEEP_RATIO -> addAbsolute(item, meta, Attribute.SWEEPING_DAMAGE_RATIO, tag, total,
                    slot, uid);
            case KB_RESIST -> addAbsolute(item, meta, Attribute.KNOCKBACK_RESISTANCE, tag, total,
                    slot, uid);
            case SAFE_FALL -> addAbsolute(item, meta, Attribute.SAFE_FALL_DISTANCE, tag, total,
                    slot, uid);
            case STEP_HEIGHT -> addAbsolute(item, meta, Attribute.STEP_HEIGHT, tag, total, slot, uid);
            case OXYGEN -> addAbsolute(item, meta, Attribute.OXYGEN_BONUS, tag, total, slot, uid);
            case SWIM_SPEED -> addAbsolute(item, meta, Attribute.WATER_MOVEMENT_EFFICIENCY, tag,
                    total, slot, uid);
            case SNEAK_SPEED -> add(item, meta, Attribute.SNEAKING_SPEED, tag, total / 100.0, slot, uid);
            case BURN_TIME -> add(item, meta, Attribute.BURNING_TIME, tag, -total / 100.0, slot, uid);
            case EXPLOSION_RESIST -> addAbsolute(item, meta, Attribute.EXPLOSION_KNOCKBACK_RESISTANCE,
                    tag, total, slot, uid);
            case LUCK -> addAbsolute(item, meta, Attribute.LUCK, tag, total, slot, uid);
            case MINING_EFFICIENCY -> addAbsolute(item, meta, Attribute.MINING_EFFICIENCY, tag,
                    total, slot, uid);
            case BLOCK_REACH -> addAbsolute(item, meta, Attribute.BLOCK_INTERACTION_RANGE, tag,
                    total, slot, uid);
            case SCALE_UP -> add(item, meta, Attribute.SCALE, tag, total / 100.0, slot, uid);
            // --- 1.4.57: атрибутная часть третьей ковки ---
            case ATTACK_SPEED_FLAT -> addAbsolute(item, meta, Attribute.ATTACK_SPEED, tag, total,
                    slot, uid);
            case TOUGHNESS_FLAT -> addAbsolute(item, meta, Attribute.ARMOR_TOUGHNESS, tag, total,
                    slot, uid);
            case JUMP -> add(item, meta, Attribute.JUMP_STRENGTH, tag, total / 100.0, slot, uid);
            case GRAVITY_DOWN -> add(item, meta, Attribute.GRAVITY, tag, -total / 100.0, slot, uid);
            case FALL_DAMAGE_DOWN -> add(item, meta, Attribute.FALL_DAMAGE_MULTIPLIER, tag,
                    -total / 100.0, slot, uid);
            case WALK_EFFICIENCY -> addAbsolute(item, meta, Attribute.MOVEMENT_EFFICIENCY, tag,
                    total, slot, uid);
            default -> {
                // событийные свойства (крит/вампиризм/терновник/…) — атрибутов не требуют
            }
        }
    }

    private void add(ItemStack item, ItemMeta meta, Attribute attribute, String tag, double amount,
                     EquipmentSlotGroup slot, String uid) {
        // 1.4.140: свои модификаторы пишут компонент атрибутов целиком, поэтому
        // сперва возвращаем в него ванильные значения предмета (базовый урон,
        // скорость атаки, защиту) — иначе меч бьёт на «полсердечка».
        ItemAttributes.keep(item, meta);
        AttributeModifier modifier = new AttributeModifier(modifierKey(tag, uid), amount,
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
    private void addAbsolute(ItemStack item, ItemMeta meta, Attribute attribute, String tag,
                             double amount, EquipmentSlotGroup slot, String uid) {
        ItemAttributes.keep(item, meta); // 1.4.140: база предмета — до своих бонусов
        AttributeModifier modifier = new AttributeModifier(modifierKey(tag, uid), amount,
                AttributeModifier.Operation.ADD_NUMBER, slot);
        try {
            meta.addAttributeModifier(attribute, modifier);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Tier attribute rejected on " + attribute
                    + ": " + ex.getMessage());
        }
    }

    /**
     * Ключ модификатора с суффиксом предмета (1.4.124). Раньше ключ был общим
     * ({@code chrdk:tier_movespeed}), и на полном сете из четырёх деталей
     * Minecraft применял только один модификатор.
     */
    private NamespacedKey modifierKey(String tag, String uid) {
        // fu_/pr_/af_ — наши ключи ковки, свойств и аффиксов, остальное — модификаторы тира
        boolean own = tag.startsWith("fu_") || tag.startsWith("pr_")
                || tag.startsWith(TierModifierIds.AFFIX_PREFIX);
        return new NamespacedKey(plugin, TierModifierIds.key(own ? tag : "tier_" + tag, uid));
    }

    /** Атрибуты, которые могут нести бонусы самого тира. */
    private static final List<Attribute> TIER_ATTRIBUTES = List.of(
            Attribute.ATTACK_DAMAGE, Attribute.ARMOR, Attribute.ARMOR_TOUGHNESS,
            Attribute.MOVEMENT_SPEED, Attribute.ATTACK_SPEED, Attribute.BLOCK_BREAK_SPEED,
            // 1.4.142: удача — второй атрибут, который может нести утилита
            // (первый — скорость бега). Без него перекат оставлял бы старый
            // модификатор удачи на предмете навсегда.
            Attribute.LUCK);

    /**
     * Снимает все модификаторы тира — и новые с суффиксом предмета, и старые
     * общие ключи (включая {@code attack_tool}/{@code attackspeed} до 1.4.117).
     */
    private void clearTierModifiers(ItemMeta meta) {
        if (meta.hasAttributeModifiers()) {
            String namespace = key.getNamespace();
            for (Attribute attribute : TIER_ATTRIBUTES) {
                Collection<AttributeModifier> mods = meta.getAttributeModifiers(attribute);
                if (mods == null || mods.isEmpty()) {
                    continue;
                }
                List<AttributeModifier> ours = new ArrayList<>();
                for (AttributeModifier existing : mods) {
                    NamespacedKey existingKey = existing.getKey();
                    if (existingKey.getNamespace().equals(namespace)
                            && existingKey.getKey().startsWith(TierModifierIds.TIER_PREFIX)) {
                        ours.add(existing);
                    }
                }
                for (AttributeModifier existing : ours) {
                    meta.removeAttributeModifier(attribute, existing);
                }
            }
        }
        // Снятие нашего блеска: оверрайд мог быть и true (легенда/эпик), и false
        // (проклятый). На 1.21.5+ геттер падает, если компонента нет, — сначала
        // проверяем hasEnchantmentGlintOverride(). Отпечаток стиля тоже чистим:
        // следующий stampVisuals поставит актуальный.
        if (meta.getPersistentDataContainer().has(visualsKey, PersistentDataType.STRING)
                && meta.hasEnchantmentGlintOverride()) {
            meta.setEnchantmentGlintOverride(null);
        }
        meta.getPersistentDataContainer().remove(visualsKey);
    }

    /** Нужно ли обновить блеск предмета под текущий {@code visuals} конфиг. */
    private boolean needsVisualRefresh(ItemMeta meta, TierDef.Snapshot tier) {
        String stamp = meta.getPersistentDataContainer().get(visualsKey, PersistentDataType.STRING);
        return !plugin.config().visuals().stampFor(tier.id()).equals(stamp);
    }

    /**
     * Ставит блеск тира и запоминает отпечаток стиля. Вызывается после
     * {@link #clearTierModifiers(ItemMeta)} — то есть оверрайд уже снят.
     * Если визуалы выключены, поведение прежних версий: блестит только легенда.
     */
    private void stampVisuals(ItemMeta meta, TierDef.Snapshot tier) {
        TierVisuals visuals = plugin.config().visuals();
        TierVisuals.Glint glint;
        if (visuals.enabled()) {
            TierVisuals.Style style = visuals.styleFor(tier.id());
            glint = style == null ? TierVisuals.Glint.INHERIT : style.glint();
        } else {
            glint = "legendary".equals(tier.id()) ? TierVisuals.Glint.ON : TierVisuals.Glint.INHERIT;
        }
        // INHERIT у старого предмета без отпечатка не трогает чужой оверрайд
        // (ресурспак/другой плагин): снимаем только то, что ставили сами.
        String previous = meta.getPersistentDataContainer()
                .get(visualsKey, PersistentDataType.STRING);
        if (glint != TierVisuals.Glint.INHERIT || previous != null) {
            meta.setEnchantmentGlintOverride(switch (glint) {
                case ON -> Boolean.TRUE;
                case OFF -> Boolean.FALSE;
                case INHERIT -> null;
            });
        }
        meta.getPersistentDataContainer().set(visualsKey, PersistentDataType.STRING,
                visuals.stampFor(tier.id()));
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

    /**
     * Снимает модификаторы улучшений ({@code fu_<id>}) и свойств тира
     * ({@code pr_<id>}) со всех атрибутов. Один проход по атрибутам вместо
     * перебора 85 улучшений × 26 атрибутов: свойств ещё 105, и старый способ
     * заметно тормозил бы пересборку.
     */
    private void clearForgeModifiers(ItemMeta meta) {
        if (!meta.hasAttributeModifiers()) {
            return;
        }
        for (Attribute attribute : FORGE_ATTRIBUTES) {
            Collection<AttributeModifier> mods = meta.getAttributeModifiers(attribute);
            if (mods == null || mods.isEmpty()) {
                continue;
            }
            List<AttributeModifier> ours = new ArrayList<>();
            for (AttributeModifier existing : mods) {
                NamespacedKey existingKey = existing.getKey();
                if (!existingKey.getNamespace().equals(propertiesKey.getNamespace())) {
                    continue;
                }
                String tag = existingKey.getKey();
                if (tag.startsWith("fu_") || tag.startsWith("pr_")
                        || tag.startsWith(TierModifierIds.AFFIX_PREFIX)) {
                    ours.add(existing);
                }
            }
            for (AttributeModifier existing : ours) {
                meta.removeAttributeModifier(attribute, existing);
            }
        }
    }

    private EquipmentSlotGroup slotGroupFor(ItemKind kind) {
        return switch (kind) {
            case HELMET -> EquipmentSlotGroup.HEAD;
            case CHESTPLATE -> EquipmentSlotGroup.CHEST;
            case LEGGINGS -> EquipmentSlotGroup.LEGS;
            case BOOTS -> EquipmentSlotGroup.FEET;
            // 1.4.142: утилиту носят и в основной, и в левой руке (щит — почти
            // всегда в левой), поэтому группа HAND, а не MAINHAND.
            case UTILITY -> EquipmentSlotGroup.HAND;
            default -> EquipmentSlotGroup.MAINHAND;
        };
    }

    /* ------------------------------- имя и лор ------------------------------- */

    /**
     * Разовая выдача клички: если пул включён, у предмета подходящего вида ещё нет
     * клички в PDC и игрок её не переименовывал — тянем случайное имя из пула 45.
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

    /**
     * Задать кличку вручную (админская выдача, 1.4.96). Пересобирает предмет,
     * чтобы имя и лор сразу отражали кличку.
     */
    public void setForgeName(ItemStack item, String name) {
        if (item == null || name == null || name.isBlank()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer()
                .set(forgeNameKey, PersistentDataType.STRING, name.trim());
        item.setItemMeta(meta);
        String tierId = tierOf(item);
        TierDef.Snapshot snapshot = tierId == null ? null : plugin.config().find(tierId);
        if (snapshot != null) {
            apply(item, snapshot);
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
        // 1.4.142: ключ перевода берём из общего помощника — у подсолнуха и
        // фонаря душ он блочный (block.minecraft.*), а не предметный.
        Component name = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                .deserialize(color + TierInfoFormat.languageTag(item.getType()));
        if (meta.hasCustomName() && meta.customName() != null) {
            // кастомное имя игрока сохраняем, красим в цвет тира
            Component colored = net.kyori.adventure.text.minimessage.MiniMessage
                    .miniMessage().deserialize(color).append(meta.customName());
            meta.displayName(colored);
        } else {
            meta.displayName(name);
        }
    }

    /** Миграция обновляет только распознанные строки; чужие маркеры не удаляем. */
    private void refreshOwnedLore(ItemMeta meta, TierDef.Snapshot tier, ItemKind kind) {
        List<Component> before = meta.lore();
        if (before == null) return;
        java.util.Set<String> names = new java.util.HashSet<>();
        for (String id : TierDef.LADDER) names.add(plugin.messages().plain("tier." + id + ".name"));
        Map<String, Component> replacements = new LinkedHashMap<>();
        for (Map.Entry<ForgeUpgrade.Def, Integer> entry : upgradesFromMeta(meta, null).entrySet()) {
            ForgeUpgrade.Def def = entry.getKey();
            int level = entry.getValue();
            rememberLore(replacements, upgradeLine(def, level), null);
            rememberLore(replacements, upgradeLine(def, level, ForgeUpgrade.describeBefore266(def, level)), null);
        }
        for (Map.Entry<ForgeUpgrade.Def, Integer> entry : upgradesFromMeta(meta, kind).entrySet()) {
            Component line = upgradeLine(entry.getKey(), entry.getValue());
            rememberLore(replacements, line, line);
        }
        for (TierProperty property : rawPropertiesFromMeta(meta)) {
            rememberLore(replacements, propertyLine(property), null);
            rememberLore(replacements, propertyLine(property,
                    ForgeUpgrade.describeBefore266(property.asDef(), 1)), null);
        }
        for (TierProperty property : propertiesFromMeta(meta, kind)) {
            Component line = propertyLine(property);
            replacements.put(PlainTextComponentSerializer.plainText().serialize(line), line);
        }
        // Все варианты аффиксов того же uid известны детерминированно, включая отключённые.
        String uid = meta.getPersistentDataContainer().get(modifierUidKey, PersistentDataType.STRING);
        for (TierAffix affix : TierAffixes.possibleForItem(uid, tier.id(), kind)) {
            rememberLore(replacements, affixLine(affix), null);
            rememberLore(replacements, affixLine(affix,
                    ForgeUpgrade.describeBefore266(affix.asDef(), 1)), null);
        }
        for (TierAffix affix : affixesFromMeta(meta, kind)) {
            Component line = affixLine(affix);
            replacements.put(PlainTextComponentSerializer.plainText().serialize(line), line);
        }
        meta.lore(TierLore.refresh(before, names, tierLine(tier, kind), replacements));
    }

    private static void rememberLore(Map<String, Component> replacements, Component known, Component next) {
        replacements.put(PlainTextComponentSerializer.plainText().serialize(known), next);
    }

    private void replaceLore(ItemMeta meta, TierDef.Snapshot tier, ItemKind kind) {
        List<Component> lore = meta.hasLore() && meta.lore() != null
                ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.removeIf(this::isTierLine);
        lore.removeIf(this::isForgeLine);
        lore.removeIf(this::isPropertyLine);
        lore.removeIf(this::isAffixLine);
        lore.add(tierLine(tier, kind));
        // уникальные свойства тира — сразу под плашкой, в порядке выдачи
        for (TierProperty property : propertiesFromMeta(meta, kind)) {
            lore.add(propertyLine(property));
        }
        // волна 17: аффиксы — отдельными строками ◆ (бонус) и ✖ (дефект)
        for (TierAffix affix : affixesFromMeta(meta, kind)) {
            lore.add(affixLine(affix));
        }
        // строки свойств улучшений — после, в порядке реестра
        for (Map.Entry<ForgeUpgrade.Def, Integer> entry : upgradesFromMeta(meta, kind).entrySet()) {
            lore.add(upgradeLine(entry.getKey(), entry.getValue()));
        }
        meta.lore(lore);
    }

    /**
     * Цветная плашка тира в лоре (собирается напрямую: цвет — тег MiniMessage).
     * С 1.4.124 рядом с именем тира стоит его реальная сила — «+60% к урону»,
     * чтобы игрок видел, что даёт редкость, не открывая {@code /tiers info}.
     */
    private Component tierLine(TierDef.Snapshot tier, ItemKind kind) {
        String color = plugin.messages().raw("tier." + tier.id() + ".color");
        String name = plugin.messages().raw("tier." + tier.id() + ".name");
        String bonus = bonusText(kind, tier.percent());
        String line = bonus.isEmpty()
                ? color + name
                : color + name + " <dark_gray>·</dark_gray> <gray>" + bonus + "</gray>";
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(line);
    }

    /** Человеческое описание бонуса тира для лора: «+60% к урону, +90% к добыче». */
    private String bonusText(ItemKind kind, int percent) {
        if (kind == null || percent == 0) {
            return "";
        }
        List<String> parts = new ArrayList<>(2);
        for (TierBonus bonus : TierBonus.forKind(kind)) {
            switch (bonus) {
                case ATTACK_DAMAGE -> parts.add(plugin.messages().raw("tier-bonus-damage",
                        "value", TierMath.signedPercent(TierMath.damageScalar(percent))));
                case PROJECTILE_DAMAGE -> parts.add(plugin.messages().raw("tier-bonus-projectile",
                        "value", TierMath.signedPercent(TierMath.projectileScalar(percent))));
                case MINING_SPEED -> parts.add(plugin.messages().raw("tier-bonus-mining",
                        "value", TierMath.signedPercent(TierMath.miningScalar(percent))));
                case MOVEMENT_SPEED -> {
                    double scalar = TierMath.movementScalar(percent);
                    if (scalar > 0) {
                        parts.add(plugin.messages().raw("tier-bonus-movement",
                                "value", TierMath.signedPercent(scalar)));
                    }
                }
                case LUCK -> {
                    // 1.4.142: удача утилиты может быть и минусом (проклятый тир).
                    double luck = TierMath.luckAmount(percent);
                    if (Math.abs(luck) >= 1.0) {
                        parts.add(plugin.messages().raw("tier-bonus-luck",
                                "value", (luck > 0 ? "+" : "") + (int) luck));
                    }
                }
                case ARMOR -> parts.add(plugin.messages().raw("tier-bonus-armor",
                        "value", TierMath.signedPercent(TierMath.armorScalar(percent))));
                case ARMOR_TOUGHNESS -> parts.add(plugin.messages().raw("tier-bonus-toughness",
                        "value", TierMath.signedPercent(TierMath.toughnessScalar(percent))));
            }
        }
        return String.join(plugin.messages().raw("tier-bonus-separator"), parts);
    }

    /** Строка свойства улучшения: «✦ Кромка III — +6% урона». */
    private Component upgradeLine(ForgeUpgrade.Def def, int level) {
        return upgradeLine(def, level, ForgeUpgrade.describe(def, level));
    }

    private Component upgradeLine(ForgeUpgrade.Def def, int level, String description) {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                "<gold>" + FORGE_LORE_MARKER + " " + def.displayName() + " "
                        + ForgeUpgrade.roman(level) + "</gold>"
                        + " <gray>— " + description + "</gray>");
    }

    /** Строка свойства тира: «✧ Ржавчина — -6% урона». */
    private Component propertyLine(TierProperty property) {
        return propertyLine(property, property.describe());
    }

    private Component propertyLine(TierProperty property, String description) {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                "<aqua>" + PROPERTY_LORE_MARKER + " " + property.name() + "</aqua>"
                        + " <gray>— " + description + "</gray>");
    }

    /** Строка аффикса: «◆ Жажда — вампиризм 2% урона» или «✖ Скол — -0.5 к урону». */
    private Component affixLine(TierAffix affix) {
        return affixLine(affix, affix.describe());
    }

    private Component affixLine(TierAffix affix, String description) {
        String color = affix.good() ? "green" : "red";
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                "<" + color + ">" + affix.polarity().marker() + " " + affix.name()
                        + "</" + color + ">"
                        + " <gray>— " + description + "</gray>");
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

    /** Своя строка свойства тира — по маркеру ✧. */
    private boolean isPropertyLine(Component line) {
        return PlainTextComponentSerializer.plainText().serialize(line).trim()
                .startsWith(PROPERTY_LORE_MARKER);
    }

    /** Своя строка аффикса — по маркерам ◆ (бонус) и ✖ (дефект). */
    private boolean isAffixLine(Component line) {
        String plain = PlainTextComponentSerializer.plainText().serialize(line).trim();
        return plain.startsWith(AFFIX_GOOD_MARKER) || plain.startsWith(AFFIX_BAD_MARKER);
    }
}
