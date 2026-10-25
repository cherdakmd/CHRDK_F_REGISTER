package ru.cherdakmd.chrdkregister;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TAB-лист: рендер шаблонов с подстановками — без сервера.
 * Важное свойство: значения игрока экранируются, ник с разметкой не сломает футер.
 */
class TabListServiceTest {

    private static final Logger LOGGER = Logger.getAnonymousLogger();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static Messages messages() {
        return Messages.from(new ByteArrayInputStream("messages: {}".getBytes(StandardCharsets.UTF_8)),
                null, LOGGER);
    }

    private static TabListService.TabContext context() {
        return new TabListService.TabContext(
                "Steve", 7, 100, 19.97, 42, "world_nether", "активна", "12 дн. 3 ч.", 13);
    }

    @Test
    void placeholdersAreFilledEverywhere() {
        Component line = TabListService.renderLines(messages(),
                List.of("<gold>%player%</gold> #%online%/%max% ping %ping% tps %tps% world %world%"),
                context()).get(0);
        assertEquals("Steve #7/100 ping 42 tps 20.0 world world_nether", PLAIN.serialize(line));
    }

    @Test
    void tpsIsCappedAndFormatted() {
        var ctx = new TabListService.TabContext("A", 1, 1, 21.5, 0, "w", "", "", 0);
        Component line = TabListService.renderName(messages(), "%tps%", ctx);
        assertEquals("20.0", PLAIN.serialize(line));
        var negative = new TabListService.TabContext("A", 1, 1, -5.0, 0, "w", "", "", 0);
        assertEquals("0.0", PLAIN.serialize(TabListService.renderName(messages(), "%tps%", negative)));
    }

    @Test
    void taggedNameIsRenderedAsMarkup() {
        var ctx = new TabListService.TabContext("Steve", 1, 1, 20.0, 0, "w", "", "", 0,
                new PresentationContext("", "", "", "", ""),
                "<bold>Steve</bold> <red>[СТРИМ]</red>");
        Component line = TabListService.renderName(messages(), "%player_tagged% / %player%", ctx);
        assertEquals("Steve [СТРИМ] / Steve", PLAIN.serialize(line),
                "%player_tagged% — доверенная разметка, %player% по-прежнему экранируется");
    }

    @Test
    void rankTagAddsRankNameAndStaysMarkup() {
        var ctx = new TabListService.TabContext("Steve", 1, 1, 20.0, 0, "w", "", "", 0,
                new PresentationContext("", "", "", "", ""),
                "<gray>Steve</gray>",
                " <dark_gray>·</dark_gray> <gradient:#b388ff:#ffd700>Покровитель</gradient>");
        Component line = TabListService.renderName(messages(), "%player_tagged%%rank_tag%", ctx);
        assertEquals("Steve · Покровитель", PLAIN.serialize(line),
                "название ранга — доверенная разметка, а не литералы <gradient…>");
    }

    @Test
    void rankTagIsEmptyWithoutRank() {
        var ctx = new TabListService.TabContext("Steve", 1, 1, 20.0, 0, "w", "", "", 0,
                new PresentationContext("", "", "", "", ""), "<gray>Steve</gray>", "");
        Component line = TabListService.renderName(messages(), "%player_tagged%%rank_tag%", ctx);
        assertEquals("Steve", PLAIN.serialize(line),
                "без ранга в списке не должно оставаться висящего разделителя");
    }

    @Test
    void oldNameFormatGetsRankSuffixOnTheFly() {
        assertEquals("%player_tagged%%rank_tag%",
                TabListService.nameFormat("%player_tagged%", true),
                "старый конфиг без %rank_tag% должен получить название ранга сам");
        assertEquals("%player_tagged%%rank_tag%",
                TabListService.nameFormat("%player_tagged%%rank_tag%", true),
                "повторно суффикс не добавляется");
        assertEquals("%rank_tag% %player_tagged%",
                TabListService.nameFormat("%rank_tag% %player_tagged%", true));
        assertEquals("%player_tagged%",
                TabListService.nameFormat("%player_tagged%", false),
                "show-rank-name: false возвращает прежний вид");
        assertEquals("", TabListService.nameFormat("", true));
        assertNull(TabListService.nameFormat(null, true));
    }

    @Test
    void rankTagTextShowsStepAndAvoidsDoubleName() {
        RankSettings ranks = RankSettings.defaults();
        String tag = TabListService.rankTagText(ranks, 5);
        assertEquals(" · Покровитель", Messages.stripFormatting(tag),
                "хвост отделяется точкой-разделителем и содержит название ступени");
        assertEquals("", TabListService.rankTagText(ranks, 0), "без ранга хвоста нет");
        assertEquals("", TabListService.rankTagText(null, 5), "нет настроек — нет хвоста");
    }

    @Test
    void maliciousNickCannotBreakMarkup() {
        var hacker = new TabListService.TabContext("X<red>HAZED", 1, 1, 20.0, 0, "w", "", "", 0);
        Component line = TabListService.renderLines(messages(),
                List.of("<gold>%player% — %pass_status%, осталось %pass_days% дн.</gold>"),
                hacker).get(0);
        String plain = PLAIN.serialize(line);
        assertTrue(plain.contains("X<red>HAZED"),
                "ник с разметкой должен выйти как есть (экранирован), а не перекрасить строку");
        assertTrue(plain.contains("активна") || plain.contains("—"),
                "остальные подстановки на месте");
    }

    @Test
    void joinLinesInsertsNewlinesAndHandlesEmpty() {
        Component joined = TabListService.joinLines(List.of(
                Component.text("первая"), Component.text("вторая"), Component.text("третья")));
        assertEquals("первая\nвторая\nтретья", PLAIN.serialize(joined));
        assertEquals("", PLAIN.serialize(TabListService.joinLines(List.of())));
    }

    @Test
    void legacyAmpersandCodesWorkNextToMiniMessage() {
        Component line = TabListService.renderLines(messages(),
                List.of("&6Игрок: &f%player% <gray>(%ping% мс)</gray>"), context()).get(0);
        assertEquals("Игрок: Steve (42 мс)", PLAIN.serialize(line));
    }

    @Test
    void pinnedMatchingIsCaseAndWhitespaceInsensitive() {
        List<String> pinned = List.of("kalinin_play", " Admin ");
        assertTrue(TabListService.isPinned(pinned, "KALININ_PLAY"));
        assertTrue(TabListService.isPinned(pinned, "kalinin_play"));
        assertTrue(TabListService.isPinned(pinned, "admin"));
        assertTrue(!TabListService.isPinned(pinned, "kalinin_play2"));
        assertTrue(!TabListService.isPinned(List.of(), "kalinin_play"));
        assertTrue(!TabListService.isPinned(null, "kalinin_play"));
        assertTrue(!TabListService.isPinned(pinned, null));
    }

    @Test
    void pinTeamSortsAboveRegularTeamsAndFitsNameLimit() {
        // клиент сортирует список по имени команды: '!' (0x21) выше любых букв/цифр
        assertEquals('!', TabListService.PIN_TEAM_NAME.charAt(0));
        assertTrue(TabListService.PIN_TEAM_NAME.compareTo("z") < 0
                && TabListService.PIN_TEAM_NAME.compareTo("A") < 0
                && TabListService.PIN_TEAM_NAME.compareTo("0ranks") < 0,
                "команда закрепа должна быть первой в алфавите команд");
        assertTrue(TabListService.PIN_TEAM_NAME.length() <= 16,
                "имя команды должно влезать в лимит 16 символов");
    }
}
