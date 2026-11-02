package ru.cherdakmd.chrdkartifacts;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.Messages;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class ArtifactGuideTest {
    @TempDir Path temp;
    private final Logger logger = Logger.getAnonymousLogger();
    private Messages messages() throws Exception {
        return Messages.fromChecked(getClass().getResourceAsStream("/messages.yml"), null, logger);
    }
    @Test void everyArtifactHasReadableDetailsAndNoUnresolvedSlots() throws Exception {
        var messages = messages();
        assertEquals(65, ArtifactRegistry.all().size());
        for (var def : ArtifactRegistry.all()) {
            var lines = messages.getList("art." + def.id() + ".details");
            assertTrue(lines.size() >= 2, def.id());
            for (var line : lines) {
                String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line);
                assertFalse(plain.isBlank(), def.id());
                assertFalse(plain.contains("%" + "id%"), def.id());
                assertFalse(plain.contains("<gray>"), def.id());
            }
        }
        assertFalse(messages.plain("art.pero-feniksa.lore").contains("Не выпадает"));
    }
    @Test void finiteTopicsNeverAcceptMessagePathsOrMarkup() throws Exception {
        var messages = messages();
        assertEquals(4, ArtifactGuide.TOPICS.size());
        for (String topic : ArtifactGuide.TOPICS) {
            assertTrue(messages.getList(ArtifactGuide.topic(topic)).size() >= 3, topic);
            assertEquals(ArtifactGuide.topic(topic), ArtifactGuide.topic(topic.toUpperCase(Locale.ROOT)));
        }
        assertNull(ArtifactGuide.topic(null));
        assertNull(ArtifactGuide.topic("../art.solnechny-obereg.details"));
        assertNull(ArtifactGuide.topic("<click:run_command:/op>"));
        assertNull(ArtifactGuide.topic("sources.extra"));
    }
    @Test void sourceReportUsesActualClampedRatesAndSkipsUnknownEntityTypes() throws Exception {
        var raw = new YamlConfiguration();
        raw.loadFromString("""
                sources:
                  chests:
                    minecraft:chests/ancient_city: 1.75
                    shipwreck_treasure: 150
                    mineshaft: -3
                  mobs:
                    ZOMBIE: 7.5
                    NOT_A_REAL_MOB: 90
                """);
        var config = ArtifactsConfig.parse(raw, logger);
        var rows = ArtifactSourceReport.chests(raw, config);
        assertEquals(3, rows.size());
        assertEquals(1.75, rows.stream().filter(row -> row.key().contains("ancient_city")).findFirst().orElseThrow().chance());
        assertEquals(100.0, rows.stream().filter(row -> row.key().equals("shipwreck_treasure")).findFirst().orElseThrow().chance());
        assertEquals(0.0, rows.stream().filter(row -> row.key().equals("mineshaft")).findFirst().orElseThrow().chance());
        assertEquals(List.of(new ArtifactSourceReport.Row("ZOMBIE", 7.5)), ArtifactSourceReport.mobs(raw, config));
        assertThrows(UnsupportedOperationException.class, () -> rows.clear());
        assertTrue(ArtifactSourceReport.chests(new YamlConfiguration(), config).isEmpty());
    }
    @Test void percentagesDoNotDependOnProcessLocale() {
        var previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ru-RU"));
            assertEquals("1.75", ArtifactSourceReport.percent(1.75));
            assertEquals("0.00", ArtifactSourceReport.percent(0));
            assertEquals("—", ArtifactSourceReport.percent(Double.NaN));
        } finally { Locale.setDefault(previous); }
    }
    @Test void legacyStockMigratesButCustomAndEmptyOverridesRemainAndFileIsNotRewritten() throws Exception {
        Path live = temp.resolve("messages.yml");
        byte[] stock;
        try (var old = getClass().getResourceAsStream("/artifacts-messages-legacy-1.4.282-rc7.yml")) {
            assertNotNull(old); stock = old.readAllBytes();
        }
        Files.write(live, stock);
        var fresh = messages();
        var upgraded = Messages.fromChecked(getClass().getResourceAsStream("/messages.yml"), live,
                getClass().getResourceAsStream("/artifacts-messages-legacy-1.4.282-rc7.yml"), logger);
        assertEquals(fresh.plain("card-source"), upgraded.plain("card-source"));
        assertEquals(fresh.plain("art.pero-feniksa.lore"), upgraded.plain("art.pero-feniksa.lore"));
        assertEquals(fresh.getList("art.pero-feniksa.details"), upgraded.getList("art.pero-feniksa.details"));
        assertArrayEquals(stock, Files.readAllBytes(live));
        Files.writeString(live, """
                messages:
                  card-source: ''
                  art:
                    pero-feniksa:
                      lore: 'Свой текст владельца'
                      details: []
                """);
        var custom = Messages.fromChecked(getClass().getResourceAsStream("/messages.yml"), live,
                getClass().getResourceAsStream("/artifacts-messages-legacy-1.4.282-rc7.yml"), logger);
        assertEquals("", custom.plain("card-source"));
        assertEquals("Свой текст владельца", custom.plain("art.pero-feniksa.lore"));
        assertTrue(custom.getList("art.pero-feniksa.details").isEmpty());
    }
    @Test void readingRemainingTimeDoesNotChangeStoredCooldownsOrDirtyFlag() {
        var cooldowns = new Cooldowns();
        var player = java.util.UUID.randomUUID();
        cooldowns.start(player, "grozovoj-kolokol", 1000, 5);
        cooldowns.markClean();
        var before = cooldowns.snapshot();
        assertEquals(5, cooldowns.remainingSeconds(player, "grozovoj-kolokol", 1001));
        assertEquals(0, cooldowns.remainingSeconds(player, "grozovoj-kolokol", 6001));
        assertEquals(before, cooldowns.snapshot());
        assertFalse(cooldowns.isDirty());
    }
    @Test void triggerProbabilitiesAreDocumentedPerCycleAndMatchActualTable() throws Exception {
        var messages = messages();
        int count = 0;
        for (var def : ArtifactRegistry.all()) {
            var trigger = ArtifactEffects.passiveTrigger(def.id());
            if (trigger == null) continue;
            String lines = messages.getList("art." + def.id() + ".details").toString();
            assertTrue(lines.contains(Math.round(trigger.chancePerCycle() * 100) + "%"), def.id());
            assertTrue(lines.contains("цикл"), def.id());
            count++;
        }
        assertEquals(7, count);
    }
    @Test void potionRanksFollowZeroBasedAmplifiersIncludingTwoEffectArtifacts() throws Exception {
        var messages = messages();
        int count = 0;
        for (var def : ArtifactRegistry.all()) {
            var effects = ArtifactEffects.passivePotions(def.id());
            if (effects.isEmpty()) continue;
            String first = messages.getList("art." + def.id() + ".details").getFirst().toString();
            for (var effect : effects) assertTrue(first.contains(ArtifactTraitCodec.roman(effect.amplifier() + 1)), def.id());
            count++;
        }
        assertEquals(22, count);
        assertTrue(messages.getList("art.solnechny-obereg.details").getFirst().toString().contains("Удача I"));
        assertTrue(messages.getList("art.serdtse-drakona.details").getFirst().toString().contains("V"));
    }
    @Test void collectionAndActivationInstructionsDoNotPromiseFreeCopies() throws Exception {
        var messages = messages();
        String collection = messages.getList("guide-collection").toString();
        assertTrue(collection.contains("/bal"));
        assertTrue(collection.contains("бесплатную копию"));
        String use = messages.getList("guide-use").toString();
        assertTrue(use.contains("уровни XP"));
        assertTrue(use.contains("отменённый телепорт"));
        assertTrue(messages.plain("sources-fishing", "chance", "3.00").contains("не среди всех забросов"));
    }
}
