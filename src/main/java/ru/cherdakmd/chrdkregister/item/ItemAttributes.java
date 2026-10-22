package ru.cherdakmd.chrdkregister.item;

import com.google.common.collect.Multimap;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ванильные атрибуты предмета при навешивании собственных модификаторов.
 *
 * <p>С 1.20.5 атрибуты предмета — это data-компонент {@code attribute_modifiers}.
 * У меча, топора, брони и инструмента он заполнен по умолчанию (базовый урон,
 * скорость атаки, защита). Но {@link ItemMeta#addAttributeModifier} пишет
 * компонент целиком: как только плагин добавляет свой модификатор, стек
 * начинает хранить <b>только</b> его, а ванильные значения перестают
 * действовать. Официальная документация {@link ItemMeta#setAttributeModifiers}
 * говорит об этом прямо: пустая карта «перекрывает модификаторы по умолчанию».</p>
 *
 * <p>Отсюда и баг 1.4.139: алмазный меч с тиром +10% бил не на 7 урона,
 * а на {@code 1 × 1.1 ≈ 1} — то есть «полсердечка». Лечение — перед первым
 * своим модификатором вернуть в компонент ванильные значения предмета
 * ({@link ItemStack#getType()} → {@code getDefaultAttributeModifiers()}):
 * дальше проценты тира, чар и находок считаются от настоящей базы.</p>
 *
 * <p>Класс — общий для всех аддонов CHRDK (TierForge, Enchants, Treasures).
 * Метод {@link #keep(ItemStack, ItemMeta)} идемпотентен: повторный вызов ничего
 * не меняет, поэтому его можно звать из любого пути сборки предмета. Чистая
 * часть ({@link #missing(Collection, Collection)}) проверяется юнит-тестами без
 * серверного реестра.</p>
 */
public final class ItemAttributes {

    private ItemAttributes() {
    }

    /**
     * Возвращает в мету ванильные модификаторы предмета, не трогая уже
     * записанные (свои и чужие). Вызывать <b>до</b> добавления собственных
     * модификаторов и до {@link ItemStack#setItemMeta(ItemMeta)}.
     *
     * @param item предмет, у которого берутся значения по умолчанию
     * @param meta редактируемая копия меты этого предмета
     * @return {@code true} — в мету добавлен хотя бы один ванильный модификатор
     */
    public static boolean keep(ItemStack item, ItemMeta meta) {
        if (item == null || meta == null) {
            return false;
        }
        final Multimap<Attribute, AttributeModifier> defaults;
        try {
            defaults = item.getType().getDefaultAttributeModifiers();
        } catch (RuntimeException exception) {
            // не предмет (воздух/блок) или нет серверного реестра — трогать нечего
            return false;
        }
        if (defaults.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (Map.Entry<Attribute, AttributeModifier> entry : defaults.entries()) {
            Attribute attribute = entry.getKey();
            Collection<AttributeModifier> present = meta.getAttributeModifiers(attribute);
            for (AttributeModifier missing : missing(present, List.of(entry.getValue()))) {
                try {
                    if (meta.addAttributeModifier(attribute, missing)) {
                        changed = true;
                    }
                } catch (IllegalArgumentException ignored) {
                    // такой id уже стоит (гонка/дубликат) — не повод падать
                }
            }
        }
        return changed;
    }

    /**
     * Убирает пустой компонент атрибутов. Если своих модификаторов не осталось,
     * стек должен снова наследовать ванильные значения предмета: пустая карта,
     * в отличие от {@code null}, их перекрывает.
     *
     * <p>Вызывать после снятия своих модификаторов и до
     * {@link ItemStack#setItemMeta(ItemMeta)}. Для меты без модификаторов это
     * безвредная операция.</p>
     */
    public static void dropEmpty(ItemMeta meta) {
        if (meta != null && !meta.hasAttributeModifiers()) {
            meta.setAttributeModifiers(null);
        }
    }

    /**
     * Ванильные модификаторы, которых ещё нет среди записанных. Сравнение идёт
     * по {@link AttributeModifier#getKey()}: Minecraft склеивает модификаторы с
     * одинаковым id, и повторное добавление того же ключа — исключение.
     *
     * @param present  уже записанные модификаторы (может быть {@code null})
     * @param defaults ванильные значения предмета (может быть {@code null})
     * @return недостающие модификаторы в порядке {@code defaults}; возможно пусто
     */
    public static List<AttributeModifier> missing(Collection<AttributeModifier> present,
                                                  Collection<AttributeModifier> defaults) {
        List<AttributeModifier> result = new ArrayList<>();
        if (defaults == null || defaults.isEmpty()) {
            return result;
        }
        Set<NamespacedKey> keys = new LinkedHashSet<>();
        if (present != null) {
            for (AttributeModifier modifier : present) {
                if (modifier != null && modifier.getKey() != null) {
                    keys.add(modifier.getKey());
                }
            }
        }
        for (AttributeModifier modifier : defaults) {
            if (modifier == null || modifier.getKey() == null) {
                continue;
            }
            if (keys.add(modifier.getKey())) {
                result.add(modifier);
            }
        }
        return result;
    }
}
