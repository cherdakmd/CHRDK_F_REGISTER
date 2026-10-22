package ru.cherdakmd.chrdkpets;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;

import java.util.List;

/**
 * Транзитные модификаторы питомца и его хозяина.
 *
 * <p>Модификаторы не попадают в NBT и не переживают перезаход — это осознанно:
 * потеря после рестарта лечится тем, что модуль переставляет бонусы при
 * загрузке чанка и пульсом ауры. Зато снятие модуля или уменьшение уровня
 * никогда не оставляет «зашитых» в сущность сердец и скорости.</p>
 *
 * <p>Ключ модификатора всегда один и тот же для своей роли: перед установкой
 * старый удаляется, поэтому повторный вызов идемпотентен и не плодит дубликаты.</p>
 */
final class PetAttributes {

    private PetAttributes() {
    }

    /** Ставит модификатор или снимает его, если величина нулевая. */
    static void apply(LivingEntity entity, Attribute attribute, NamespacedKey key,
                      double amount, AttributeModifier.Operation operation) {
        if (entity == null || attribute == null || key == null || operation == null) {
            return;
        }
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        remove(instance, key);
        if (Math.abs(amount) < 1.0e-6 || Double.isNaN(amount)) {
            return;
        }
        try {
            instance.addTransientModifier(new AttributeModifier(key, amount, operation));
        } catch (IllegalArgumentException ignored) {
            // атрибут не принимает такую комбинацию — бонус просто не сработает
        }
    }

    /** Снимает модификатор по ключу; отсутствие — не ошибка. */
    static void remove(LivingEntity entity, Attribute attribute, NamespacedKey key) {
        if (entity == null || attribute == null || key == null) {
            return;
        }
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            remove(instance, key);
        }
    }

    private static void remove(AttributeInstance instance, NamespacedKey key) {
        for (AttributeModifier modifier : List.copyOf(instance.getModifiers())) {
            if (modifier.getKey().equals(key)) {
                instance.removeModifier(modifier);
            }
        }
    }
}
