package ru.cherdakmd.chrdkartifacts;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Настоящие объекты AttributeModifier и тот же путь замены, что у пульса игрока.
 * Подмена AttributeInstance хранит ключи и ловит дубли/постоянные модификаторы;
 * серверный реестр Attribute и Minecraft-мир для этих проверок не нужны.
 */
class ArtifactTraitModifiersTest {
    private static final double EPSILON = 1e-12;
    private static final NamespacedKey FOREIGN = new NamespacedKey("other", "damage");

    private static NamespacedKey key(ArtifactTraits.Def def) {
        return new NamespacedKey("chrdk_artifacts", "trait_" + def.id());
    }

    private static Stream<Arguments> percentages() {
        return Stream.of(
                Arguments.of("silnyj-zahvat", new double[]{0.04, 0.06, 0.08}),
                Arguments.of("tverdost-kozh", new double[]{0.05, 0.07, 0.09}),
                Arguments.of("legkij-shag", new double[]{0.03, 0.04, 0.05}),
                Arguments.of("bytroe-serdce", new double[]{0.04, 0.07, 0.10}),
                Arguments.of("bystraya-kopka", new double[]{0.05, 0.08, 0.12}),
                Arguments.of("tihaya-postup", new double[]{0.10, 0.15, 0.20}),
                Arguments.of("tyazhelyj-gruz", new double[]{-0.03, -0.05, -0.07}),
                Arguments.of("slabaya-ruka", new double[]{-0.04, -0.06, -0.08}),
                Arguments.of("zamedlennyj-zamah", new double[]{-0.05, -0.08, -0.12}),
                Arguments.of("hrupkaya-oprava", new double[]{-0.04, -0.06, -0.08}),
                Arguments.of("zharkoe-plamya", new double[]{0.15, 0.25, 0.35}),
                Arguments.of("dolgaya-kopka", new double[]{-0.05, -0.08, -0.12}),
                Arguments.of("skolzkij-shag", new double[]{-0.10, -0.15, -0.20}));
    }

    @ParameterizedTest(name = "{0}: уровни I–III")
    @MethodSource("percentages")
    void percentNumbersBecomeFractionsAtAllLevels(String id, double[] expected) {
        ArtifactTraits.Def def = ArtifactTraits.byId(id);
        assertNotNull(def);
        assertTrue(def.kind().percent());
        MemoryAttribute attribute = new MemoryAttribute(20);
        for (int level = 1; level <= 3; level++) {
            ArtifactTraitModifiers.replace(attribute.instance, key(def), def, level);
            AttributeModifier modifier = attribute.modifiers.get(key(def));
            assertEquals(expected[level - 1], modifier.getAmount(), EPSILON);
            assertEquals(AttributeModifier.Operation.MULTIPLY_SCALAR_1, modifier.getOperation());
            assertEquals(1, attribute.modifiers.size(), "смена уровня заменяет, а не накапливает");
        }
    }

    @Test
    void regressionEightPercentDamageIsNotEightHundredPercent() {
        ArtifactTraits.Def grip = ArtifactTraits.byId("silnyj-zahvat");
        MemoryAttribute attribute = new MemoryAttribute(1);
        ArtifactTraitModifiers.replace(attribute.instance, key(grip), grip, 3);
        AttributeModifier modifier = attribute.modifiers.get(key(grip));
        assertEquals(0.08, modifier.getAmount(), EPSILON);
        // Семь единиц урона до этого модификатора: 7 × 1.08, а не 7 × 9.
        assertEquals(7.56, 7 * (1 + modifier.getAmount()), EPSILON);
        assertEquals(1.08, 1 + modifier.getAmount(), EPSILON);
    }

    @Test
    void percentageMatrixCoversEveryPercentageTrait() {
        long catalogCount = ArtifactTraits.all().stream().filter(def -> def.kind().percent()).count();
        assertEquals(13, catalogCount);
        assertEquals(catalogCount, percentages().count());
    }

    @Test
    void absoluteAttributesKeepTheirUnitsAtEveryLevel() {
        int checked = 0;
        for (ArtifactTraits.Def def : ArtifactTraits.all()) {
            if (def.kind().percent() || def.kind() == ArtifactTraits.Kind.POTION) {
                continue;
            }
            MemoryAttribute attribute = new MemoryAttribute(20);
            for (int level = 1; level <= 3; level++) {
                ArtifactTraitModifiers.replace(attribute.instance, key(def), def, level);
                AttributeModifier modifier = attribute.modifiers.get(key(def));
                assertEquals(def.value(level), modifier.getAmount(), EPSILON, def.id());
                assertEquals(AttributeModifier.Operation.ADD_NUMBER, modifier.getOperation(), def.id());
                checked++;
            }
        }
        assertEquals(27, checked, "девять абсолютных свойств × три уровня");
    }

    @Test
    void repeatedPulsesReplaceLegacyAmountAndPreserveOtherPlugins() {
        ArtifactTraits.Def grip = ArtifactTraits.byId("silnyj-zahvat");
        MemoryAttribute attribute = new MemoryAttribute(1);
        AttributeModifier foreign = new AttributeModifier(FOREIGN, 0.20,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        attribute.modifiers.put(FOREIGN, foreign);
        attribute.modifiers.put(key(grip), new AttributeModifier(key(grip), 8,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        for (int pulse = 0; pulse < 100; pulse++) {
            ArtifactTraitModifiers.replace(attribute.instance, key(grip), grip, 3);
            assertEquals(2, attribute.modifiers.size());
            assertEquals(0.08, attribute.modifiers.get(key(grip)).getAmount(), EPSILON);
            assertSame(foreign, attribute.modifiers.get(FOREIGN));
        }
        assertEquals(1, attribute.base, EPSILON, "база игрока не меняется");
    }

    @Test
    void downgradeAndRemovalLeaveNoStaleModifier() {
        ArtifactTraits.Def grip = ArtifactTraits.byId("silnyj-zahvat");
        MemoryAttribute attribute = new MemoryAttribute(1);
        ArtifactTraitModifiers.replace(attribute.instance, key(grip), grip, 3);
        ArtifactTraitModifiers.replace(attribute.instance, key(grip), grip, 1);
        assertEquals(0.04, attribute.modifiers.get(key(grip)).getAmount(), EPSILON);
        ArtifactTraitModifiers.replace(attribute.instance, key(grip), grip, 0);
        assertTrue(attribute.modifiers.isEmpty());
        ArtifactTraitModifiers.replace(attribute.instance, key(grip), grip, -1);
        assertTrue(attribute.modifiers.isEmpty());
    }

    @Test
    void separateGoodAndBadTraitsUseMinecraftMultipliers() {
        ArtifactTraits.Def grip = ArtifactTraits.byId("silnyj-zahvat");
        ArtifactTraits.Def weak = ArtifactTraits.byId("slabaya-ruka");
        MemoryAttribute attribute = new MemoryAttribute(1);
        ArtifactTraitModifiers.replace(attribute.instance, key(grip), grip, 3);
        ArtifactTraitModifiers.replace(attribute.instance, key(weak), weak, 3);
        double multiplier = 1;
        for (AttributeModifier modifier : attribute.modifiers.values()) {
            multiplier *= 1 + modifier.getAmount();
        }
        assertEquals(1.08 * 0.92, multiplier, EPSILON);
        ArtifactTraitModifiers.replace(attribute.instance, key(grip), grip, 0);
        assertEquals(1, attribute.modifiers.size());
        assertEquals(-0.08, attribute.modifiers.get(key(weak)).getAmount(), EPSILON);
    }

    @Test
    void healthPenaltyStillRespectsOneHeartFloor() {
        ArtifactTraits.Def weak = ArtifactTraits.byId("slaboe-serdce");
        for (double base : new double[]{1, 2, 3, 20}) {
            MemoryAttribute attribute = new MemoryAttribute(base);
            ArtifactTraitModifiers.replace(attribute.instance, key(weak), weak, 3);
            AttributeModifier modifier = attribute.modifiers.get(key(weak));
            double amount = modifier == null ? 0 : modifier.getAmount();
            assertEquals(base <= 2 ? 0 : -Math.min(2, base - 2), amount, EPSILON);
            assertEquals(base, attribute.base, EPSILON);
        }
    }

    @Test
    void potionTraitsNeverBecomeAttributeModifiers() {
        ArtifactTraits.Def regeneration = ArtifactTraits.byId("regeneraciya");
        MemoryAttribute attribute = new MemoryAttribute(20);
        ArtifactTraitModifiers.replace(attribute.instance, key(regeneration), regeneration, 3);
        assertTrue(attribute.modifiers.isEmpty());
    }

    /** Узкая подмена только хранилища атрибута; сами модификаторы — из Bukkit API. */
    private static final class MemoryAttribute {
        private final double base;
        private final Map<NamespacedKey, AttributeModifier> modifiers = new LinkedHashMap<>();
        private final AttributeInstance instance;

        private MemoryAttribute(double base) {
            this.base = base;
            this.instance = (AttributeInstance) Proxy.newProxyInstance(
                    AttributeInstance.class.getClassLoader(), new Class<?>[]{AttributeInstance.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getBaseValue" -> this.base;
                        case "getModifier" -> modifiers.get((NamespacedKey) args[0]);
                        case "removeModifier" -> {
                            modifiers.remove((NamespacedKey) args[0]);
                            yield null;
                        }
                        case "addTransientModifier" -> {
                            AttributeModifier modifier = (AttributeModifier) args[0];
                            if (modifiers.putIfAbsent(modifier.getKey(), modifier) != null) {
                                throw new IllegalArgumentException("Повторный ключ модификатора");
                            }
                            yield null;
                        }
                        case "addModifier", "setBaseValue" -> throw new AssertionError(
                                "Нельзя менять базу или сохранять постоянный модификатор");
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
