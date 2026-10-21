package ru.cherdakmd.chrdkartifacts;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Сериализация свойств артефактов в PDC: формат «id:level;…», устойчивость
 * к мусору и стабильный вывод.
 */
class ArtifactTraitCodecTest {

    @Test
    void roundTripKeepsValues() {
        Map<String, Integer> source = new LinkedHashMap<>();
        source.put("silnyj-zahvat", 2);
        source.put("tyazhelyj-gruz", 3);
        String encoded = ArtifactTraitCodec.encode(source);
        assertEquals("silnyj-zahvat:2;tyazhelyj-gruz:3", encoded);
        assertEquals(source, ArtifactTraitCodec.decode(encoded));
    }

    @Test
    void encodeSortsIdsAndDropsEmpty() {
        Map<String, Integer> source = new LinkedHashMap<>();
        source.put("vechnyj-golod", 1);
        source.put("silnyj-zahvat", 2);
        source.put("", 3);
        source.put("tormoz", 0);
        assertEquals("silnyj-zahvat:2;vechnyj-golod:1", ArtifactTraitCodec.encode(source));
        assertEquals("", ArtifactTraitCodec.encode(Map.of()));
        assertEquals("", ArtifactTraitCodec.encode(null));
    }

    @Test
    void decodeIgnoresGarbage() {
        assertTrue(ArtifactTraitCodec.decode(null).isEmpty());
        assertTrue(ArtifactTraitCodec.decode("").isEmpty());
        assertTrue(ArtifactTraitCodec.decode("  ").isEmpty());
        Map<String, Integer> decoded = ArtifactTraitCodec.decode(
                "broken;:2;silnyj-zahvat:;tormoz:0;slabost:9;tihaya-postup:2;tihaya-postup:3");
        assertEquals(Map.of("tihaya-postup", 3), decoded,
                "мусор отброшен, уровень взят максимальный");
    }

    @Test
    void encodeRollListUsesMaxLevel() {
        List<ArtifactTraits.Roll> rolls = List.of(
                new ArtifactTraits.Roll("slabost", 1),
                new ArtifactTraits.Roll("slabost", 2),
                new ArtifactTraits.Roll("tormoz", 3));
        assertEquals("slabost:2;tormoz:3", ArtifactTraitCodec.encode(rolls));
        assertEquals("", ArtifactTraitCodec.encode(List.of()));
    }

    @Test
    void romanCoversLevelsOneToThree() {
        assertEquals("I", ArtifactTraitCodec.roman(1));
        assertEquals("II", ArtifactTraitCodec.roman(2));
        assertEquals("III", ArtifactTraitCodec.roman(3));
        assertEquals("I", ArtifactTraitCodec.roman(0), "уровень ниже I поднимается до I");
        assertEquals("V", ArtifactTraitCodec.roman(10), "потолок римских цифр — V");
    }
}
