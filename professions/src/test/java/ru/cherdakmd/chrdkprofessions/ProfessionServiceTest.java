package ru.cherdakmd.chrdkprofessions;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Правила профессий (1.4.165): выбор, опыт, потолки и прибавки.
 *
 * <p>Сервис не знает ни о Bukkit, ни о текстах, поэтому здесь проверяется сама
 * механика: смена раз в сутки, опыт только за своё ремесло, суточный потолок
 * и то, что прибавку получает лишь выбранная профессия.</p>
 */
class ProfessionServiceTest {

    private static final Logger LOGGER = Logger.getLogger("ProfessionServiceTest");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final long NOW = 1_792_000_000_000L;
    private static final long DAY = 86_400_000L;

    private static ProfessionConfig config(String yaml) throws Exception {
        YamlConfiguration source = new YamlConfiguration();
        source.loadFromString(yaml);
        return ProfessionConfig.parse(source, LOGGER);
    }

    private static ProfessionService service(ProfessionConfig config, Path dir) {
        ProfessionStore store = new ProfessionStore(dir, LOGGER);
        return new ProfessionService(config, store);
    }

    @Test
    void firstChoiceIsFreeAndChangeWaits(@TempDir Path dir) {
        ProfessionService service = service(ProfessionConfig.defaults(), dir);
        assertEquals(0L, service.chooseCooldownLeft(PLAYER, NOW), "до выбора менять нечего");
        assertNull(service.active(PLAYER));

        ProfessionResult chosen = service.choose(PLAYER, "smith", NOW);
        assertTrue(chosen.ok());
        assertEquals("profession-chosen", chosen.key());
        assertEquals(List.of("profession", "smith"), chosen.args());
        assertEquals(Profession.SMITH, service.active(PLAYER));
        assertEquals(86_400L, service.chooseCooldownLeft(PLAYER, NOW));

        assertEquals("profession-same", service.choose(PLAYER, "smith", NOW).key());
        assertEquals("profession-unknown", service.choose(PLAYER, "паладин", NOW).key());
        assertEquals(List.of("profession", "паладин"),
                service.choose(PLAYER, "паладин", NOW).args());

        ProfessionResult tooEarly = service.choose(PLAYER, "mason", NOW + 1_000L);
        assertFalse(tooEarly.ok());
        assertEquals("profession-cooldown", tooEarly.key());
        assertEquals(List.of("time", ProfessionMath.clock(86_399)), tooEarly.args());
        assertEquals(Profession.SMITH, service.active(PLAYER), "отказ не меняет ремесло");

        ProfessionResult changed = service.choose(PLAYER, "mason", NOW + DAY);
        assertTrue(changed.ok());
        assertEquals("profession-changed", changed.key());
        assertEquals(List.of("from", "smith", "to", "mason"), changed.args());
        assertEquals(Profession.MASON, service.active(PLAYER));
    }

    @Test
    void checkChooseDoesNotWrite(@TempDir Path dir) {
        ProfessionService service = service(ProfessionConfig.defaults(), dir);
        ProfessionResult ready = service.checkChoose(PLAYER, "cook", NOW);
        assertTrue(ready.ok());
        assertEquals("profession-choose-ready", ready.key());
        assertNull(service.active(PLAYER), "проверка не выбирает ремесло");

        service.choose(PLAYER, "cook", NOW);
        ProfessionResult blocked = service.checkChoose(PLAYER, "smith", NOW + 1_000L);
        assertFalse(blocked.ok());
        assertEquals("profession-cooldown", blocked.key());
    }

    @Test
    void experienceOnlyForActiveProfession(@TempDir Path dir) {
        ProfessionService service = service(ProfessionConfig.defaults(), dir);
        assertEquals(0, service.award(PLAYER, Profession.SMITH, 10, NOW).added(),
                "без ремесла опыт не идёт");

        service.choose(PLAYER, "smith", NOW);
        assertEquals(0, service.award(PLAYER, Profession.MASON, 10, NOW).added(),
                "чужое ремесло опыта не получает");

        ProfessionService.Award award = service.award(PLAYER, Profession.SMITH, 10, NOW);
        assertEquals(10, award.added());
        assertEquals(1, award.levelBefore());
        assertEquals(1, award.levelAfter());
        assertFalse(award.leveled());
        assertEquals(1_190, award.dailyLeft());
        assertEquals(10, service.xp(PLAYER, Profession.SMITH));
        assertEquals(0, service.xp(PLAYER, Profession.MASON));
    }

    @Test
    void dailyCapStopsExperience(@TempDir Path dir) throws Exception {
        ProfessionService service = service(config("""
                general:
                  daily-xp-cap: 25
                """), dir);
        service.choose(PLAYER, "smith", NOW);

        ProfessionService.Award first = service.award(PLAYER, Profession.SMITH, 20, NOW);
        assertEquals(20, first.added());
        assertFalse(first.capped());
        assertEquals(5, first.dailyLeft());

        ProfessionService.Award capped = service.award(PLAYER, Profession.SMITH, 10, NOW);
        assertEquals(5, capped.added(), "дальше потолка опыт не идёт");
        assertTrue(capped.capped());
        assertEquals(0, capped.dailyLeft());
        assertEquals(25, service.xp(PLAYER, Profession.SMITH));

        ProfessionService.Award tomorrow = service.award(PLAYER, Profession.SMITH, 10,
                NOW + DAY);
        assertEquals(10, tomorrow.added(), "на следующий день счётчик сбрасывается");
        assertEquals(15, tomorrow.dailyLeft());
        assertEquals(35, service.xp(PLAYER, Profession.SMITH));
    }

    @Test
    void dailyCapCanBeDisabled(@TempDir Path dir) throws Exception {
        ProfessionService service = service(config("""
                general:
                  daily-xp-cap: 0
                """), dir);
        service.choose(PLAYER, "smith", NOW);
        assertEquals(-1, service.dailyLeft(PLAYER, Profession.SMITH, NOW));
        ProfessionService.Award award = service.award(PLAYER, Profession.SMITH, 5_000, NOW);
        assertEquals(5_000, award.added());
        assertEquals(-1, award.dailyLeft());
        assertFalse(award.capped());
    }

    @Test
    void multiplierScalesExperience(@TempDir Path dir) throws Exception {
        ProfessionService service = service(config("""
                general:
                  xp-multiplier: 2.5
                """), dir);
        service.choose(PLAYER, "smith", NOW);
        assertEquals(25, service.award(PLAYER, Profession.SMITH, 10, NOW).added());
        assertEquals(3, service.award(PLAYER, Profession.SMITH, 1, NOW).added(),
                "минимум один опыт за действие");
    }

    @Test
    void levelUpsAreReported(@TempDir Path dir) {
        ProfessionService service = service(ProfessionConfig.defaults(), dir);
        service.choose(PLAYER, "smith", NOW);

        ProfessionService.Award second = service.award(PLAYER, Profession.SMITH, 120, NOW);
        assertTrue(second.leveled());
        assertEquals(1, second.levelBefore());
        assertEquals(2, second.levelAfter());

        ProfessionService.Award almost = service.award(PLAYER, Profession.SMITH, 179, NOW);
        assertFalse(almost.leveled());
        assertEquals(2, almost.levelAfter());

        ProfessionService.Award third = service.award(PLAYER, Profession.SMITH, 1, NOW);
        assertTrue(third.leveled());
        assertEquals(3, third.levelAfter());
        assertEquals(3, service.level(PLAYER));
    }

    @Test
    void perksFollowActiveProfession(@TempDir Path dir) {
        ProfessionService service = service(ProfessionConfig.defaults(), dir);
        assertEquals(0, service.smithDiscount(PLAYER), "без ремесла прибавок нет");

        service.choose(PLAYER, "smith", NOW);
        assertEquals(0, service.smithDiscount(PLAYER), "на первом уровне скидки ещё нет");

        service.grantByAdmin(PLAYER, "smith", 120);
        assertEquals(4, service.smithDiscount(PLAYER), "2-й уровень: 2 % за уровень");
        assertEquals(0, service.alchemistChance(PLAYER), "чужая прибавка молчит");
        assertEquals(0, service.cookSaturation(PLAYER));
        assertEquals(0, service.masonChance(PLAYER));

        service.choose(PLAYER, "alchemist", NOW + DAY);
        assertEquals(0, service.smithDiscount(PLAYER), "после смены кузнец молчит");
        assertEquals(1, service.alchemistChance(PLAYER));
    }

    @Test
    void disabledConfigBlocksEverything(@TempDir Path dir) throws Exception {
        ProfessionService service = service(config("""
                general:
                  enabled: false
                """), dir);
        assertEquals("disabled", service.choose(PLAYER, "smith", NOW).key());
        assertEquals(0, service.award(PLAYER, Profession.SMITH, 10, NOW).added());

        service.setByAdmin(PLAYER, "smith", NOW);
        service.grantByAdmin(PLAYER, "smith", 120);
        assertEquals(0, service.smithDiscount(PLAYER), "выключенные профессии не дают прибавок");
        assertEquals(0, service.cookSaturation(PLAYER));
    }

    @Test
    void adminToolsSetGrantAndReset(@TempDir Path dir) {
        ProfessionService service = service(ProfessionConfig.defaults(), dir);
        assertEquals("profession-unknown", service.setByAdmin(PLAYER, "", NOW).key());

        ProfessionResult set = service.setByAdmin(PLAYER, "cook", NOW);
        assertTrue(set.ok());
        assertEquals("admin-profession-set", set.key());
        assertEquals(Profession.COOK, service.active(PLAYER));

        ProfessionResult granted = service.grantByAdmin(PLAYER, "cook", 130);
        assertTrue(granted.ok());
        assertEquals("admin-xp-done", granted.key());
        assertEquals(List.of("profession", "cook", "amount", "130", "level", "2"), granted.args());
        assertEquals(130, service.totalXp(PLAYER));

        assertEquals("admin-xp-amount", service.grantByAdmin(PLAYER, "cook", 0).key());
        assertEquals(130, service.totalXp(PLAYER), "нулевая выдача ничего не меняет");

        ProfessionResult reset = service.resetByAdmin(PLAYER);
        assertTrue(reset.ok());
        assertEquals("admin-reset", reset.key());
        assertEquals(0, service.totalXp(PLAYER));
        assertNull(service.active(PLAYER));

        assertFalse(service.resetByAdmin(PLAYER).ok());
        assertEquals("admin-reset-none", service.resetByAdmin(PLAYER).key());
    }

    @Test
    void utcDayIsTimezoneIndependent() {
        assertEquals("1970-01-01", ProfessionService.utcDay(0L));
        assertEquals("1970-01-02", ProfessionService.utcDay(DAY));
        assertEquals("2026-10-14", ProfessionService.utcDay(NOW));

        long start = NOW / DAY * DAY;
        assertEquals(ProfessionService.utcDay(start), ProfessionService.utcDay(start + DAY - 1));
        assertFalse(ProfessionService.utcDay(start)
                .equals(ProfessionService.utcDay(start + DAY)));
    }
}
