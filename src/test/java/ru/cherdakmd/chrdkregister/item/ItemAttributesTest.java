package ru.cherdakmd.chrdkregister.item;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверки чистой половины {@link ItemAttributes}: какие ванильные модификаторы
 * нужно вернуть в мету, чтобы свои бонусы не стирали базовый урон предмета.
 *
 * <p>Серверный реестр здесь не нужен: {@link AttributeModifier} и
 * {@link EquipmentSlotGroup} — обычные объекты API, а {@code Material} и
 * {@code Attribute} в тестах не трогаем (без сервера они не инициализируются).</p>
 */
class ItemAttributesTest {

    private static AttributeModifier modifier(String key, double amount) {
        return new AttributeModifier(new NamespacedKey("chrdk-test", key), amount,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND);
    }

    @Test
    void allDefaultsAreMissingWhenNothingIsSet() {
        List<AttributeModifier> defaults = List.of(
                modifier("base_attack_damage", 7.0),
                modifier("base_attack_speed", -2.4));

        assertEquals(defaults, ItemAttributes.missing(null, defaults));
        assertEquals(defaults, ItemAttributes.missing(List.of(), defaults));
    }

    @Test
    void presentBaseModifierIsNotDuplicated() {
        AttributeModifier damage = modifier("base_attack_damage", 7.0);
        AttributeModifier speed = modifier("base_attack_speed", -2.4);
        AttributeModifier ours = modifier("tier_attack_3f9a1c0b7d2e", 0.12);

        List<AttributeModifier> missing = ItemAttributes.missing(
                List.of(ours, damage), List.of(damage, speed));

        assertEquals(List.of(speed), missing, "уже стоящая база не добавляется повторно");
    }

    @Test
    void ownModifiersDoNotCountAsBase() {
        AttributeModifier ours = modifier("tier_attack_3f9a1c0b7d2e", 0.12);
        AttributeModifier base = modifier("base_attack_damage", 7.0);

        assertEquals(List.of(base), ItemAttributes.missing(List.of(ours), List.of(base)));
    }

    @Test
    void emptyOrNullDefaultsMeanNothingToRestore() {
        assertTrue(ItemAttributes.missing(null, null).isEmpty());
        assertTrue(ItemAttributes.missing(List.of(), List.of()).isEmpty());
    }

    @Test
    void duplicatesInsideDefaultsAreCollapsedByKey() {
        AttributeModifier base = modifier("base_attack_damage", 7.0);
        AttributeModifier same = modifier("base_attack_damage", 7.0);

        List<AttributeModifier> missing = ItemAttributes.missing(null, List.of(base, same));

        assertEquals(1, missing.size());
        assertSame(base, missing.get(0), "первый по порядку и остаётся");
    }
}
