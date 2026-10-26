package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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

    @Test
    void composePlacesFirstLabelOnTheLeft() {
        // guilds применён последним, значит оказался левее всех.
        assertEquals("[ГИ] [КЛ] Имя",
                plain().compose("Имя", "Имя", List.of(G, C, R), "", ""));
    }

    @Test
    void badgeSitsWherePlayerPutIt() {
        assertEquals("[ГИ] ✦ [КЛ] Имя",
                plain().compose("Имя", "Имя", List.of(G, R, C), "✦", ""));
        assertEquals("[ГИ] [КЛ] Имя",
                plain().compose("Имя", "Имя", List.of(G, C, R), "", ""));
    }

    @Test
    void hiddenLabelNeverAppears() {
        assertEquals("✦ [КЛ] Имя",
                plain().compose("Имя", "Имя", List.of(G, C, R), "✦", "", Set.of(G)),
                "скрытая гильдия выпала из строки");
    }

    @Test
    void unknownTagKeepsNameAsIs() {
        // Квестов в заглушке нет: метка пропускается, а имя остаётся.
        assertEquals("[ГИ] Имя",
                plain().compose("Имя", "Имя", List.of(LabelPreferences.Label.QUESTS, G), "", ""));
    }

    @Test
    void suffixGoesAfterEverything() {
        assertEquals("[ГИ] Имя <dark_gray>·</dark_gray> Странник",
                plain().compose("Имя", "Имя", List.of(G), "",
                        " <dark_gray>·</dark_gray> Странник"));
    }

    @Test
    void emptyOrderFallsBackToDeclarationOrder() {
        assertEquals("[КЛ] [ГИ] [ДО] Имя",
                plain().compose("Имя", "Имя", List.of(), "", ""),
                "пустой порядок — объявление, ранг без знака ничего не добавляет");
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
    void defaultOrderKeepsOldLookAndRankLast(@TempDir Path dir) {
        List<LabelPreferences.Label> order = with(fresh(dir)).defaultOrder();
        assertEquals(List.of(G, C, LabelPreferences.Label.ACHIEVEMENTS, R), order,
                "порядок регистрации перевёрнут, ранг — последним, ближе к нику");
        assertTrue(order.containsAll(LabelPreferences.Label.nameTags()),
                "все надписи на месте");
    }

    @Test
    void savedOrderKeepsRankWherePlayerPutIt(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        preferences.setOrder("Steve", List.of(G, R, C));
        List<LabelPreferences.Label> order = with(preferences).order("Steve");
        assertEquals(G, order.get(0));
        assertEquals(R, order.get(1));
        assertEquals(C, order.get(2));
        assertEquals(LabelPreferences.Label.nameTags().size(), order.size(),
                "недостающие метки не потерялись");
    }

    @Test
    void newLabelLandsBeforeRank(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        // Порядок настроен до появления новой метки (например, стримера).
        preferences.setOrder("Steve", List.of(G, R));
        List<LabelPreferences.Label> order = with(preferences).order("Steve");
        assertTrue(order.indexOf(LabelPreferences.Label.STREAMS) < order.indexOf(R),
                "новая метка встаёт перед рангом, а не после");
    }

    @Test
    void moveSwapsNeighboursAndStopsAtEdges(@TempDir Path dir) {
        LabelStyleService styles = with(fresh(dir));
        assertTrue(styles.move("Steve", R, -1), "ранг можно сдвинуть левее");

        List<LabelPreferences.Label> order = styles.order("Steve");
        assertEquals(R, order.get(order.size() - 2), "ранг сдвинулся на одну позицию");
        assertFalse(styles.move("Steve", order.get(0), -1), "левее первой некуда");
        assertFalse(styles.move("Steve", order.get(order.size() - 1), 1), "правее последней некуда");
        assertFalse(styles.move("Steve", null, 1));
        assertFalse(styles.move("Steve", R, 0));
    }

    @Test
    void resetOrderReturnsDefault(@TempDir Path dir) {
        LabelStyleService styles = with(fresh(dir));
        styles.move("Steve", R, -1);
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
