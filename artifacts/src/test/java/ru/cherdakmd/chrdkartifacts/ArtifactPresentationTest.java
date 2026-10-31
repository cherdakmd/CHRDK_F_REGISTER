package ru.cherdakmd.chrdkartifacts;

import net.kyori.adventure.text.Component;
import ru.cherdakmd.chrdkregister.Messages;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactPresentationTest {
    @Test
    void exactValuesFollowCatalogWithoutLocaleRounding() {
        assertEquals("+4", ArtifactTraitText.signedValue(ArtifactTraits.byId("silnyj-zahvat"), 1));
        assertEquals("+6", ArtifactTraitText.signedValue(ArtifactTraits.byId("silnyj-zahvat"), 2));
        assertEquals("+8", ArtifactTraitText.signedValue(ArtifactTraits.byId("silnyj-zahvat"), 3));
        assertEquals("−8", ArtifactTraitText.signedValue(ArtifactTraits.byId("slabaya-ruka"), 3));
        assertEquals("+0.08", ArtifactTraitText.signedValue(ArtifactTraits.byId("ustojchivost"), 2));
        assertEquals("−0.35", ArtifactTraitText.signedValue(ArtifactTraits.byId("korotkaya-ruka"), 2));
        assertEquals("+1.5", ArtifactTraitText.signedValue(ArtifactTraits.byId("myagkoe-padenie"), 3));
        assertEquals("−1.5", ArtifactTraitText.signedValue(ArtifactTraits.byId("vysokoe-padenie"), 3));
    }

    @Test
    void everyTraitHasExactTemplateAndLegacyDescription() throws Exception {
        try (var input = getClass().getResourceAsStream("/messages.yml")) {
            assertNotNull(input);
            var messages = Messages.fromChecked(input, null, Logger.getAnonymousLogger());
            for (ArtifactTraits.Def def : ArtifactTraits.all()) {
                String key = "trait." + def.id();
                assertTrue(messages.has(key + ".detail"), def.id());
                assertTrue(messages.has(key + ".desc"), def.id());
                String detail = messages.raw(key + ".detail");
                String placeholder = def.kind() == ArtifactTraits.Kind.POTION ? "%effectlevel%" : "%value%";
                assertTrue(detail.contains(placeholder), def.id());
                for (int level = 1; level <= 3; level++) {
                    String rendered = messages.plain(key + ".detail",
                            "value", ArtifactTraitText.signedValue(def, level),
                            "effectlevel", ArtifactTraitText.effectLevel(def, level));
                    assertFalse(rendered.contains("%value%"), def.id());
                    assertFalse(rendered.contains("%effectlevel%"), def.id());
                    if (def.kind().percent()) assertTrue(rendered.contains("%"), def.id());
                }
            }
        }
    }

    @Test
    void traitRankAndPotionAmplifierAreNotConfused() {
        var regeneration = ArtifactTraits.byId("regeneraciya");
        assertEquals("I", ArtifactTraitText.effectLevel(regeneration, 1));
        assertEquals("I", ArtifactTraitText.effectLevel(regeneration, 2));
        assertEquals("II", ArtifactTraitText.effectLevel(regeneration, 3));
        assertEquals("I", ArtifactTraitText.effectLevel(ArtifactTraits.byId("noschnoe-zrenie"), 3));
    }

    @Test
    void migrationReplacesOnlyKnownLinesAndIsIdempotent() {
        Component name = Component.text("Описание артефакта");
        Component old = Component.text("▲ Сильный захват III — +4–8% к урону");
        Component exact = Component.text("▲ Сильный захват III — +8% к атрибуту урона");
        Component tier = Component.text("✧ Свойство тира");
        Component custom = Component.text("▲ Собственный текст администратора");
        List<Component> before = List.of(name, old, tier, custom);
        var replacements = Map.of(old, exact);
        List<Component> after = ArtifactLore.replaceKnown(before, replacements);
        assertEquals(List.of(name, exact, tier, custom), after);
        assertEquals(List.of(name, old, tier, custom), before);
        assertEquals(after, ArtifactLore.replaceKnown(after, replacements));
        assertSame(custom, after.get(3));
    }

    @Test
    void equivalentComponentTreeFromSerializationStillMatches() {
        Component before = Component.text("Сильный захват III — +4–8%");
        Component stored = Component.empty().append(Component.text("Сильный захват III"))
                .append(Component.text(" — +4–8%"));
        Component exact = Component.text("Сильный захват III — +8%");
        assertEquals(List.of(exact), ArtifactLore.replaceKnown(List.of(stored), Map.of(before, exact)));
    }

    @Test
    void missingOrCustomLoreIsNotRebuiltOrAppended() {
        assertNull(ArtifactLore.replaceKnown(null, Map.of()));
        assertEquals(List.of(), ArtifactLore.replaceKnown(List.of(), Map.of()));
        var custom = List.of(Component.text("Только мои строки"));
        assertEquals(custom, ArtifactLore.replaceKnown(custom,
                Map.of(Component.text("Неизвестная строка"), Component.text("Новая"))));
    }
}
