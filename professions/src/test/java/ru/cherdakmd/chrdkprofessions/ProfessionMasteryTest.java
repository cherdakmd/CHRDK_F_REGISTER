package ru.cherdakmd.chrdkprofessions;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ProfessionMasteryTest {
    @TempDir Path folder;
    private static final UUID PLAYER = UUID.fromString("3e6cd78b-4d1e-4080-a9ed-63773484c438");
    private static final long NOW = 1_800_000_000_000L;
    private static final Logger LOG = Logger.getLogger("ProfessionMasteryTest");

    private ProfessionStore store() { return new ProfessionStore(folder, LOG); }
    private void unlock(ProfessionStore store, Profession profession) {
        store.setActive(PLAYER, profession.id(), NOW);
        store.addXp(PLAYER, profession, ProfessionMath.totalTo(5));
    }

    @Test void everyProfessionHasTwoBranchesAvailableWithoutAPaidRank() {
        var store = store();
        var service = new ProfessionService(ProfessionConfig.defaults(), store);
        assertEquals(16, ProfessionMastery.values().length);
        for (Profession profession : Profession.values()) {
            assertEquals(2, ProfessionMastery.of(profession).size());
            unlock(store, profession);
            assertTrue(service.chooseMastery(PLAYER, ProfessionMastery.of(profession).getFirst().id(), NOW).ok());
            assertEquals(100, service.maxLevel(PLAYER));
        }
        assertNull(ProfessionMastery.parse("not-real"));
        assertNull(ProfessionMastery.parse(null));
        assertEquals(ProfessionMastery.RESTORER, ProfessionMastery.parse(" RESTORER "));
    }

    @Test void levelGateWrongBranchAndCooldownAreCheckedBeforeChanges() {
        var store = store();
        var service = new ProfessionService(ProfessionConfig.defaults(), store);
        assertFalse(service.chooseMastery(PLAYER, "restorer", NOW).ok());
        store.setActive(PLAYER, "smith", NOW);
        assertEquals("mastery-locked", service.chooseMastery(PLAYER, "restorer", NOW).key());
        unlock(store, Profession.SMITH);
        assertEquals("mastery-wrong", service.chooseMastery(PLAYER, "chef", NOW).key());
        assertTrue(service.chooseMastery(PLAYER, "restorer", NOW).ok());
        assertEquals("mastery-same", service.chooseMastery(PLAYER, "restorer", NOW).key());
        assertEquals("mastery-change-cooldown", service.chooseMastery(PLAYER, "field-smith", NOW + 1).key());
        assertEquals(ProfessionMastery.RESTORER, service.mastery(PLAYER));
        assertTrue(service.chooseMastery(PLAYER, "field-smith", NOW + ProfessionMastery.CHANGE_MILLIS).ok());
        assertEquals(5, service.level(PLAYER, Profession.SMITH));
    }

    @Test void switchingProfessionBranchReloadAndResetCannotSilentlyEraseWorkCooldown() {
        var store = store();
        var service = new ProfessionService(ProfessionConfig.defaults(), store);
        unlock(store, Profession.SMITH);
        service.chooseMastery(PLAYER, "restorer", NOW);
        store.setWorkUntil(PLAYER, Profession.SMITH, NOW + 600_000);
        store.setActive(PLAYER, "cook", NOW + 1);
        store.setActive(PLAYER, "smith", NOW + 2);
        assertEquals(600, service.workCooldownLeft(PLAYER, NOW + 2));
        assertEquals(ProfessionMastery.RESTORER, service.mastery(PLAYER));
        store.flush();
        var loaded = store();
        loaded.load();
        var after = new ProfessionService(ProfessionConfig.defaults(), loaded);
        assertEquals(600, after.workCooldownLeft(PLAYER, NOW));
        assertEquals(ProfessionMastery.RESTORER, after.mastery(PLAYER));
        assertEquals(5, after.level(PLAYER, Profession.SMITH));
        assertTrue(loaded.reset(PLAYER));
        assertNull(loaded.mastery(PLAYER, Profession.SMITH));
        assertEquals(0, loaded.workUntil(PLAYER, Profession.SMITH));
    }

    @Test void versionOneDataKeepsXpDailyCountersAndChoice() throws Exception {
        Files.writeString(folder.resolve("professions.json"), """
            {"version":1,"players":{"%s":{"name":"Player","active":"smith","xp":{"smith":840},
            "daily":{"smith":{"date":"2026-10-27","amount":250}},"changed-at":1800000000000}}}
            """.formatted(PLAYER));
        var loaded = store();
        loaded.load();
        assertEquals(840, loaded.xp(PLAYER, Profession.SMITH));
        assertEquals(Profession.SMITH, loaded.active(PLAYER));
        assertNull(loaded.mastery(PLAYER, Profession.SMITH));
        String snapshot = loaded.snapshotJson();
        assertTrue(snapshot.contains("2026-10-27"));
        assertTrue(snapshot.contains("250"));
        assertTrue(snapshot.contains("\"version\": 3"));
    }

    @Test void malformedOrCrossProfessionChoicesAreNotActivated() throws Exception {
        Files.writeString(folder.resolve("professions.json"), """
            {"version":2,"players":{"%s":{"active":"smith","mastery":{"smith":"chef","cook":"missing"},
            "work-until":{"smith":-1,"fake":999},"mastery-changed":{"fake":999}}}}
            """.formatted(PLAYER));
        var loaded = store(); loaded.load();
        assertNull(loaded.mastery(PLAYER, Profession.SMITH));
        assertNull(loaded.mastery(PLAYER, Profession.COOK));
        assertEquals(0, loaded.workUntil(PLAYER, Profession.SMITH));
        assertFalse(loaded.setMastery(null, ProfessionMastery.CHEF, NOW));
    }

    @Test void disabledFeatureAndDisabledPerksRejectSelectionAndWorkEligibility() {
        var store = store(); unlock(store, Profession.SMITH);
        var yaml = new YamlConfiguration();
        yaml.set("mastery.enabled", false);
        var service = new ProfessionService(ProfessionConfig.parse(yaml, LOG), store);
        assertEquals("mastery-disabled", service.checkMastery(PLAYER).key());
        yaml.set("mastery.enabled", true);
        yaml.set("perks.enabled", false);
        service.applyConfig(ProfessionConfig.parse(yaml, LOG));
        assertFalse(service.chooseMastery(PLAYER, "restorer", NOW).ok());
        assertNull(service.mastery(PLAYER));
        assertTrue(ProfessionConfig.parse(new YamlConfiguration(), LOG).masteryEnabled());
    }

    @Test void recipesAreBoundedOneToOneAndHaveNoReverseOrFreeXpPath() {
        assertEquals("COOKED_BEEF", ProfessionWorkRecipes.output(ProfessionMastery.CAMP_COOK, "BEEF"));
        assertEquals("GLASS", ProfessionWorkRecipes.output(ProfessionMastery.KILN_MASTER, "SAND"));
        assertEquals("STONE_BRICKS", ProfessionWorkRecipes.output(ProfessionMastery.FINISHER, "STONE"));
        assertNull(ProfessionWorkRecipes.output(ProfessionMastery.FINISHER, "STONE_BRICKS"));
        assertNull(ProfessionWorkRecipes.output(ProfessionMastery.KILN_MASTER, "DIAMOND_ORE"));
        assertNull(ProfessionWorkRecipes.output(ProfessionMastery.CHEF, "COOKED_BEEF"));
        assertEquals("SPLASH_POTION", ProfessionWorkRecipes.output(ProfessionMastery.GRENADIER, "POTION"));
        assertEquals("LINGERING_POTION", ProfessionWorkRecipes.output(ProfessionMastery.DISTILLER, "SPLASH_POTION"));
        assertNull(ProfessionWorkRecipes.output(ProfessionMastery.DISTILLER, "POTION"));
        for (ProfessionMastery m : ProfessionMastery.values()) {
            assertTrue(m.batch() >= 1 && m.batch() <= 32);
            assertTrue(m.cooldownSeconds() >= 120 && m.cooldownSeconds() <= 600);
        }
        assertEquals(0, ProfessionWorkRecipes.fuel(0));
        assertEquals(1, ProfessionWorkRecipes.fuel(1));
        assertEquals(1, ProfessionWorkRecipes.fuel(8));
        assertEquals(2, ProfessionWorkRecipes.fuel(9));
        assertEquals(4, ProfessionWorkRecipes.fuel(32));
        assertEquals(25, ProfessionWorkRecipes.repair(100, 90, 25));
        assertEquals(3, ProfessionWorkRecipes.repair(100, 3, 25));
        assertEquals(0, ProfessionWorkRecipes.repair(100, 0, 25));
        assertEquals("DIAMOND", ProfessionWorkRecipes.repairMaterial("DIAMOND_PICKAXE"));
        assertEquals("NETHERITE_INGOT", ProfessionWorkRecipes.repairMaterial("NETHERITE_CHESTPLATE"));
        assertNull(ProfessionWorkRecipes.repairMaterial("DIAMOND"));
        assertNull(ProfessionWorkRecipes.repairMaterial("MACE"));
    }

    @Test void everyBranchHasValidLocalizedCardsAndMatchingMaterials() throws Exception {
        var yaml = new YamlConfiguration();
        try (var stream = getClass().getResourceAsStream("/messages.yml")) {
            assertNotNull(stream);
            yaml.load(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
        }
        var section = yaml.getConfigurationSection("professions");
        assertNotNull(section);
        for (ProfessionMastery mastery : ProfessionMastery.values()) {
            for (String key : java.util.List.of(mastery.nameKey(), mastery.descKey(), mastery.costKey())) {
                String text = section.getString(key);
                assertNotNull(text, key);
                assertFalse(text.isBlank(), key);
                assertDoesNotThrow(() -> net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(text));
            }
            assertNotNull(org.bukkit.Material.matchMaterial(mastery.icon()));
        }
    }

    @Test void workshopOnlyCommitsPreparedStorageAndDoesNotAwardOrTouchWorld() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkprofessions/ProfessionWorkshop.java"));
        assertTrue(source.contains("ItemStack[] planned = copy(original)"));
        assertTrue(source.indexOf("Result result = prepare(") < source.indexOf("journal.apply("));
        assertTrue(source.contains("if (!result.ok())"));
        assertTrue(source.contains("Arrays.equals(original, player.getInventory().getStorageContents())"));
        assertFalse(source.contains("setStorageContents("),"Рюкзак изменяет только журнал после записи плана");
        String journal=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkprofessions/ProfessionWorkJournal.java"));
        assertTrue(journal.indexOf("ProfessionDurableFile.write(")<journal.indexOf("return recover(player)"));
        assertTrue(journal.contains("ProfessionWorkRecovery.decide("));
        assertTrue(journal.indexOf("player.saveData()")<journal.indexOf("plugin.store().completeWork("));
        int completed=journal.indexOf("plugin.store().completeWork(");
        assertTrue(completed<journal.indexOf("flushDurably()",completed));
        assertTrue(journal.indexOf("flushDurably()",completed)<journal.indexOf("ProfessionDurableFile.delete(",completed));
        assertTrue(source.contains("!plugin.authenticated(player)"));
        assertTrue(source.contains("potion.hasCustomEffects()"));
        assertFalse(source.contains("award("));
        assertFalse(source.contains("addXp("));
        assertFalse(source.contains("getWorld("));
        assertFalse(source.contains("setBlock"));
    }
}
