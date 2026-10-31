package ru.cherdakmd.chrdkartifacts;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;

/**
 * Замена одного атрибутного свойства без обращения к серверному реестру.
 * Каталог хранит проценты как 8, а Minecraft принимает долю 0.08.
 * Абсолютные единицы (здоровье, дальность, сопротивление отбросу) не масштабируем.
 */
final class ArtifactTraitModifiers {

    /** Ниже одного сердца свойство «Слабое сердце» не опускает базовое здоровье. */
    private static final double MIN_MAX_HEALTH = 2.0;

    private ArtifactTraitModifiers() {
    }

    /**
     * Постоянный ключ позволяет заменить и старую ошибочную величину, и другой
     * уровень без накопления. Нулевой уровень только снимает наше свойство;
     * чужие модификаторы и базовое значение атрибута не меняются.
     */
    static void replace(AttributeInstance instance, NamespacedKey key,
                        ArtifactTraits.Def def, int level) {
        AttributeModifier existing = instance.getModifier(key);
        if (level <= 0 || def.kind() == ArtifactTraits.Kind.POTION) {
            if (existing != null) instance.removeModifier(key);
            return;
        }
        double amount = def.kind().percent() ? def.value(level) / 100.0 : def.value(level);
        if (def.kind() == ArtifactTraits.Kind.MAX_HEALTH && amount < 0) {
            amount = -Math.min(Math.max(0, instance.getBaseValue() - MIN_MAX_HEALTH), -amount);
        }
        if (amount == 0) {
            if (existing != null) instance.removeModifier(key);
            return;
        }
        AttributeModifier.Operation operation = def.kind().percent()
                ? AttributeModifier.Operation.MULTIPLY_SCALAR_1
                : AttributeModifier.Operation.ADD_NUMBER;
        // Не снимаем и не возвращаем неизменный бонус каждый пульс, особенно здоровье.
        if (existing != null && existing.getOperation() == operation
                && Double.compare(existing.getAmount(), amount) == 0) {
            return;
        }
        if (existing != null) instance.removeModifier(key);
        try {
            instance.addTransientModifier(new AttributeModifier(key, amount, operation));
        } catch (IllegalArgumentException ignored) {
            // Атрибут не принимает такой модификатор — свойство просто не сработает.
        }
    }
}
