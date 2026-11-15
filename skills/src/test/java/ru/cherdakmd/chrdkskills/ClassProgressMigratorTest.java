package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Конвертация классового прогресса в очки общего пула (п.29): кривая класса,
 * потолок очков и идемпотентность — каждый игрок конвертируется ровно один раз.
 */
class ClassProgressMigratorTest {

    private static final Logger LOGGER = Logger.getLogger("chrdk-skills-test");
    private static final String PLAYER = "11111111-2222-3333-4444-555555555555";

    @TempDir
    Path folder;

    @Test
    void classLevelFollowsLegacyCurve() {
        assertEquals(0, ClassProgressMigrator.classLevel(0));
        assertEquals(1, ClassProgressMigrator.classLevel(99));
        assertEquals(2, ClassProgressMigrator.classLevel(100), "100 XP — второй уровень класса");
        assertEquals(2, ClassProgressMigrator.classLevel(247));
        assertEquals(3, ClassProgressMigrator.classLevel(248));
        assertEquals(50, ClassProgressMigrator.classLevel(Long.MAX_VALUE), "потолок — 50 уровней");
    }

    @Test
    void pointsAreCappedAndFollowLevels() {
        assertEquals(0, ClassProgressMigrator.pointsFor(0));
        assertEquals(1, ClassProgressMigrator.pointsFor(248), "три уровня класса — очко за каждые два");
        assertEquals(ClassProgressMigrator.MAX_POINTS,
                ClassProgressMigrator.pointsFor(Long.MAX_VALUE), "конвертация ограничена потолком");
    }

    @Test
    void conversionHappensOnceAndReadsClassesJson() throws Exception {
        Path dataFolder = folder.resolve("plugins");
        Files.createDirectories(dataFolder.resolve("CHRDK_Classes"));
        Files.writeString(dataFolder.resolve("CHRDK_Classes").resolve("classes.json"),
                "{\"version\": 3, \"players\": {\"" + PLAYER + "\": {\"development\": {\"version\": 1, \"xp\": 248}}}}",
                StandardCharsets.UTF_8);
        SkillStore store = new SkillStore(folder.resolve("skills"), LOGGER);
        ClassProgressMigrator.run(store, dataFolder, LOGGER);
        assertEquals(1, store.bonusPoints(PLAYER), "очки пула выданы за уровень класса");
        assertFalse(store.migrateClassPoints(PLAYER, 5), "повторная конвертация закрыта");
        ClassProgressMigrator.run(store, dataFolder, LOGGER);
        assertEquals(1, store.bonusPoints(PLAYER), "повторный запуск не удваивает очки");
    }

    @Test
    void brokenFileIsIgnoredSafely() throws Exception {
        Path dataFolder = folder.resolve("plugins");
        Files.createDirectories(dataFolder.resolve("CHRDK_Classes"));
        Files.writeString(dataFolder.resolve("CHRDK_Classes").resolve("classes.json"),
                "{битый json", StandardCharsets.UTF_8);
        SkillStore store = new SkillStore(folder.resolve("skills"), LOGGER);
        ClassProgressMigrator.run(store, dataFolder, LOGGER);
        assertEquals(0, store.bonusPoints(PLAYER), "битый файл не выдаёт очков");
        assertFalse(store.migrateClassPoints(PLAYER, -1), "отрицательные очки отклоняются");
    }
}
