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
    void catalogueIsThirtySplitEvenly() {
        assertEquals(ArtifactRegistry.EXPECTED_SIZE, ArtifactRegistry.size());
        assertEquals(ArtifactRegistry.EXPECTED_GOOD,
                ArtifactRegistry.bySide(ArtifactDef.Side.GOOD).size());
        assertEquals(ArtifactRegistry.EXPECTED_CURSED,
                ArtifactRegistry.bySide(ArtifactDef.Side.CURSED).size());
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
