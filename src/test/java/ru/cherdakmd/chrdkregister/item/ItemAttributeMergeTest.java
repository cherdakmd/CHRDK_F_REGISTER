package ru.cherdakmd.chrdkregister.item;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Чистая половина слияния атрибутов: без серверного реестра, {@code Material}
 * и {@code ItemStack}. {@link Attribute} и {@link ItemMeta} — интерфейсы API,
 * поэтому в тестах их заменяют динамические прокси.
 */
class ItemAttributeMergeTest {

    private static final EquipmentSlotGroup HAND = EquipmentSlotGroup.MAINHAND;

    private static Set<String> owned() {
        return Set.of("chrdkcore", "chrdkenchants", "chrdktierforge",
                "chrdk_enchants", "chrdk_tier_forge");
    }

    private static Attribute attribute(String key) {
        NamespacedKey namespaced = NamespacedKey.minecraft(key);
        return (Attribute) Proxy.newProxyInstance(Attribute.class.getClassLoader(),
                new Class<?>[]{Attribute.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getKey" -> namespaced;
                    case "toString" -> namespaced.toString();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static AttributeModifier modifier(String namespace, String key, double amount,
                                              AttributeModifier.Operation operation) {
        return new AttributeModifier(new NamespacedKey(namespace, key), amount, operation, HAND);
    }

    private static Map.Entry<Attribute, AttributeModifier> pair(Attribute attribute,
                                                                AttributeModifier modifier) {
        return Map.entry(attribute, modifier);
    }

    @SafeVarargs
    private static Multimap<Attribute, AttributeModifier> multimap(
            Map.Entry<Attribute, AttributeModifier>... entries) {
        ImmutableMultimap.Builder<Attribute, AttributeModifier> builder = ImmutableMultimap.builder();
        for (Map.Entry<Attribute, AttributeModifier> entry : entries) {
            builder.put(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static final class Meta {
        Multimap<Attribute, AttributeModifier> explicit;

        Meta(Multimap<Attribute, AttributeModifier> explicit) {
            this.explicit = explicit;
        }

        ItemMeta proxy() {
            return (ItemMeta) Proxy.newProxyInstance(ItemMeta.class.getClassLoader(),
                    new Class<?>[]{ItemMeta.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "hasAttributeModifiers" -> explicit != null && !explicit.isEmpty();
                        case "getAttributeModifiers" -> {
                            // Как в CraftMetaItem: пустой компонент тоже даёт null.
                            if (method.getParameterCount() == 0) {
                                yield explicit != null && !explicit.isEmpty() ? explicit : null;
                            }
                            yield explicit == null ? null : explicit.get((Attribute) args[0]);
                        }
                        case "getAsComponentString" -> componentString();
                        case "setAttributeModifiers" -> {
                            explicit = (Multimap<Attribute, AttributeModifier>) args[0];
                            yield null;
                        }
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "Meta";
                        default -> null;
                    });
        }

        String componentString() {
            if (explicit == null) return "[]";
            if (explicit.isEmpty()) return "[!minecraft:attribute_modifiers]";
            return "[minecraft:attribute_modifiers=" + explicit + "]";
        }

        Set<String> keys(Attribute attribute) {
            Set<String> result = new LinkedHashSet<>();
            if (explicit != null) {
                for (AttributeModifier modifier : explicit.get(attribute)) {
                    result.add(modifier.getKey().toString());
                }
            }
            return result;
        }

        List<AttributeModifier> modifiers(Attribute attribute) {
            return explicit == null ? List.of() : List.copyOf(explicit.get(attribute));
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void uuidIsDerivedFromKeyButStableIdAlsoUsesAttribute() {
        Attribute damage = attribute("attack_damage");
        Attribute speed = attribute("attack_speed");
        AttributeModifier base = modifier("minecraft", "base_attack_speed", -2.4,
                AttributeModifier.Operation.ADD_NUMBER);
        AttributeModifier sameKeyOtherAttribute = modifier("minecraft", "base_attack_speed", -2.4,
                AttributeModifier.Operation.ADD_NUMBER);

        assertEquals(base.getUniqueId(), sameKeyOtherAttribute.getUniqueId(),
                "устаревший UUID выводится из NamespacedKey и не различает атрибуты");
        assertNotEquals(ItemAttributeMerge.stableId(damage, base),
                ItemAttributeMerge.stableId(speed, sameKeyOtherAttribute));
        assertEquals(ItemAttributeMerge.stableId(speed, base),
                ItemAttributeMerge.stableId(speed, sameKeyOtherAttribute));
    }

    @Test
    void mergeKeepsVanillaMineTreasureEnchantTierAndForeignModifiers() {
        Attribute damage = attribute("attack_damage");
        Attribute speed = attribute("attack_speed");
        AttributeModifier vanillaDamage = modifier("minecraft", "base_attack_damage", 7.0,
                AttributeModifier.Operation.ADD_NUMBER);
        AttributeModifier vanillaSpeed = modifier("minecraft", "base_attack_speed", -2.4,
                AttributeModifier.Operation.ADD_NUMBER);
        Multimap<Attribute, AttributeModifier> defaults = multimap(
                pair(damage, vanillaDamage), pair(speed, vanillaSpeed));

        AttributeModifier mineSpeed = modifier("minetreasure", "hurricane_speed", 1.0,
                AttributeModifier.Operation.ADD_NUMBER);
        AttributeModifier foreignDamage = modifier("otherplugin", "bonus", 1.0,
                AttributeModifier.Operation.ADD_NUMBER);
        Multimap<Attribute, AttributeModifier> sourceExplicit = multimap(
                pair(speed, mineSpeed), pair(damage, vanillaDamage), pair(damage, foreignDamage));
        ItemAttributeMerge.Snapshot source = ItemAttributeMerge.capture(
                null, sourceExplicit, defaults, owned(), "left");
        assertTrue(source.customBaseAttributes().contains(speed));
        assertFalse(source.customBaseAttributes().contains(damage),
                "чужой аддитивный бонус рядом с ванильным ключом — не замена базы");

        AttributeModifier tierDamage = modifier("chrdktierforge", "tier_attack_uid", 0.12,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        AttributeModifier enchSpeed = modifier("chrdkenchants", "ench_haste_3", 0.2,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        Meta meta = new Meta(multimap(
                pair(damage, vanillaDamage), pair(damage, tierDamage), pair(damage, foreignDamage),
                pair(speed, vanillaSpeed), pair(speed, enchSpeed)));

        assertTrue(ItemAttributeMerge.merge(meta.proxy(), defaults, List.of(source), owned()));
        assertEquals(Set.of("minecraft:base_attack_damage", "chrdktierforge:tier_attack_uid",
                "otherplugin:bonus"), meta.keys(damage));
        assertEquals(Set.of("minetreasure:hurricane_speed", "chrdkenchants:ench_haste_3"),
                meta.keys(speed), "ванильная скорость не должна добавляться поверх базы Mine Treasure");
        assertFalse(meta.keys(speed).contains("minecraft:base_attack_speed"));
        assertFalse(ItemAttributeMerge.merge(meta.proxy(), defaults, List.of(source), owned()),
                "повторное слияние идемпотентно");
    }

    @Test
    void conflictingForeignKeysAreRekeyedWithoutDataLoss() {
        Attribute speed = attribute("attack_speed");
        AttributeModifier vanillaSpeed = modifier("minecraft", "base_attack_speed", -2.4,
                AttributeModifier.Operation.ADD_NUMBER);
        Multimap<Attribute, AttributeModifier> defaults = multimap(pair(speed, vanillaSpeed));
        AttributeModifier first = modifier("minetreasure", "shared", 1.0,
                AttributeModifier.Operation.ADD_NUMBER);
        AttributeModifier second = modifier("minetreasure", "shared", 0.0,
                AttributeModifier.Operation.ADD_NUMBER);
        ItemAttributeMerge.Snapshot source = ItemAttributeMerge.capture(null,
                multimap(pair(speed, first)), defaults, owned(), "left");
        Meta meta = new Meta(multimap(pair(speed, second)));

        assertTrue(ItemAttributeMerge.merge(meta.proxy(), defaults, List.of(source), owned()));
        List<AttributeModifier> modifiers = meta.modifiers(speed);
        assertEquals(2, modifiers.size());
        Set<Double> amounts = new HashSet<>();
        for (AttributeModifier modifier : modifiers) amounts.add(modifier.getAmount());
        assertEquals(Set.of(1.0, 0.0), amounts);
        assertTrue(modifiers.stream().anyMatch(m -> m.getKey().toString().equals("minetreasure:shared")));
        assertTrue(modifiers.stream().anyMatch(m
                -> m.getKey().toString().startsWith("minetreasure:shared_compat_")));
        assertTrue(modifiers.stream().allMatch(m -> m.getKey().getNamespace().equals("minetreasure")),
                "префикс и namespace сохраняются для штатной очистки плагина");
    }

    @Test
    void ownedSourceModifierDoesNotOverrideNewerTarget() {
        Attribute speed = attribute("attack_speed");
        Multimap<Attribute, AttributeModifier> defaults = multimap();
        AttributeModifier stale = modifier("chrdkenchants", "ench_haste_1", 0.1,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        AttributeModifier fresh = modifier("chrdkenchants", "ench_haste_1", 0.2,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        ItemAttributeMerge.Snapshot source = ItemAttributeMerge.capture(null,
                multimap(pair(speed, stale)), defaults, owned(), "left");
        Meta meta = new Meta(multimap(pair(speed, fresh)));

        assertFalse(ItemAttributeMerge.merge(meta.proxy(), defaults, List.of(source), owned()));
        assertEquals(1, meta.modifiers(speed).size());
        assertEquals(0.2, meta.modifiers(speed).get(0).getAmount());
    }

    @Test
    void explicitVanillaBaseFromSourceRestoresCorrectAmount() {
        Attribute speed = attribute("attack_speed");
        AttributeModifier correct = modifier("minecraft", "base_attack_speed", 1.0,
                AttributeModifier.Operation.ADD_NUMBER);
        AttributeModifier wrong = modifier("minecraft", "base_attack_speed", 0.0,
                AttributeModifier.Operation.ADD_NUMBER);
        Multimap<Attribute, AttributeModifier> defaults = multimap(pair(speed, wrong));
        ItemAttributeMerge.Snapshot source = ItemAttributeMerge.capture(null,
                multimap(pair(speed, correct)), defaults, owned(), "left");
        Meta meta = new Meta(multimap(pair(speed, wrong)));

        assertTrue(ItemAttributeMerge.merge(meta.proxy(), defaults, List.of(source), owned()));
        assertEquals(1, meta.modifiers(speed).size());
        assertEquals(1.0, meta.modifiers(speed).get(0).getAmount(),
                "база 1.0 восстановлена вместо перезаписанного нуля");
        assertEquals("minecraft:base_attack_speed",
                meta.modifiers(speed).get(0).getKey().toString());
    }

    @Test
    void emptyExplicitComponentIsRemovedInsteadOfOverridingDefaults() {
        Attribute speed = attribute("attack_speed");
        Multimap<Attribute, AttributeModifier> defaults = multimap(pair(speed,
                modifier("minecraft", "base_attack_speed", -2.4,
                        AttributeModifier.Operation.ADD_NUMBER)));
        Meta meta = new Meta(ImmutableMultimap.of());

        assertTrue(ItemAttributeMerge.merge(meta.proxy(), defaults, List.of(), owned()));
        assertNull(meta.explicit, "пустая карта перекрывала бы ванильные значения");
    }

    @Test
    void vanillaDefaultsAloneDoNotCreateExplicitComponent() {
        Attribute speed = attribute("attack_speed");
        Multimap<Attribute, AttributeModifier> defaults = multimap(pair(speed,
                modifier("minecraft", "base_attack_speed", -2.4,
                        AttributeModifier.Operation.ADD_NUMBER)));
        Meta meta = new Meta(null);

        assertFalse(ItemAttributeMerge.merge(meta.proxy(), defaults, List.of(), owned()));
        assertNull(meta.explicit);
    }

    @Test
    void rekeyIsDeterministicLowercaseAndKeepsNamespace() {
        NamespacedKey original = new NamespacedKey("chrdkenchants", "ench_haste_3");
        NamespacedKey first = ItemAttributeMerge.uniqueKey(original, "stable-seed");
        NamespacedKey second = ItemAttributeMerge.uniqueKey(original, "stable-seed");

        assertEquals(first, second);
        assertEquals(original.getNamespace(), first.getNamespace());
        assertNotEquals(original.getKey(), first.getKey());
        assertTrue(first.getKey().startsWith("ench_haste_3_compat_"));
        assertTrue(first.getKey().matches("[a-z0-9/._-]+"));
    }
}
