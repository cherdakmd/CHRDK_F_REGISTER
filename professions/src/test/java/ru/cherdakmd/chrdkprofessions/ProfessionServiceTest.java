package ru.cherdakmd.chrdkprofessions;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.RankBonus;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Правила ремёсел (1.4.282): выбор отменён, все восемь активны, опыт и доход
 * идут по всем, донатный ранг усиливает и то и другое, выплата ограничена.
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
    void choiceIsRemovedAndEveryProfessionIsActive(@TempDir Path dir) {
        ProfessionService service = service(ProfessionConfig.defaults(), dir);
        for (Profession profession : Profession.values()) {
            assertTrue(service.isActive(PLAYER, profession), profession.id());
        }
        assertFalse(service.isActive(PLAYER, null));
        assertEquals(0L, service.chooseCooldownLeft(PLAYER, NOW));
        assertEquals("choose-removed", service.choose(PLAYER, "smith", NOW).key());
        assertEquals("choose-removed", service.checkChoose(PLAYER, "smith", NOW).key());
        assertEquals("choose-removed", service.setByAdmin(PLAYER, "smith", NOW).key());
        assertEquals(Profession.SMITH, service.primary(PLAYER), "фокус меню по умолчанию");
    }

    @Test
    void experienceGoesToEveryProfession(@TempDir Path dir) {
        ProfessionService service = service(ProfessionConfig.defaults(), dir);
        for (Profession profession : Profession.values()) {
            ProfessionService.Award award = service.award(PLAYER, profession, 10, NOW);
            assertEquals(10, award.added(), profession.id());
            assertEquals(1, award.levelAfter());
            assertEquals(10, service.xp(PLAYER, profession));
        }
        assertEquals(80, service.totalXp(PLAYER));
        assertFalse(service.award(PLAYER, null, 10, NOW).leveled());
    }

    @Test
    void dailyCapStopsBaseExperience(@TempDir Path dir) throws Exception {
        ProfessionService service = service(config("""
                general:
                  daily-xp-cap: 25
                """), dir);

        ProfessionService.Award first = service.award(PLAYER, Profession.SMITH, 20, NOW);
        assertEquals(20, first.added());
        assertFalse(first.capped());
        assertEquals(5, first.dailyLeft());

        ProfessionService.Award capped = service.award(PLAYER, Profession.SMITH, 10, NOW);
        assertEquals(5, capped.added(), "дальше потолка базовый опыт не идёт");
        assertTrue(capped.capped());
        assertEquals(0, capped.dailyLeft());
        assertEquals(25, service.xp(PLAYER, Profession.SMITH));

        ProfessionService.Award tomorrow = service.award(PLAYER, Profession.SMITH, 10, NOW + DAY);
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
        assertEquals(25, service.award(PLAYER, Profession.SMITH, 10, NOW).added());
        assertEquals(3, service.award(PLAYER, Profession.SMITH, 1, NOW).added(),
                "минимум один опыт за действие");
    }

    @Test
    void levelUpsAreReported(@TempDir Path dir) {
        ProfessionService service = service(ProfessionConfig.defaults(), dir);

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
        assertEquals(3, service.level(PLAYER), "лучший уровень среди всех ремёсел");
    }

    @Test
    void perksOfAllProfessionsWorkAtOnce(@TempDir Path dir) {
        ProfessionService service = service(ProfessionConfig.defaults(), dir);
        assertEquals(2, service.smithDiscount(PLAYER), "прибавки работают без всякого выбора");
        assertEquals(1, service.alchemistChance(PLAYER));
        assertEquals(1, service.masonChance(PLAYER));

        service.grantByAdmin(PLAYER, "smith", 120);
        assertEquals(4, service.smithDiscount(PLAYER), "2-й уровень: 2 % за уровень");
        assertEquals(1, service.alchemistChance(PLAYER), "другое ремесло не отключилось");
    }

    @Test
    void disabledConfigBlocksEverything(@TempDir Path dir) throws Exception {
        ProfessionService service = service(config("""
                general:
                  enabled: false
                """), dir);
        assertEquals("choose-removed", service.choose(PLAYER, "smith", NOW).key());
        assertEquals(0, service.award(PLAYER, Profession.SMITH, 10, NOW).added());
        service.grantByAdmin(PLAYER, "smith", 120);
        assertEquals(0, service.smithDiscount(PLAYER), "выключенные ремесла не дают прибавок");
        assertEquals(0, service.cookSaturation(PLAYER));
    }

    @Test
    void adminToolsGrantAndReset(@TempDir Path dir) {
        ProfessionService service = service(ProfessionConfig.defaults(), dir);
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
        assertFalse(service.resetByAdmin(PLAYER).ok());
        assertEquals("admin-reset-none", service.resetByAdmin(PLAYER).key());
    }

    /* ------------------------------- заработок ------------------------------- */

    @Test
    void oneDiamondPerHundredXpWithFractionalRemainder(@TempDir Path dir) {
        ProfessionService service = service(ProfessionConfig.defaults(), dir);
        assertEquals(0, service.award(PLAYER, Profession.SMITH, 60, NOW).diamonds());
        assertEquals(600, service.incomeMicro(PLAYER));
        ProfessionService.Award award = service.award(PLAYER, Profession.MASON, 40, NOW);
        assertEquals(1, award.diamonds(), "дробный остаток общий на игрока");
        assertEquals(0, service.incomeMicro(PLAYER));
        assertEquals(1, service.incomeTotal(PLAYER));
        assertEquals(1, service.incomeToday(PLAYER, NOW));
        assertEquals(0, service.incomeToday(PLAYER, NOW + DAY));
    }

    @Test
    void rankBonusRaisesBothXpAndIncome(@TempDir Path dir) {
        ProfessionService rank = service(ProfessionConfig.defaults(), dir);
        rank.setRankBonus(id -> new RankBonus(5, 50, 0, 0)); // +50 % опыта
        ProfessionService plain = service(ProfessionConfig.defaults(), dir);

        assertEquals(100, plain.award(PLAYER, Profession.SMITH, 100, NOW).added());
        assertEquals(1, plain.award(PLAYER, Profession.SMITH, 100, NOW).diamonds());

        ProfessionService.Award first = rank.award(PLAYER, Profession.SMITH, 100, NOW);
        assertEquals(150, first.added(), "ранг даёт больше опыта");
        assertEquals(1, first.diamonds());
        assertEquals(2, rank.award(PLAYER, Profession.SMITH, 100, NOW).diamonds(),
                "300 опыта с бонусом = 3 алмаза против 2 без ранга");
        assertEquals(3, rank.incomeTotal(PLAYER));
    }

    @Test
    void rankBonusIsAddedAboveTheDailyXpCap(@TempDir Path dir) throws Exception {
        ProfessionService service = service(config("""
                general:
                  daily-xp-cap: 100
                """), dir);
        service.setRankBonus(id -> new RankBonus(5, 50, 0, 0));
        assertEquals(150, service.award(PLAYER, Profession.SMITH, 100, NOW).added());
        assertEquals(100, service.dailyUsed(PLAYER, Profession.SMITH, NOW),
                "потолок считается по базовому опыту");
        assertEquals(50, service.award(PLAYER, Profession.SMITH, 100, NOW).added(),
                "после потолка ранг всё ещё получает свою надбавку");
        assertEquals(200, service.xp(PLAYER, Profession.SMITH));
    }

    @Test
    void incomeIsCappedAt9999PerDayAndCanBeDisabled(@TempDir Path dir) throws Exception {
        ProfessionService capped = service(config("""
                general:
                  daily-xp-cap: 0
                income:
                  xp-per-diamond: 10
                  daily-cap: 2
                """), dir);
        assertEquals(2, capped.award(PLAYER, Profession.SMITH, 10_000, NOW).diamonds());
        assertEquals(2, capped.incomeToday(PLAYER, NOW));
        assertEquals(0, capped.award(PLAYER, Profession.SMITH, 10_000, NOW).diamonds());
        assertEquals(2, capped.incomeTotal(PLAYER));

        ProfessionService off = service(config("""
                income:
                  enabled: false
                """), dir);
        assertEquals(0, off.award(PLAYER, Profession.SMITH, 10_000, NOW).diamonds());
        assertEquals(0, off.incomeTotal(PLAYER));
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
