package ru.cherdakmd.chrdkenchants;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import ru.cherdakmd.chrdkregister.item.ItemAttributes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Работа с предметами: PDC-метка чар ("id:level;..."), книга чары как предмет,
 * аккуратная перерисовка лора (наши строки помечаются маркером «✶ ») и
 * атрибутные модификаторы светлых чар/проклятий (1.4.128).
 *
 * <p>Атрибуты живут на предмете под ключами {@code ench_<id>_<level>} в
 * пространстве имён плагина: они не конфликтуют с тирами и ковкой и снимаются
 * только нами при пересборке чар.</p>
 *
 * <p>Только серверный рантайм — в юнит-тестах не использовать.</p>
 */
public final class EnchantItems {

    public static final String PDC_KEY = "custom_enchants";
    /**
     * Маркер строк лора чар. С 1.4.128 — «✶ », а не «✦ »: ровно этим маркером
     * TierForge помечает строки своих свойств, и общий символ заставлял бы
     * аддоны стирать лор друг друга при пересборке предмета.
     */
    public static final String LORE_MARKER = "✶ ";
    /** Маркер прежних версий (1.4.13–1.4.127): снимаем только «свои» строки. */
    public static final String LEGACY_LORE_MARKER = "✦ ";
    /** Префикс ключей атрибутных модификаторов чар. */
    public static final String ATTRIBUTE_PREFIX = "ench_";

    /** Атрибуты, которые модуль трогает: снимаются одним проходом при пересборке. */
    private static final Map<EnchantAttributeMap.Target, Attribute> ATTRIBUTES = Map.of(
            EnchantAttributeMap.Target.ATTACK_DAMAGE, Attribute.ATTACK_DAMAGE,
            EnchantAttributeMap.Target.ATTACK_SPEED, Attribute.ATTACK_SPEED,
            EnchantAttributeMap.Target.ARMOR, Attribute.ARMOR,
            EnchantAttributeMap.Target.MOVEMENT_SPEED, Attribute.MOVEMENT_SPEED);

    private final EnchantsPlugin plugin;
    private final NamespacedKey key;
    /** Кэш имён чар — нужен, чтобы отличить старые строки чар от строк свойств тира. */
    private List<String> cachedNames;

    public EnchantItems(EnchantsPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, PDC_KEY);
    }

    /** Все чары предмета (id→уровень). */
    public Map<String, Integer> read(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return Map.of();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return Map.of();
        }
        if (!meta.getPersistentDataContainer().has(key, PersistentDataType.STRING)) return Map.of();
        String raw = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return raw == null ? Map.of() : Map.copyOf(EnchantCodec.decode(raw));
    }

    /** Наличие метки проверяется и при повреждённом типе/пустой строке. */
    boolean marked(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(key);
    }
    Map<String, Integer> validated(ItemStack item) {
        if (!marked(item)) return Map.of();
        var data = item.getItemMeta().getPersistentDataContainer();
        return data.has(key, PersistentDataType.STRING) ? EnchantBookValidation.read(data.get(key, PersistentDataType.STRING)) : null;
    }

    /** Уровень конкретной чары на предмете (0 — нет). */
    public int levelOf(ItemStack item, String id) {
        return read(item).getOrDefault(id, 0);
    }

    /** Точно записывает карту чар в PDC и перерисовывает лор. */
    public void write(ItemStack item, Map<String, Integer> enchants) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        String encoded = EnchantCodec.encode(enchants);
        if (encoded.isEmpty()) {
            meta.getPersistentDataContainer().remove(key);
        } else {
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, encoded);
        }
        applyAttributes(item, meta, enchants);
        refreshLore(meta, enchants);
        item.setItemMeta(meta);
    }

    /**
     * Перерисовка без изменения набора: лор и атрибуты восстанавливаются из PDC.
     * Нужна тир-предметам — их пересборка пересоздаёт лор с нуля.
     */
    public void refresh(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        Map<String, Integer> enchants = read(item);
        applyAttributes(item, meta, enchants);
        refreshLore(meta, enchants);
        item.setItemMeta(meta);
    }

    /**
     * Возвращает предмету ванильную базу атрибутов, если она потерялась раньше
     * (1.4.140). До исправления первый же атрибутный модификатор чары писал
     * компонент {@code attribute_modifiers} целиком и стирал ванильный урон
     * меча — предмет с «+4% к урону» бил на «полсердечка». Чинит уже выданные
     * предметы: вызывается при входе игрока.
     *
     * @return {@code true} — предмет изменён (инвентарь стоит перерисовать)
     */
    public boolean repairAttributes(ItemStack item) {
        if (item == null || !item.hasItemMeta() || read(item).isEmpty()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !ItemAttributes.keep(item, meta)) {
            return false;
        }
        item.setItemMeta(meta);
        return true;
    }

    /** Добавляет чару с уровнем (правила применимости — на вызывающем). */
    public void apply(ItemStack item, EnchantDef def, int level) {
        Map<String, Integer> enchants = new LinkedHashMap<>(read(item));
        enchants.put(def.id(), Math.min(def.maxLevel(), Math.max(1, level)));
        write(item, enchants);
    }

    /** Книга чар (жители, выдача админом, наковальня). */
    public ItemStack makeEnchantBook(EnchantDef def, int level) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        apply(book, def, level);
        ItemMeta meta = book.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.messages().get(
                    def.curse() ? "gui-book-name-curse" : "gui-book-name",
                    "name", plugin.messages().mini("en." + def.id() + ".name")));
            book.setItemMeta(meta);
        }
        return book;
    }

    /** Это наша книга чар? */
    public boolean isEnchantBook(ItemStack item) {
        return item != null && item.getType() == Material.ENCHANTED_BOOK
                && !read(item).isEmpty();
    }

    /** Чары на сущности (стрелы и пр.) через её PDC. */
    public Map<String, Integer> readEntity(org.bukkit.entity.Entity entity) {
        if (entity == null) {
            return Map.of();
        }
        if (!entity.getPersistentDataContainer().has(key, PersistentDataType.STRING)) return Map.of();
        String raw = entity.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return raw == null ? Map.of() : Map.copyOf(EnchantCodec.decode(raw));
    }

    /** Записывает чары на сущность (стрела при выстреле). */
    public void writeEntity(org.bukkit.entity.Entity entity, Map<String, Integer> enchants) {
        String encoded = EnchantCodec.encode(enchants);
        if (encoded.isEmpty()) {
            entity.getPersistentDataContainer().remove(key);
        } else {
            entity.getPersistentDataContainer().set(key, PersistentDataType.STRING, encoded);
        }
    }

    public NamespacedKey key() {
        return key;
    }

    /** Есть ли на предмете хоть одна из указанных чар. */
    public boolean hasEnchant(ItemStack item, String... ids) {
        Map<String, Integer> enchants = read(item);
        for (String id : ids) {
            if (enchants.containsKey(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Перерисовывает секцию лора чар: старые наши строки (с маркером) удаляются,
     * новые добавляются по реестру.
     */
    private void refreshLore(ItemMeta meta, Map<String, Integer> enchants) {
        List<Component> lore = meta.hasLore() && meta.lore() != null
                ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.removeIf(this::isOurs);
        for (Map.Entry<String, Integer> entry : new LinkedHashMap<>(enchants).entrySet()) {
            EnchantDef def = EnchantRegistry.byId(entry.getKey());
            if (def == null) continue;
            // 1.4.128: проклятия пишутся красным — по лору видно, что это минус
            lore.add(plugin.messages().get(def.curse() ? "lore-line-curse" : "lore-line",
                    "name", plugin.messages().mini("en." + def.id() + ".name"),
                    "levelroman", EnchantCodec.roman(entry.getValue())));
            if (meta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta) {
                lore.add(plugin.messages().get("book-rarity", "rarity", plugin.messages().mini(def.rarity().labelKey()),
                        "maxlevel", def.maxLevel()));
                String groups = plugin.gui().supportedLabel(def);
                lore.add(plugin.messages().get("book-items", "items", ru.cherdakmd.chrdkregister.Messages.trusted(groups)));
                for (Component description : plugin.messages().getList("en." + def.id() + ".lore"))
                    lore.add(Component.text("✶ ").append(description));
                if (def.conflictGroup() != null) {
                    String conflicts = String.join(", ", EnchantRegistry.all().stream()
                            .filter(other -> def.conflictGroup().equals(other.conflictGroup()) && !def.id().equals(other.id()))
                            .map(other -> plugin.messages().raw("en." + other.id() + ".name")).toList());
                    if (!conflicts.isEmpty()) lore.add(plugin.messages().get("book-conflicts", "conflicts", ru.cherdakmd.chrdkregister.Messages.trusted(conflicts)));
                }
                lore.add(plugin.messages().get("book-apply"));
            }
        }
        meta.lore(lore.isEmpty() ? null : lore);
    }

    /**
     * Пересобирает атрибутные модификаторы чар: сначала снимает все наши
     * ({@code ench_*}), затем кладёт актуальные из {@link EnchantAttributeMap}.
     * Слот выбирается по виду предмета: оружие — рука, броня — своя деталь.
     *
     * <p>Перед первым своим модификатором в мету возвращаются ванильные
     * значения предмета (1.4.140): иначе компонент атрибутов перекрывал бы
     * базовый урон и скорость атаки.</p>
     */
    private void applyAttributes(ItemStack item, ItemMeta meta, Map<String, Integer> enchants) {
        clearAttributes(meta);
        if (enchants.isEmpty() || meta == null) {
            // своих модификаторов не осталось — пустой компонент не должен
            // перекрывать ванильные атрибуты предмета (1.4.140)
            ItemAttributes.dropEmpty(meta);
            return;
        }
        EquipmentSlotGroup slot = slotGroupFor(ItemGroup.classify(item.getType().name()));
        if (slot == null) {
            ItemAttributes.dropEmpty(meta);
            return;
        }
        boolean defaultsKept = false;
        for (Map.Entry<String, Integer> entry : enchants.entrySet()) {
            EnchantDef def = EnchantRegistry.byId(entry.getKey());
            if (def == null) {
                continue;
            }
            for (EnchantAttributeMap.Modifier modifier
                    : EnchantAttributeMap.modifiers(def.id(), entry.getValue())) {
                Attribute attribute = ATTRIBUTES.get(modifier.target());
                if (attribute == null) {
                    continue;
                }
                NamespacedKey modifierKey = new NamespacedKey(plugin,
                        ATTRIBUTE_PREFIX + def.id() + "_" + entry.getValue());
                AttributeModifier.Operation operation = modifier.kind()
                        == EnchantAttributeMap.Kind.PERCENT
                        ? AttributeModifier.Operation.MULTIPLY_SCALAR_1
                        : AttributeModifier.Operation.ADD_NUMBER;
                AttributeModifier built = new AttributeModifier(modifierKey,
                        modifier.amount(), operation, slot);
                if (!defaultsKept) {
                    ItemAttributes.keep(item, meta);
                    defaultsKept = true;
                }
                try {
                    meta.addAttributeModifier(attribute, built);
                } catch (IllegalArgumentException ignored) {
                    // кривая комбинация атрибут/слот не должна ломать выдачу чары
                }
            }
        }
    }

    /** Снимает все наши модификаторы чар со всех четырёх атрибутов. */
    private void clearAttributes(ItemMeta meta) {
        if (meta == null || !meta.hasAttributeModifiers()) {
            return;
        }
        String namespace = key.getNamespace();
        for (Attribute attribute : ATTRIBUTES.values()) {
            Collection<AttributeModifier> present = meta.getAttributeModifiers(attribute);
            if (present == null || present.isEmpty()) {
                continue;
            }
            List<AttributeModifier> ours = new ArrayList<>();
            for (AttributeModifier existing : present) {
                NamespacedKey existingKey = existing.getKey();
                if (existingKey.getNamespace().equals(namespace)
                        && existingKey.getKey().startsWith(ATTRIBUTE_PREFIX)) {
                    ours.add(existing);
                }
            }
            for (AttributeModifier existing : ours) {
                meta.removeAttributeModifier(attribute, existing);
            }
        }
    }

    /** Слот, к которому привязан модификатор: оружие — рука, броня — своя деталь. */
    private static EquipmentSlotGroup slotGroupFor(ItemGroup group) {
        if (group == null) {
            return null;
        }
        return switch (group) {
            case SWORD, AXE, BOW, CROSSBOW, PICKAXE, SHOVEL, HOE -> EquipmentSlotGroup.MAINHAND;
            case HELMET -> EquipmentSlotGroup.HEAD;
            case CHESTPLATE -> EquipmentSlotGroup.CHEST;
            case LEGGINGS -> EquipmentSlotGroup.LEGS;
            case BOOTS -> EquipmentSlotGroup.FEET;
        };
    }

    /**
     * Наша ли строка лора. Новые строки узнаются по «✶ ». Старые («✦ ») снимаем
     * только если после маркера стоит имя известной чары и римский уровень:
     * у TierForge тот же символ, но за ним идут названия свойств и числа.
     */
    private boolean isOurs(Component line) {
        String plain = PlainTextComponentSerializer.plainText().serialize(line).trim();
        if (plain.startsWith(LORE_MARKER)) {
            return true;
        }
        if (!plain.startsWith(LEGACY_LORE_MARKER)) {
            return false;
        }
        String rest = plain.substring(LEGACY_LORE_MARKER.length()).trim();
        for (String name : enchantNames()) {
            if (!rest.startsWith(name)) {
                continue;
            }
            String tail = rest.substring(name.length()).trim();
            if (tail.isEmpty() || tail.matches("[IVX]+")) {
                return true;
            }
        }
        return false;
    }

    /** Имена всех чар в текущей локали (кэш до перезагрузки плагина). */
    private List<String> enchantNames() {
        List<String> names = cachedNames;
        if (names != null) {
            return names;
        }
        List<String> built = new ArrayList<>();
        for (EnchantDef def : EnchantRegistry.all()) {
            String name = plugin.messages().plain("en." + def.id() + ".name").trim();
            if (!name.isEmpty()) {
                built.add(name);
            }
        }
        built.sort((left, right) -> right.length() - left.length());
        cachedNames = List.copyOf(built);
        return cachedNames;
    }
}
