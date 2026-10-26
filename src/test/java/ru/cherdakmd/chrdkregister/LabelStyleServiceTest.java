package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Сборка ника (1.4.187): порядок меток слева направо, свой цвет против цвета
 * ступени и то, что скрытая надпись не появляется ни в каком порядке.
 */
class LabelStyleServiceTest {

    private static final Logger LOGGER = Logger.getLogger("LabelStyleServiceTest");
    private static final LabelPreferences.Label R = LabelPreferences.Label.RANK;
    private static final LabelPreferences.Label G = LabelPreferences.Label.GUILDS;
    private static final LabelPreferences.Label C = LabelPreferences.Label.CLASSES;

    /** Метки-заглушки: каждая приписывает свой ярлык перед строкой. */
    private static String tag(String id, String playerName, String decorated) {
        String label = switch (id) {
            case "guilds" -> "[ГИ]";
            case "classes" -> "[КЛ]";
            case "achievements" -> "[ДО]";
            default -> null;
        };
        return label == null ? decorated : label + " " + decorated;
    }

    private static final LabelStyleService.RankLookup LOOKUP = new LabelStyleService.RankLookup() {
        @Override
        public LabelStyleService.Rank view(String playerName) {
            return null;
        }

        @Override
        public List<String> registrationOrder() {
            return List.of("achievements", "classes", "guilds");
        }
    };

    /** Сервис без настроек: compose их не читает. */
    private static LabelStyleService plain() {
        return new LabelStyleService(LabelStyleServiceTest::tag,
                new LabelPreferences(Path.of("build", "never-written"), LOGGER), LOOKUP);
    }

    private static LabelStyleService with(LabelPreferences preferences) {
        return new LabelStyleService(LabelStyleServiceTest::tag, preferences, LOOKUP);
    }

    private static LabelPreferences fresh(Path dir) {
        LabelPreferences preferences = new LabelPreferences(dir, LOGGER);
        preferences.load();
        return preferences;
    }

    private static String text(String markup) {
        return Messages.stripFormatting(markup);
    }

    @Test
    void nameAlwaysComesBeforeLabels() {
        assertEquals("Имя · [ГИ] · [КЛ]",
                text(plain().compose("Имя", "Имя", List.of(G, C, R), "", "")));
    }

    @Test
    void badgeAndRankNameStayTogetherAtSelectedPosition() {
        assertEquals("Имя · [ГИ] · ✦ Странник · [КЛ]",
                text(plain().compose("Имя", "Имя", List.of(G, R, C), "✦", "Странник")));
        assertEquals("Имя · [ГИ] · [КЛ]",
                text(plain().compose("Имя", "Имя", List.of(G, C, R), "", "")));
        var selection = plain().select("Имя", List.of(R, G, C), "✦", "Странник", Set.of(), 2);
        assertEquals(List.of(R, G), selection.shown().stream().map(LabelStyleService.Decoration::source).toList());
        assertEquals(List.of(C), selection.overflow().stream().map(LabelStyleService.Decoration::source).toList());
    }

    @Test
    void hiddenRankDoesNotLeakItsNameAndHiddenTagsDoNotUseSlots() {
        assertEquals("Имя · [КЛ]",
                text(plain().compose("Имя", "Имя", List.of(G, C, R), "✦", "Странник", Set.of(G, R))));
        assertEquals("Имя", text(plain().compose("Имя", "Имя", List.of(G, C, R), "✦", "Странник",
                Set.of(G, C, R))));
        var selection = plain().select("Имя", List.of(G, R, C), "✦", "Странник", Set.of(G), 2);
        assertEquals(List.of(R, C), selection.shown().stream().map(LabelStyleService.Decoration::source).toList());
        assertTrue(selection.overflow().isEmpty());
    }

    @Test
    void absentSourcesAndHeaderDoNotUseSlots() {
        var selection = plain().select("Имя", List.of(LabelPreferences.Label.QUESTS,
                LabelPreferences.Label.HEADER, R, G, C), "", "", Set.of(), 2);
        assertEquals(List.of(G, C), selection.shown().stream().map(LabelStyleService.Decoration::source).toList());
        assertTrue(selection.overflow().isEmpty());
        assertEquals("Имя", text(plain().compose("Имя", "Имя", List.of(LabelPreferences.Label.QUESTS), "", "")));
    }

    @Test
    void emptyOrderFallsBackToDeclarationOrder() {
        assertEquals("Имя · [КЛ] · [ГИ] · [ДО]",
                text(plain().compose("Имя", "Имя", List.of(), "", "")));
    }

    @Test
    void legacyPrefixSuffixAndStreamerWrappingAllBecomeSuffixes(@TempDir Path dir) {
        LabelStyleService styles = new LabelStyleService((id, player, name) -> switch (id) {
            case "classes" -> "<aqua>[КЛ]</aqua> " + name;
            case "guilds" -> name + " <green>[ГИ]</green>";
            case "streams" -> "<bold>" + name + "</bold> <red>[СТРИМ]</red>";
            default -> name;
        }, fresh(dir), LOOKUP);
        String result = styles.compose("Имя", "<gold>Имя</gold>",
                List.of(C, G, LabelPreferences.Label.STREAMS), "", "");
        assertEquals("Имя · [КЛ] · [ГИ] · [СТРИМ]", text(result));
        assertFalse(result.contains("ANCHOR"));
        assertTrue(result.contains("<aqua>"));
        assertTrue(result.contains("<green>"));
        assertTrue(result.contains("<red>"));
        assertFalse(result.contains("<bold>Имя"), "аддон больше не меняет стиль самого ника");
    }

    @Test
    void nestedAndLegacyColorsSurviveProjection(@TempDir Path dir) {
        LabelStyleService styles = new LabelStyleService((id, player, name) ->
                "<bold><aqua> · [Класс]</aqua></bold> " + name + " ", fresh(dir), LOOKUP);
        String result = styles.compose("Имя", "Имя", List.of(C), "", "");
        assertEquals("Имя · [Класс]", text(result));
        assertTrue(result.contains("<aqua>"));
        assertTrue(result.contains("<bold>"));
        LabelStyleService legacy = new LabelStyleService((id, player, name) ->
                "&a[КЛ] &r" + name, fresh(dir.resolve("legacy")), LOOKUP);
        assertEquals("Имя · [КЛ]", text(legacy.compose("Имя", "Имя", List.of(C), "", "")));
    }

    @Test
    void gradientAroundNameCannotLeakAnchorAcrossComponents(@TempDir Path dir) {
        LabelStyleService styles = new LabelStyleService((id, player, name) ->
                "<gradient:#ff0000:#0000ff>" + name + " · Звание</gradient>", fresh(dir), LOOKUP);
        String result = styles.compose("Имя", "Имя", List.of(C), "", "");
        assertEquals("Имя · Звание", text(result));
        assertFalse(result.contains("ANCHOR"));
    }

    @Test
    void invalidDuplicateOrEmptyProvidersCannotReplaceName(@TempDir Path dir) {
        for (LabelStyleService.TagRenderer renderer : List.<LabelStyleService.TagRenderer>of(
                (id, player, name) -> null, (id, player, name) -> "замена имени",
                (id, player, name) -> name + name, (id, player, name) -> "<bold>" + name + "</bold>")) {
            LabelStyleService styles = new LabelStyleService(renderer, fresh(dir), LOOKUP);
            assertEquals("Имя", text(styles.compose("Имя", "Имя", List.of(C), "", "")));
        }
    }

    @Test
    void rankDowngradeAndReloadChangeCountWithoutLosingPreferences(@TempDir Path dir) {
        LabelPreferences prefs = fresh(dir);
        int[] level = {5};
        LabelStyleService styles = new LabelStyleService(LabelStyleServiceTest::tag, prefs,
                new LabelStyleService.RankLookup() {
                    public LabelStyleService.Rank view(String player) {
                        return new LabelStyleService.Rank(level[0], "✦", "<gold>%name%</gold>", "Ранг", true);
                    }
                    public List<String> registrationOrder() { return List.of(); }
                });
        prefs.setOrder("Имя", List.of(R, G, C, LabelPreferences.Label.ACHIEVEMENTS));
        var order = styles.order("Имя");
        assertEquals(4, styles.selection("Имя").shown().size());
        level[0] = 1;
        assertEquals("Имя · ✦ Ранг · [ГИ]", text(styles.decorate("Имя", "Имя", "")));
        assertEquals(2, styles.selection("Имя").overflow().size());
        assertEquals(order, styles.order("Имя"));
        styles.applyLimits(new LabelLimits(List.of(2), List.of(3)));
        assertEquals(3, styles.selection("Имя").shown().size());
        styles.applyLimits(null);
        assertEquals(2, styles.selection("Имя").shown().size());
        level[0] = 5;
        assertEquals(4, styles.selection("Имя").shown().size());
    }

    @Test
    void duplicateSourcesCannotConsumeExtraSlots() {
        var result = plain().select("Имя", List.of(G, G, C), "", "", Set.of(), 2);
        assertEquals(List.of(G, C), result.shown().stream().map(LabelStyleService.Decoration::source).toList());
    }

    @Test
    void customColorBeatsRankColor() {
        LabelStyleService.Rank rank =
                new LabelStyleService.Rank(1, "✦", "<gray>%name%</gray>", "Странник", false);
        assertEquals("<aqua>Имя</aqua>",
                LabelStyleService.colorize("Имя", "<aqua>%name%</aqua>", rank));
        assertEquals("<gray>Имя</gray>", LabelStyleService.colorize("Имя", "", rank));
        assertEquals("Имя", LabelStyleService.colorize("Имя", null, null));
    }

    @Test
    void rankNameFollowsCustomColorToo() {
        LabelStyleService.Rank rank =
                new LabelStyleService.Rank(1, "✦", "<gray>%name%</gray>", "Странник", true);
        assertEquals("<aqua>Странник</aqua>",
                LabelStyleService.rankName(rank, "<aqua>%name%</aqua>"));
        assertEquals("<gray>Странник</gray>", LabelStyleService.rankName(rank, null));
        assertEquals("", LabelStyleService.rankName(null, null));
    }

    @Test
    void defaultOrderIsStableAndRankHasPriority(@TempDir Path dir) {
        assertEquals(List.of(R, C, G, LabelPreferences.Label.TREASURES,
                LabelPreferences.Label.TIERFORGE, LabelPreferences.Label.STREAMS,
                LabelPreferences.Label.QUESTS, LabelPreferences.Label.ACHIEVEMENTS),
                with(fresh(dir)).defaultOrder());
    }

    @Test
    void savedOrderKeepsRankWherePlayerPutIt(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        LabelStyleService styles = with(preferences);
        // Полный порядок, в котором ранг стоит вторым — не там, где по умолчанию.
        List<LabelPreferences.Label> arranged = new ArrayList<>(styles.defaultOrder());
        arranged.remove(R);
        arranged.add(1, R);
        preferences.setOrder("Steve", arranged);

        List<LabelPreferences.Label> order = styles.order("Steve");
        assertEquals(arranged, order, "сохранённый порядок не переставляется");
        assertEquals(R, order.get(1), "ранг остался там, куда его поставил игрок");
        assertEquals(LabelPreferences.Label.nameTags().size(), order.size(),
                "недостающие метки не потерялись");
    }

    @Test
    void newLabelsAreAppendedWithoutChangingSavedPriority(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        // Порядок настроен до появления новой метки (например, стримера).
        preferences.setOrder("Steve", List.of(G, R));
        List<LabelPreferences.Label> order = with(preferences).order("Steve");
        assertEquals(List.of(G, R), order.subList(0, 2));
        assertTrue(order.indexOf(LabelPreferences.Label.STREAMS) > order.indexOf(R));
    }

    @Test
    void moveSwapsNeighboursAndStopsAtEdges(@TempDir Path dir) {
        LabelStyleService styles = with(fresh(dir));
        assertTrue(styles.move("Steve", R, 1), "ранг можно сдвинуть правее");

        List<LabelPreferences.Label> order = styles.order("Steve");
        assertEquals(R, order.get(1), "ранг сдвинулся на одну позицию");
        assertFalse(styles.move("Steve", order.get(0), -1), "левее первой некуда");
        assertFalse(styles.move("Steve", order.get(order.size() - 1), 1), "правее последней некуда");
        assertFalse(styles.move("Steve", null, 1));
        assertFalse(styles.move("Steve", R, 0));
    }

    @Test
    void resetOrderReturnsDefault(@TempDir Path dir) {
        LabelStyleService styles = with(fresh(dir));
        styles.move("Steve", R, 1);
        styles.resetOrder("Steve");
        assertEquals(styles.defaultOrder(), styles.order("Steve"));
    }

    @Test
    void colorFormatChecksAccess(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        LabelStyleService styles = with(preferences);
        LabelPalette palette = LabelPalette.of(true, List.of(
                new LabelPalette.Color("aqua", "Бирюзовый", "<aqua>%name%</aqua>", 1, "")));

        assertEquals("", styles.colorFormat("Steve", palette, 1, Set.of()),
                "цвет не выбран — пусто");
        preferences.setColor("Steve", "aqua");
        assertEquals("<aqua>%name%</aqua>", styles.colorFormat("Steve", palette, 1, Set.of()));
        assertEquals("", styles.colorFormat("Steve", palette, 0, Set.of()),
                "ранг кончился — цвет не применяется");
        assertEquals("", styles.colorFormat("Steve", LabelPalette.disabled(), 5, Set.of()),
                "выключенная палитра не красит");
        preferences.setColor("Steve", "нет-такого");
        assertEquals("", styles.colorFormat("Steve", palette, 5, Set.of()),
                "незнакомый ид из старого файла ничего не ломает");
    }
}
