package ru.cherdakmd.chrdkartifacts;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Симметричная проверка таблиц эффектов и каталога (аудит A-20).
 *
 * <p>До 1.4.130 тесты проверяли только «у каждого пассива-особняка есть
 * суперспособность». Обратная сторона — запись в таблице без артефакта в
 * реестре — не ловилась: опечатка в иде тихо оставляла артефакт без эффекта,
 * а таблицу — с «сиротой». Здесь проверяются обе стороны и ровно один
 * источник эффекта на артефакт.</p>
 */
class ArtifactEffectsCoverageTest {

    @Test
    void everyRegistryEntryHasExactlyOneEffectSource() {
        for (ArtifactDef def : ArtifactRegistry.all()) {
            assertTrue(ArtifactEffects.isComplete(def),
                    "у артефакта нет эффекта: " + def.id());
            if (def.mode() == ArtifactDef.Mode.PASSIVE) {
                int sources = (ArtifactEffects.passivePotions(def.id()).isEmpty() ? 0 : 1)
                        + (ArtifactEffects.passiveTrigger(def.id()) == null ? 0 : 1)
                        + (ArtifactEffects.passiveSpecial(def.id()) == null ? 0 : 1);
                assertEquals(1, sources, "у пассива должен быть ровно один источник: "
                        + def.id());
                assertNull(ArtifactEffects.activeKind(def.id()),
                        "пассив не должен иметь активный скрипт: " + def.id());
            } else {
                assertNotNull(ArtifactEffects.activeKind(def.id()),
                        "у активного артефакта нет скрипта: " + def.id());
                assertTrue(ArtifactEffects.passivePotions(def.id()).isEmpty(),
                        "активный артефакт не должен вешать пассивные зелья: " + def.id());
                assertNull(ArtifactEffects.passiveTrigger(def.id()),
                        "активный артефакт не должен иметь проклятый триггер: " + def.id());
                assertNull(ArtifactEffects.passiveSpecial(def.id()),
                        "активный артефакт не должен иметь пассивную суперспособность: "
                                + def.id());
            }
        }
    }

    @Test
    void effectTablesHaveNoOrphans() {
        Set<String> registryIds = new HashSet<>();
        for (ArtifactDef def : ArtifactRegistry.all()) {
            registryIds.add(def.id());
        }
        for (String id : ArtifactEffects.effectIds()) {
            assertTrue(registryIds.contains(id),
                    "эффект объявлен для ида вне каталога: " + id);
        }
        assertEquals(registryIds.size(), ArtifactEffects.effectIds().size(),
                "каждый артефакт каталога должен встречаться в таблицах эффектов");
    }

    @Test
    void potionSpecsAndTriggersAreSane() {
        for (ArtifactDef def : ArtifactRegistry.all()) {
            for (ArtifactEffects.PotionSpec spec : ArtifactEffects.passivePotions(def.id())) {
                assertNotNull(spec.type(), "пустой тип зелья у " + def.id());
                assertTrue(!spec.type().isBlank(), "пустой тип зелья у " + def.id());
                assertTrue(spec.amplifier() >= 0 && spec.amplifier() <= 9,
                        "амплификатор вне 0..9 у " + def.id());
            }
            ArtifactEffects.TriggerSpec trigger = ArtifactEffects.passiveTrigger(def.id());
            if (trigger != null) {
                assertTrue(trigger.chancePerCycle() > 0.0 && trigger.chancePerCycle() <= 1.0,
                        "шанс триггера должен быть в (0..1]: " + def.id());
            }
        }
    }

    @Test
    void activeArtifactsHavePositiveCooldowns() {
        for (ArtifactDef def : ArtifactRegistry.bySide(ArtifactDef.Side.GOOD)) {
            if (def.mode() == ArtifactDef.Mode.ACTIVE) {
                assertTrue(def.cooldownSeconds() > 0,
                        "активный артефакт без кулдауна: " + def.id());
            }
        }
        for (ArtifactDef def : ArtifactRegistry.bySide(ArtifactDef.Side.CURSED)) {
            if (def.mode() == ArtifactDef.Mode.ACTIVE) {
                assertTrue(def.cooldownSeconds() > 0,
                        "активный артефакт без кулдауна: " + def.id());
            }
        }
    }
}
