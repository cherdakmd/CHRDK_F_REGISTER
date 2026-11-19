package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Рантайм-контракт перерождений без Bukkit/Mockito: текстовые и командные
 * контракты по исходникам плюс мягкая проверка падения Core. Настоящий JUnit
 * CI компилирует и прогоняет и {@link RebirthMathTest}.
 */
final class SkillRebirthServiceTest {

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    private static Messages messages() throws IOException {
        String yml = read("src/main/resources/messages.yml");
        return Messages.from(new ByteArrayInputStream(yml.getBytes(StandardCharsets.UTF_8)),
                null, Logger.getLogger("SkillRebirthServiceTest"));
    }

    private static ChrdkRegisterApi apiForRank(int level) {
        return (ChrdkRegisterApi) Proxy.newProxyInstance(
                ChrdkRegisterApi.class.getClassLoader(),
                new Class<?>[]{ChrdkRegisterApi.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("rankLevel")) {
                        return level;
                    }
                    Class<?> returns = method.getReturnType();
                    if (returns == boolean.class) return false;
                    if (returns == int.class) return 0;
                    if (returns == long.class) return 0L;
                    return null;
                });
    }

    @Test
    void absentOrOutdatedCoreNeverBreaksLeveling() {
        assertEquals(0, SkillService.effectiveRankLevel(null, "Ada"));
        assertEquals(5, SkillService.effectiveRankLevel(apiForRank(5), "Ada"));
        assertEquals(0, SkillService.effectiveRankLevel(apiForRank(-3), "Ada"));

        ChrdkRegisterApi broken = (ChrdkRegisterApi) Proxy.newProxyInstance(
                ChrdkRegisterApi.class.getClassLoader(),
                new Class<?>[]{ChrdkRegisterApi.class},
                (proxy, method, args) -> {
                    throw new IllegalStateException("Core отвечает ошибкой");
                });
        assertEquals(0, SkillService.effectiveRankLevel(broken, "Ada"));

        ChrdkRegisterApi oldBinary = (ChrdkRegisterApi) Proxy.newProxyInstance(
                ChrdkRegisterApi.class.getClassLoader(),
                new Class<?>[]{ChrdkRegisterApi.class},
                (proxy, method, args) -> {
                    throw new NoSuchMethodError("rankLevel отсутствует в старом Core");
                });
        assertEquals(0, SkillService.effectiveRankLevel(oldBinary, "Ada"));
    }

    @Test
    void awardAppliesRebirthFactorAfterBoostersBeforeTheCap() throws IOException {
        String service = read("src/main/java/ru/cherdakmd/chrdkskills/SkillService.java");
        int start = service.indexOf("public Award award(Player player");
        assertTrue(start >= 0, "award не найден");
        // Окно метода фиксированное: порядок множителей проверяем в его теле.
        String award = service.substring(start,
                Math.min(service.length(), start + 3000));

        int boost = award.indexOf("boostExperience(");
        int rebirth = award.indexOf("RebirthMath.xpFactor(rebirths(player)");
        int wisdom = award.indexOf("AttributeMath.xpFactor(");
        int clamp = award.indexOf("Math.max(0L, Math.min(plugin.config().maxXpPerAction(), gained))");
        assertTrue(boost >= 0 && rebirth > boost && wisdom > rebirth && clamp > wisdom,
                "множитель перерождения после бустеров ядра и до клампа действия");
        assertFalse(award.contains("PrestigeXpGate"), "правило баланса удалено целиком");
    }

    @Test
    void applyRebirthResetsAndPaysRewards() throws IOException {
        String service = read("src/main/java/ru/cherdakmd/chrdkskills/SkillService.java");
        int start = service.indexOf("private RebirthResult applyRebirth(Player player)");
        assertTrue(start >= 0, "applyRebirth не найден");
        String method = service.substring(start, service.indexOf("private void giveUniqueReward", start));

        int confirm = method.indexOf("confirmations.consume(player.getUniqueId(), \"rebirth\", \"all\", now)");
        int reset = method.indexOf("store.resetForRebirth(key(player))");
        int add = method.indexOf("store.addRebirth(key(player))");
        int diamonds = method.indexOf("giveDiamonds(player, RebirthMath.rebirthDiamonds(");
        int unique = method.indexOf("giveUniqueReward(player)");
        int milestone = method.indexOf("grantMilestoneRewards(player, count)");
        assertTrue(confirm >= 0 && reset > confirm && add > reset && diamonds > add
                        && unique > diamonds && milestone > unique,
                "подтверждение → полный сброс → счётчик → награды по порядку");

        String uniqueMethod = service.substring(service.indexOf("private void giveUniqueReward"),
                service.indexOf("private ItemStack buildUnique"));
        assertTrue(uniqueMethod.contains("grantRebirthUnique"), "выданный id фиксируется");
        assertTrue(uniqueMethod.contains("* 2"), "исчерпанный пул — удвоенные алмазы");

        String milestoneMethod = service.substring(service.indexOf("private void grantMilestoneRewards"));
        assertTrue(milestoneMethod.contains("markRebirthMilestone"), "веха одноразовая");
        assertTrue(milestoneMethod.contains("rebirth-milestone-announce"));
    }

    @Test
    void commandContractIsRebirthAndRebirthtop() throws IOException {
        String pluginYml = read("src/main/resources/plugin.yml");
        assertTrue(pluginYml.contains("rebirth [confirm]|rebirthtop"), "usage обновлён");
        assertFalse(pluginYml.contains("prestige"), "старая команда убрана из usage");

        String handler = read("src/main/java/ru/cherdakmd/chrdkskills/SkillCommandHandler.java");
        assertTrue(handler.contains("args[0].equalsIgnoreCase(\"rebirth\")"));
        assertTrue(handler.contains("args[0].equalsIgnoreCase(\"rebirthtop\")"));
        assertTrue(handler.contains("service.requestRebirth(player)"));
        assertTrue(handler.contains("service.rebirthTop()"));
        assertFalse(handler.contains("handleBalance"), "команда /skills balance удалена");
        assertFalse(handler.contains("PrestigeMath"), "PrestigeMath вырезан");
        assertFalse(handler.contains("PrestigeXpGate"), "PrestigeXpGate вырезан");
    }

    @Test
    void rebirthMessagesExistAndOldOnesAreGone() throws IOException {
        Messages messages = messages();
        for (String key : new String[]{
                "rebirth-usage", "rebirth-disabled", "rebirth-not-ready",
                "rebirth-confirm", "rebirth-done", "rebirth-title",
                "rebirth-subtitle", "rebirth-milestone-announce",
                "rebirth-top-header", "rebirth-top-line", "rebirth-top-empty",
                "profile-rebirth", "menu-summary-rebirth", "menu-tab-rebirth",
                "menu-rebirth-title", "menu-rebirth-count", "menu-rebirth-bonus-xp",
                "menu-rebirth-next-milestone", "menu-rebirth-hint",
                "menu-rebirth-top-button", "menu-rebirth-top-line", "menu-rebirth-back"}) {
            assertTrue(messages.has(key), "нет текста " + key);
        }
        String rendered = messages.raw("rebirth-done",
                "count", "3", "title", "Перерождение III", "bonus", "12.9");
        assertFalse(rendered.contains("%count%") || rendered.contains("%title%")
                        || rendered.contains("%bonus%"),
                "не подставлен плейсхолдер: " + rendered);
        for (String key : new String[]{
                "prestige-usage", "prestige-done", "balance-header",
                "menu-summary-prestige", "menu-prestige-line", "prestige-balance-bar"}) {
            assertFalse(messages.has(key), "старый ключ остался: " + key);
        }
    }

    @Test
    void configCarriesRebirthKnobsWithoutPrestigeBlock() throws IOException {
        String config = read("src/main/resources/config.yml");
        for (String key : new String[]{"rebirth:", "xp-percent-per-rebirth: 5.0",
                "xp-decay: 0.9", "xp-bonus-cap-percent: 50.0",
                "diamonds-per-rebirth: 32", "milestone-diamonds: 128",
                "announce-milestones: true", "unique-items:",
                "max-level: 150"}) {
            assertTrue(config.contains(key), "нет ключа/значения " + key);
        }
        assertFalse(config.contains("xp-per-step"), "старый множитель ступеней удалён");
        assertFalse(config.contains("balance:"), "правило баланса удалено из конфига");

        String schema = read("src/main/resources/config-schema.yml");
        assertTrue(schema.contains("messages-version: 12"));
        assertTrue(schema.contains("remove: [leveling.max-level]"),
                "потолок навыка переписывается эталоном при обновлении");
    }

    @Test
    void docsDescribeRebirth() throws IOException {
        String wiki = read("../wiki/skills.md");
        assertTrue(wiki.contains("/skills rebirth"));
        assertTrue(wiki.contains("/skills rebirthtop"));
        assertFalse(wiki.contains("/skills balance"), "мёртвая команда убрана из wiki");

        String design = read("../docs/design/REBIRTH.md");
        assertTrue(design.contains("xpBonusPercent"), "формула множителя описана");
        assertTrue(design.contains("fromLegacySteps"), "конвертация описана");
    }

    @Test
    void diamondDropsStayOutsideTheXpRule() throws IOException {
        String listener = read("src/main/java/ru/cherdakmd/chrdkskills/SkillListener.java");
        int award = listener.indexOf("service.award(player, skill, 1L);");
        int drop = listener.indexOf("diamondDrop(player, skill,", award);
        assertTrue(award >= 0 && drop > award,
                "физический дроп вызывается после award и не зависит от его результата");

        int start = listener.indexOf("private void diamondDrop(");
        int end = listener.indexOf("\n    /**", start);
        assertTrue(start >= 0 && end > start);
        String method = listener.substring(start, end);
        assertTrue(method.contains("DiamondDrops.roll("));
        assertTrue(method.contains("dropItemNaturally("));
        assertFalse(method.contains("PrestigeXpGate"));
        assertFalse(method.contains("Award.none"));
    }
}
