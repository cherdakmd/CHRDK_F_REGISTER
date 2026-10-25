package ru.cherdakmd.chrdkclasses;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Правила классов (1.4.164): выбор, очки, требования талантов и потолки.
 *
 * <p>Проверяется без сервера: уровни навыков приходят из заглушки, поэтому
 * видно, как класс реагирует на прокачку — и что очки нельзя получить дважды
 * или потратить сверх выданных.</p>
 */
class TalentServiceTest {

    private static final Logger LOGGER = Logger.getLogger("TalentServiceTest");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final long NOW = 1_792_000_000_000L;

    /** Заглушка навыков: уровни по иду и сумма. */
    private static final class Skills implements SkillSource {
        private final Map<String, Integer> levels = new HashMap<>();
        private int base;
        private int total;

        Skills put(String skill, int level) {
            levels.put(skill, level);
            return this;
        }

        Skills all(int level) {
            base = level;
            return this;
        }

        Skills total(int value) {
            total = value;
            return this;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public int level(UUID player, String skillId) {
            return levels.getOrDefault(skillId, base);
        }

        @Override
        public int totalLevel(UUID player) {
            return total;
        }
    }

    private static ClassConfig config(String yaml) {
        YamlConfiguration source = new YamlConfiguration();
        try {
            source.loadFromString(yaml);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        return ClassConfig.parse(source, LOGGER);
    }

    private static ClassConfig freeConfig() {
        return config("""
                general:
                  change-cooldown-days: 0
                  respec-cooldown-hours: 0
                """);
    }

    private static ClassStore store(Path dir) {
        ClassStore store = new ClassStore(dir, LOGGER);
        store.load();
        return store;
    }

    /* ---------------------------------- выбор --------------------------------- */

    @Test
    void chooseRecordsClassAndBlocksRepeat(@TempDir Path dir) {
        Skills skills = new Skills();
        TalentService service = new TalentService(ClassConfig.defaults(), store(dir), skills);
        assertEquals("class-unknown", service.choose(PLAYER, "паладин", NOW).key());
        assertEquals("class-chosen", service.choose(PLAYER, "warrior", NOW).key());
        assertEquals(PlayerClass.WARRIOR, service.classOf(PLAYER));
        assertEquals("class-same", service.choose(PLAYER, "warrior", NOW).key());
        assertEquals("class-cooldown", service.checkChoose(PLAYER, "mage", NOW + 1_000).key());
        assertTrue(service.changeCooldownLeft(PLAYER, NOW) > 0);
        assertEquals(0L, service.changeCooldownLeft(PLAYER, NOW + 8L * 86_400_000L));
    }

    @Test
    void changeWipesTalentsAndMentionsThem(@TempDir Path dir) {
        Skills skills = new Skills().put("defense", 10);
        TalentService service = new TalentService(freeConfig(), store(dir), skills);
        service.choose(PLAYER, "warrior", NOW);
        assertTrue(service.learn(PLAYER, "warrior-stoyka-1").ok());
        assertEquals(1, service.store().talentCount(PLAYER));

        ClassResult changed = service.choose(PLAYER, "mage", NOW + 1_000);
        assertTrue(changed.ok(), changed.key());
        assertEquals("class-changed", changed.key());
        assertEquals("1", changed.args().get(3), "в тексте видно, сколько талантов сгорело");
        assertEquals(0, service.store().talentCount(PLAYER));
        assertEquals(PlayerClass.MAGE, service.classOf(PLAYER));
    }

    @Test
    void disabledConfigBlocksEverything(@TempDir Path dir) {
        TalentService service = new TalentService(config("""
                general:
                  enabled: false
                """), store(dir), new Skills());
        assertEquals("disabled", service.choose(PLAYER, "warrior", NOW).key());
        assertEquals("disabled", service.learn(PLAYER, "warrior-stoyka-1").key());
        assertEquals("disabled", service.respec(PLAYER, NOW).key());
    }

    /* ---------------------------------- очки ---------------------------------- */

    @Test
    void pointsFollowSkillLevels(@TempDir Path dir) {
        Skills skills = new Skills().total(0);
        TalentService service = new TalentService(freeConfig(), store(dir), skills);
        service.choose(PLAYER, "warrior", NOW);
        assertEquals(1, service.level(PLAYER));
        assertEquals(1, service.earnedPoints(PLAYER));
        assertEquals(1, service.availablePoints(PLAYER));
        assertEquals(30, ClassMath.nextLevelIn(0, 30));

        skills.total(300);
        assertEquals(11, service.level(PLAYER));
        assertEquals(11, service.earnedPoints(PLAYER));
        assertEquals(11, service.availablePoints(PLAYER));
    }

    @Test
    void learningSpendsPointsOnce(@TempDir Path dir) {
        Skills skills = new Skills().total(0).put("defense", 10).put("combat", 10);
        TalentService service = new TalentService(freeConfig(), store(dir), skills);
        service.choose(PLAYER, "warrior", NOW);

        ClassResult learned = service.learn(PLAYER, "warrior-stoyka-1");
        assertTrue(learned.ok(), learned.key());
        assertEquals("talent-learned", learned.key());
        assertEquals(0, service.availablePoints(PLAYER));
        assertEquals(1, service.spentPoints(PLAYER));
        assertEquals("talent-known", service.learn(PLAYER, "warrior-stoyka-1").key());
        assertEquals("talent-points", service.learn(PLAYER, "warrior-natisk-1").key());
    }

    @Test
    void chainAndSkillRequirementsAreChecked(@TempDir Path dir) {
        Skills skills = new Skills().total(0).put("combat", 5).put("defense", 0);
        TalentService service = new TalentService(freeConfig(), store(dir), skills);
        service.choose(PLAYER, "warrior", NOW);

        assertEquals("talent-locked", service.learn(PLAYER, "warrior-stoyka-2").key());
        assertEquals("talent-skill", service.learn(PLAYER, "warrior-stoyka-1").key());

        skills.put("defense", 10);
        assertTrue(service.learn(PLAYER, "warrior-stoyka-1").ok());
        skills.total(300);
        assertEquals("talent-skill", service.learn(PLAYER, "warrior-stoyka-2").key(),
                "второму ярусу нужен 20-й уровень обороны");

        skills.put("defense", 20);
        ClassResult second = service.learn(PLAYER, "warrior-stoyka-2");
        assertTrue(second.ok(), second.key());
        assertEquals("talent-skill", service.learn(PLAYER, "warrior-stoyka-3").key(),
                "третий ярус требует 35 уровней обороны");
    }

    @Test
    void foreignAndUnknownTalentsAreRejected(@TempDir Path dir) {
        Skills skills = new Skills().total(300);
        TalentService service = new TalentService(freeConfig(), store(dir), skills);
        assertEquals("class-required", service.learn(PLAYER, "warrior-stoyka-1").key());
        service.choose(PLAYER, "warrior", NOW);
        assertEquals("talent-unknown", service.learn(PLAYER, "нет-такого").key());
        assertEquals("talent-foreign", service.learn(PLAYER, "mage-chary-1").key());
    }

    /* --------------------------------- эффекты -------------------------------- */

    @Test
    void effectsAddUpAndRespectCaps(@TempDir Path dir) {
        Skills skills = new Skills().all(60).total(1_000);
        TalentService service = new TalentService(freeConfig(), store(dir), skills);
        service.choose(PLAYER, "cleric", NOW);

        assertEquals(2.0, service.effect(PLAYER, EffectKind.MAX_HEALTH), "пассив жреца");
        assertEquals(1.0, service.effect(PLAYER, EffectKind.REGEN));
        for (Talent talent : ClassCatalog.talents("cleric")) {
            ClassResult result = service.learn(PLAYER, talent.id());
            assertTrue(result.ok(), talent.id() + ": " + result.key());
        }
        assertEquals(ClassCatalog.TALENTS_PER_CLASS, service.store().talentCount(PLAYER));

        Map<EffectKind, Double> expected = new EnumMap<>(EffectKind.class);
        ClassCatalog.passive("cleric").forEach((kind, value) -> expected.merge(kind, value, Double::sum));
        for (Talent talent : ClassCatalog.talents("cleric")) {
            expected.merge(talent.kind(), talent.value(), Double::sum);
        }
        Map<EffectKind, Double> capped = new EnumMap<>(EffectKind.class);
        expected.forEach((kind, value) -> {
            double total = ClassMath.total(kind, value);
            if (total > 0.0) {
                capped.put(kind, total);
            }
        });
        assertEquals(capped, service.effects(PLAYER), "сумма дерева и пассива с потолками");
        assertEquals(EffectKind.MAX_HEALTH.cap(), service.effect(PLAYER, EffectKind.MAX_HEALTH),
                "всё дерево жреца выводит здоровье в потолок");
        assertEquals(EffectKind.REGEN.cap(), service.effect(PLAYER, EffectKind.REGEN),
                "и лечение тоже — но не выше");
        assertTrue(service.effect(PLAYER, EffectKind.SATURATION) > 0.0);
    }

    @Test
    void noClassMeansNoEffects(@TempDir Path dir) {
        TalentService service = new TalentService(freeConfig(), store(dir), new Skills());
        assertTrue(service.effects(PLAYER).isEmpty());
        assertEquals(0.0, service.effect(PLAYER, EffectKind.ARMOR));
        assertEquals(0.0, service.effect(PLAYER, null));
    }

    /* -------------------------------- пересборка ------------------------------- */

    @Test
    void respecReturnsPointsAndStartsCooldown(@TempDir Path dir) {
        Skills skills = new Skills().total(300).put("defense", 10);
        TalentService service = new TalentService(config("""
                general:
                  respec-cooldown-hours: 2
                """), store(dir), skills);
        service.choose(PLAYER, "warrior", NOW);
        assertEquals("respec-empty", service.respec(PLAYER, NOW).key());
        assertTrue(service.learn(PLAYER, "warrior-stoyka-1").ok());
        int before = service.availablePoints(PLAYER);

        ClassResult result = service.respec(PLAYER, NOW + 1_000);
        assertTrue(result.ok(), result.key());
        assertEquals("1", result.args().get(1));
        assertEquals(before + 1, service.availablePoints(PLAYER));
        assertTrue(service.learn(PLAYER, "warrior-stoyka-1").ok(), "очки снова доступны");
        assertEquals("respec-cooldown", service.respec(PLAYER, NOW + 2_000).key());
        assertTrue(service.respecCooldownLeft(PLAYER, NOW + 2_000) > 0);
    }

    /* -------------------------------- служебное -------------------------------- */

    @Test
    void adminSetIgnoresCooldownAndResetClears(@TempDir Path dir) {
        Skills skills = new Skills().total(300).put("archery", 10);
        TalentService service = new TalentService(ClassConfig.defaults(), store(dir), skills);
        service.choose(PLAYER, "warrior", NOW);
        ClassResult set = service.setByAdmin(PLAYER, "archer", NOW + 1_000);
        assertTrue(set.ok(), set.key());
        assertEquals("admin-class-set", set.key());
        assertEquals(PlayerClass.ARCHER, service.classOf(PLAYER));
        assertTrue(service.learn(PLAYER, "archer-metkost-1").ok());

        assertEquals("admin-reset", service.resetByAdmin(PLAYER).key());
        assertNull(service.classOf(PLAYER));
        assertEquals(0, service.store().talentCount(PLAYER));
        assertEquals("admin-reset-none", service.resetByAdmin(OTHER).key());
        assertEquals("class-unknown", service.setByAdmin(PLAYER, "паладин", NOW).key());
    }

    @Test
    void skillsAbsenceIsSafe(@TempDir Path dir) {
        TalentService service = new TalentService(freeConfig(), store(dir), null);
        service.choose(PLAYER, "mage", NOW);
        assertEquals(0, service.totalSkill(PLAYER));
        assertEquals(1, service.level(PLAYER));
        assertEquals(1, service.availablePoints(PLAYER));
        assertEquals(0, service.skillLevel(PLAYER, "enchanting"));
        assertNotNull(service.effects(PLAYER));
        assertFalse(service.effects(PLAYER).isEmpty(), "пассив класса работает без навыков");
    }
}
