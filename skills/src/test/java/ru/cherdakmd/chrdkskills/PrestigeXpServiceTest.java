package ru.cherdakmd.chrdkskills;

import org.bukkit.configuration.file.YamlConfiguration;
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
 * Рантайм-контракт мягкой зоны без Bukkit/Mockito: чистая проверка падения
 * Core, состояния уведомлений и текстовых/командных контрактов по исходникам.
 * Настоящий JUnit CI компилирует и прогоняет и {@link PrestigeXpGateTest}.
 */
final class PrestigeXpServiceTest {

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    private static Messages messages() throws IOException {
        String yml = read("src/main/resources/messages.yml");
        return Messages.from(new ByteArrayInputStream(yml.getBytes(StandardCharsets.UTF_8)),
                null, Logger.getLogger("PrestigeXpServiceTest"));
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
    void chatNoticeIsWrittenOnlyWhenTheStateChanges() {
        SkillService.BalanceState state = new SkillService.BalanceState(0.75, 2);
        assertTrue(state.same(0.75, 2), "тот же множитель и зона — чат молчит");
        assertFalse(state.same(0.50, 2), "смена множителя — новое сообщение");
        assertFalse(state.same(0.75, 3), "смена allowance после покупки ранга — новое сообщение");
        assertFalse(state.same(0.0, 2), "жёсткий стоп — новое сообщение");
    }

    @Test
    void awardAppliesTheFactorAfterBoostersAndKeepsOneXp() throws IOException {
        String service = read("src/main/java/ru/cherdakmd/chrdkskills/SkillService.java");
        int start = service.indexOf("public Award award(Player player");
        int end = service.indexOf("/** {@code null} — баланс выключен", start);
        assertTrue(start >= 0 && end > start);
        String award = service.substring(start, end);
        assertTrue(award.contains("BalanceView balance = balanceView(player)"),
                "ранг пересчитывается на каждом действии, без кэша и /reload");
        assertTrue(award.contains("if (balanceFactor >= 1.0)"));
        assertTrue(award.contains("clearBalanceState(player, skill)"));
        assertTrue(award.contains("if (balanceFactor <= 0.0)"));
        assertTrue(award.contains("return Award.none(skill)"));

        int boost = award.indexOf("boostExperience(");
        int prestige = award.indexOf("PrestigeMath.xpFactor(");
        int soft = award.indexOf("PrestigeXpGate.scaledXp(gained, balanceFactor)");
        int clamp = award.indexOf("Math.max(0L, Math.min(plugin.config().maxXpPerAction(), gained))");
        assertTrue(boost >= 0 && prestige > boost && soft > prestige && clamp > soft,
                "множитель после бустеров ядра и престижа, до клампа действия");

        int notifyStart = service.indexOf("private void notifyBalance(");
        int notifyEnd = service.indexOf("/** Вернулись к полному опыту", notifyStart);
        assertTrue(notifyStart >= 0 && notifyEnd > notifyStart);
        String notify = service.substring(notifyStart, notifyEnd);
        assertTrue(notify.contains("balanceStates.put(key, new BalanceState(factor, allowance))"));
        assertTrue(notify.contains("previous != null && previous.same(factor, allowance)"));
        assertTrue(notify.contains("\"prestige-balance-bar\""));
        assertTrue(notify.contains("\"prestige-balance-bar-stop\""));
        assertTrue(notify.contains("\"prestige-balance-state\""));
        assertTrue(notify.contains("\"prestige-balance-state-stop\""));
        assertTrue(notify.contains("balanceBars.put(player.getUniqueId(), now)"),
                "actionbar с кулдауном, а не на каждый блок");
        assertTrue(service.contains("catch (RuntimeException | LinkageError outdated)"));
    }

    @Test
    void balanceCommandHasUsageTabCompleteAndTenRows() throws IOException {
        String pluginYml = read("src/main/resources/plugin.yml");
        assertTrue(pluginYml.contains("prestigetop|balance"), "usage включает /skills balance");

        String handler = read("src/main/java/ru/cherdakmd/chrdkskills/SkillCommandHandler.java");
        assertTrue(handler.contains("args[0].equalsIgnoreCase(\"balance\")"));
        assertTrue(handler.contains("return handleBalance(player)"));
        assertTrue(handler.contains("\"prestigetop\", \"balance\""));
        assertTrue(handler.contains("service.balanceReport(player)"));
        assertTrue(handler.contains("balance-header"));
        assertTrue(handler.contains("balance-line-off"));
        assertTrue(handler.contains("balance-line-stop"));
        assertTrue(handler.contains("balance-line-full"));
        assertTrue(handler.contains("balance-line"));
        assertTrue(handler.contains("balance-footer"));

        String service = read("src/main/java/ru/cherdakmd/chrdkskills/SkillService.java");
        assertTrue(service.contains("public record BalanceReport"));
        assertTrue(service.contains("public record BalanceRow"));
        assertTrue(service.contains("for (SkillType type : SkillType.values())"));
        assertTrue(service.contains("public BalanceReport balanceReport(Player player)"));
    }

    @Test
    void newMessagesExistAndOldRepeatedChatBlockIsGone() throws IOException {
        Messages messages = messages();
        assertFalse(messages.has("prestige-balance-blocked"),
                "старое повторяющееся сообщение удалено");
        for (String key : new String[]{
                "prestige-balance-bar", "prestige-balance-bar-stop",
                "prestige-balance-state", "prestige-balance-state-stop",
                "balance-header", "balance-line", "balance-line-full",
                "balance-line-stop", "balance-line-off", "balance-footer"}) {
            assertTrue(messages.has(key), "нет текста " + key);
            String rendered = messages.raw(key,
                    "skill", "Горное дело", "factor", "0.75",
                    "lead", "3", "allowance", "2", "hard_cap", "8",
                    "name", "Горное дело", "step", "4", "level", "100",
                    "base", "2", "rank_bonus", "0", "rank", "0", "minimum", "0");
            assertFalse(rendered.contains("%skill%") || rendered.contains("%factor%")
                            || rendered.contains("%name%") || rendered.contains("%allowance%")
                            || rendered.contains("%hard_cap%"),
                    "не подставлен плейсхолдер в " + key + ": " + rendered);
        }
        assertTrue(messages.template("prestige-balance-bar").contains("алмазы идут как обычно"));
        assertTrue(messages.template("prestige-balance-bar-stop").contains("алмазы идут как обычно"));
        assertTrue(messages.template("balance-footer").contains("Алмазы и перки"));
        assertTrue(messages.template("balance-footer").contains("/skills prestige all"));
    }

    @Test
    void configAndDocsCarryTheSoftZoneWithoutSchemaBump() throws IOException {
        String config = read("src/main/resources/config.yml");
        for (String key : new String[]{"base-max-lead: 2",
                "rank-max-lead: [0, 1, 1, 2, 2, 3]", "hard-cap: 8",
                "soft-step: 0.25", "min-xp-factor: 0.25", "notice-seconds: 5"}) {
            assertTrue(config.contains(key), "нет ключа/значения " + key);
        }
        YamlConfiguration stock = YamlConfiguration.loadConfiguration(
                Path.of("src/main/resources/config.yml").toFile());
        assertFalse(stock.isSet("prestige.balance.max-lead"),
                "старый ключ остался только в комментарии как фолбэк");

        String schema = read("src/main/resources/config-schema.yml");
        assertTrue(schema.contains("config-version: 2"));
        assertTrue(schema.contains("messages-version: 2"));

        String wiki = read("../wiki/skills.md");
        assertTrue(wiki.contains("/skills balance"));
        assertTrue(wiki.contains("hard-cap"));
        assertTrue(wiki.contains("алмазы и перки"));
        String design = read("../docs/design/PRESTIGE.md");
        assertTrue(design.contains("### 7.1 Баланс прокачки ступеней (мягкая зона)"));
        assertTrue(design.contains("| L5 | 5 |"));
        assertTrue(design.contains("Амнистия и пересчёт"));
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
        assertFalse(method.contains("balanceFactor"));
        assertFalse(method.contains("Award.none"));
    }
}
