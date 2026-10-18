package ru.cherdakmd.chrdkartifacts;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactRegistryTest {

    @Test
    void catalogueIsSixtyFiveSplitThirtyThreeThirtyTwo() {
        assertEquals(ArtifactRegistry.EXPECTED_SIZE, ArtifactRegistry.size());
        assertEquals(65, ArtifactRegistry.size(), "30 базовых + 35 по мотивам CHRDK_REBORN");
        assertEquals(ArtifactRegistry.EXPECTED_GOOD,
                ArtifactRegistry.bySide(ArtifactDef.Side.GOOD).size());
        assertEquals(ArtifactRegistry.EXPECTED_CURSED,
                ArtifactRegistry.bySide(ArtifactDef.Side.CURSED).size());
    }

    @Test
    void rebornWaveArtifactsArePresent() {
        // Мифическая тройка из CHRDK_REBORN: феникс/бездна/дракон
        assertNotNull(ArtifactRegistry.byId("pero-feniksa"));
        assertNotNull(ArtifactRegistry.byId("korona-bezdny"));
        assertNotNull(ArtifactRegistry.byId("serdtse-drakona"));
        // Именословы старших тиров: свитки/тотемы/сферы из магазина реборна
        assertNotNull(ArtifactRegistry.byId("svitok-ochischeniya"));
        assertNotNull(ArtifactRegistry.byId("totem-krovi"));
        assertNotNull(ArtifactRegistry.byId("sfera-pobega"));
        // Суперспособности: у каждого пассива-особняка — свой SpecialKind
        for (String id : new String[]{"pero-feniksa", "zhnej-dush", "ledyanaya-aura",
                "ternistyj-plasch", "magnit-opyta", "glinyanaya-dusha"}) {
            assertNotNull(ArtifactEffects.passiveSpecial(id),
                    "суперспособность не привязана к " + id);
        }
    }

    @Test
    void idsAreUniqueMaterialsDoNotRepeatAndDefaultsAreSane() {
        Set<String> ids = new HashSet<>();
        Map<org.bukkit.Material, Integer> materials = new HashMap<>();
        for (ArtifactDef def : ArtifactRegistry.all()) {
            assertTrue(ids.add(def.id()), "дубль ид: " + def.id());
            materials.merge(def.material(), 1, Integer::sum);
            assertTrue(def.cooldownSeconds() >= 0 && def.cooldownSeconds() <= 86_400);
            assertTrue(def.xpCostLevels() >= 0 && def.xpCostLevels() <= 100);
            if (def.mode() == ArtifactDef.Mode.ACTIVE) {
                assertTrue(def.cooldownSeconds() > 0,
                        "активный " + def.id() + " должен иметь кулдаун по умолчанию");
            }
        }
        // Материалы уникальны: один и тот же ванильный предмет не может быть двумя артефактами.
        for (var entry : materials.entrySet()) {
            assertEquals(1, entry.getValue(), "материал " + entry.getKey() + " встречается дважды");
        }
    }

    @Test
    void everyArtifactHasEffectBinding() {
        for (ArtifactDef def : ArtifactRegistry.all()) {
            assertTrue(ArtifactEffects.isComplete(def), "нет эффекта для " + def.id());
        }
        // Контроль количества скриптов: активных артефактов ровно столько, сколько видов эффектов.
        long actives = ArtifactRegistry.all().stream()
                .filter(a -> a.mode() == ArtifactDef.Mode.ACTIVE).count();
        assertEquals(ArtifactEffects.ActiveKind.values().length, actives,
                "каждый вид активного эффекта должен иметь ровно один артефакт");
    }

    @Test
    void jumpWardHasNoPotionHack() {
        // «Глиняная душа» больше не носит jump_boost с переполненным амплификатором:
        // прыжок гасится атрибутом jump_strength в PassiveEffectsService.
        assertEquals(ArtifactEffects.SpecialKind.JUMP_WARD,
                ArtifactEffects.passiveSpecial("glinyanaya-dusha"));
        assertTrue(ArtifactEffects.passivePotions("glinyanaya-dusha").isEmpty(),
                "у JUMP_WARD не должно быть зельевых эффектов");
        for (var spec : ArtifactEffects.passivePotions("sapogi-vetra")) {
            assertTrue(spec.amplifier() <= 100,
                    "амплификатор " + spec.type() + " выглядит как переполнение");
        }
    }

    @Test
    void everyPotionAmplifierIsSane() {
        for (ArtifactDef def : ArtifactRegistry.all()) {
            for (var spec : ArtifactEffects.passivePotions(def.id())) {
                assertTrue(spec.amplifier() >= 0 && spec.amplifier() <= 100,
                        def.id() + ": амплификатор " + spec.amplifier() + " вне 0..100");
                assertTrue(Potions.isKnown(spec.type()),
                        def.id() + ": неизвестный эффект " + spec.type());
            }
        }
    }

    @Test
    void lookupIsCaseAndWhitespaceTolerant() {
        assertNotNull(ArtifactRegistry.byId("Koren-Zhizni"));
        assertNotNull(ArtifactRegistry.byId("  kaplya-zhizni  "));
        assertNull(ArtifactRegistry.byId("no-such-artifact"));
    }

    @Test
    void messagesYmlCoversEveryArtifactNameAndLore() throws Exception {
        try (var in = getClass().getResourceAsStream("/messages.yml")) {
            assertNotNull(in, "messages.yml должен быть в ресурсах");
            String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            for (ArtifactDef def : ArtifactRegistry.all()) {
                assertTrue(text.contains("\n    " + def.id() + ":"),
                        "в messages.yml нет секции art." + def.id());
            }
        }
    }
}
