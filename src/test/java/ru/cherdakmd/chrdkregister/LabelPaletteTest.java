package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Палитра цветов ника (1.4.187): что открыто всем, что за ранг, что покупается
 * в каталоге, и как из этого собирается разметка ника.
 */
class LabelPaletteTest {

    private static final LabelPalette.Color FREE =
            new LabelPalette.Color("gray", "Серый", "<gray>%name%</gray>", 0, "");
    private static final LabelPalette.Color RANKED =
            new LabelPalette.Color("aqua", "Бирюзовый", "<aqua>%name%</aqua>", 2, "");
    private static final LabelPalette.Color CATALOG =
            new LabelPalette.Color("aurora", "Сияние", "<gradient:#00e5ff:#8a2be2>%name%</gradient>",
                    4, "nick-aurora");

    private static LabelPalette palette() {
        return LabelPalette.of(true, List.of(FREE, RANKED, CATALOG));
    }

    private static YamlConfiguration yaml(String text) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(text);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        return config;
    }

    @Test
    void freeColorIsOpenToEveryone() {
        assertTrue(palette().allowed(FREE, 0, Set.of()));
        assertTrue(palette().allowed(FREE, 5, Set.of()));
    }

    @Test
    void rankColorNeedsTheStep() {
        assertFalse(palette().allowed(RANKED, 0, Set.of()));
        assertFalse(palette().allowed(RANKED, 1, Set.of()));
        assertTrue(palette().allowed(RANKED, 2, Set.of()));
        assertTrue(palette().allowed(RANKED, 5, Set.of()));
    }

    @Test
    void catalogColorCanBeBoughtWithoutRank() {
        assertFalse(palette().allowed(CATALOG, 0, Set.of()));
        assertTrue(palette().allowed(CATALOG, 0, Set.of("nick-aurora")),
                "покупка открывает цвет без ступени");
        assertFalse(palette().allowed(CATALOG, 3, Set.of("другой-товар")),
                "чужой товар ничего не открывает");
        assertTrue(palette().allowed(CATALOG, 4, Set.of()),
                "с четвёртой ступени цвет открыт и так");
    }

    @Test
    void nullAndUnknownAreRejected() {
        assertFalse(palette().allowed(null, 5, Set.of()));
        assertFalse(palette().allowed(CATALOG, 5, null), "нет товаров — только ранг");
        assertNull(palette().byId(null));
        assertNull(palette().byId("  "));
        assertNull(palette().byId("нет"));
    }

    @Test
    void availableListKeepsConfigOrder() {
        List<LabelPalette.Color> colors = palette().available(0, Set.of("nick-aurora"));
        assertEquals(List.of(FREE, CATALOG), colors);
        assertEquals(List.of(FREE), palette().available(0, Set.of()));
        assertEquals(List.of(FREE, RANKED, CATALOG), palette().available(5, Set.of()));
    }

    @Test
    void lockReasonExplainsWhatIsMissing() {
        assertEquals(LabelPalette.Lock.CATALOG, LabelPalette.lockReason(CATALOG, Set.of()));
        assertEquals(LabelPalette.Lock.RANK, LabelPalette.lockReason(CATALOG, Set.of("nick-aurora")));
        assertEquals(LabelPalette.Lock.RANK, LabelPalette.lockReason(RANKED, Set.of()));
        assertEquals(LabelPalette.Lock.HIDDEN, LabelPalette.lockReason(null, Set.of()));
    }

    @Test
    void applySubstitutesNameOnly() {
        assertEquals("<aqua>Cherdak</aqua>", LabelPalette.apply(RANKED, "Cherdak"));
        assertEquals("<gray>Cherdak</gray>", LabelPalette.apply(FREE, "Cherdak"));
        assertEquals("Cherdak", LabelPalette.apply(null, "Cherdak"));
        assertEquals("", LabelPalette.apply(RANKED, null));
    }

    @Test
    void configParsesRangeAndSkipsGarbage() {
        LabelPalette parsed = LabelPalette.fromConfig(yaml("""
                labels:
                  colors:
                    enabled: true
                    list:
                      - {id: "gray", name: "Серый", format: "<gray>%name%</gray>", rank: 0}
                      - {id: "BAD ID", name: "Кривой"}
                      - {id: "gray", name: "Дубль"}
                      - {id: "aqua", name: "Бирюзовый", format: "<aqua>%name%</aqua>", rank: 2}
                      - {id: "aurora", name: "Сияние", rank: 4, product: "nick-aurora"}
                """));
        assertTrue(parsed.enabled());
        assertEquals(3, parsed.colors().size(), "мусор и дубли отброшены");
        assertEquals("gray", parsed.colors().get(0).id());
        assertEquals("aqua", parsed.colors().get(1).id());
        assertEquals("nick-aurora", parsed.colors().get(2).product());
        assertEquals("<white>%name%</white>", parsed.colors().get(2).format(),
                "без format ник остаётся белым, а не пропадает");
    }

    @Test
    void configCanDisableTheWholePalette() {
        LabelPalette parsed = LabelPalette.fromConfig(yaml("""
                labels:
                  colors:
                    enabled: false
                    list:
                      - {id: "gray", name: "Серый", format: "<gray>%name%</gray>"}
                """));
        assertFalse(parsed.enabled());
        assertEquals(1, parsed.colors().size(), "список читается, но меню его не показывает");
    }

    @Test
    void emptyConfigFallsBackToDefaults() {
        LabelPalette parsed = LabelPalette.fromConfig(yaml("general:\n  x: 1\n"));
        assertTrue(parsed.enabled(), "без секции палитра включена, но пуста");
        assertTrue(parsed.colors().isEmpty());
        assertNotNull(LabelPalette.fromConfig(null));
        assertFalse(LabelPalette.disabled().enabled());
    }
}
