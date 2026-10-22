package ru.cherdakmd.chrdktreasures;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Список «что именно выпало»: имена, обрезка и журнальная строка. Без сервера. */
class TreasureLootTextTest {

    private static TreasureLoot.LootStack stack(String material, int amount) {
        return new TreasureLoot.LootStack(material, amount);
    }

    private static TreasureLoot.LootStack named(String material, int amount, String name) {
        return new TreasureLoot.LootStack(material, amount, name);
    }

    @Test
    void ordinaryItemsUseClientLanguageTag() {
        TreasureLootText.Summary summary = TreasureLootText.summarize(
                List.of(stack("DIAMOND", 3), stack("COAL", 8)), 6);
        assertEquals(2, summary.parts().size());
        assertEquals(0, summary.hidden());
        TreasureLootText.Part diamond = summary.parts().get(0);
        assertTrue(diamond.markup().startsWith("<lang:"), diamond.markup());
        assertTrue(diamond.markup().contains("minecraft.diamond"), diamond.markup());
        assertEquals("DIAMOND", diamond.plain(), "в лог идёт материал, а не тег");
        assertEquals(3, diamond.amount());
    }

    @Test
    void namedFindKeepsItsOwnNameAndEscapesMarkup() {
        TreasureLootText.Part part = TreasureLootText.part(
                named("MUSHROOM_STEW", 1, "Кровавый <суп>"));
        assertEquals("Кровавый \\<суп>", part.markup(), "разметка из имени экранируется");
        assertEquals("Кровавый <суп>", part.plain(), "в лог — живой текст");
        assertEquals(1, part.amount());
    }

    @Test
    void longListIsTrimmedToLimit() {
        List<TreasureLoot.LootStack> stacks = List.of(
                stack("COAL", 4), stack("IRON_INGOT", 2), stack("GOLD_INGOT", 1),
                stack("DIAMOND", 3), stack("EMERALD", 2), stack("REDSTONE", 16),
                stack("LAPIS_LAZULI", 8));
        TreasureLootText.Summary summary = TreasureLootText.summarize(stacks, 3);
        assertEquals(3, summary.parts().size());
        assertEquals(4, summary.hidden(), "остальные сворачиваются в «и ещё N»");
        assertEquals("COAL ×4", summary.plain().substring(0, 7));
        assertTrue(summary.plain().endsWith("(+4)"), summary.plain());
    }

    @Test
    void limitIsClampedAndZeroMeansOne() {
        List<TreasureLoot.LootStack> stacks = List.of(stack("COAL", 1), stack("STONE", 2));
        assertEquals(1, TreasureLootText.summarize(stacks, 0).parts().size());
        assertEquals(2, TreasureLootText.summarize(stacks, 99).parts().size(),
                "больше " + TreasureLootText.MAX_LIMIT + " показать нельзя");
        assertEquals(1, TreasureLootText.summarize(stacks, -5).parts().size());
    }

    @Test
    void emptyAndBrokenStacksAreIgnored() {
        assertTrue(TreasureLootText.summarize(List.of(), 6).isEmpty());
        assertTrue(TreasureLootText.summarize(null, 6).isEmpty());
        assertTrue(TreasureLootText.summarize(
                List.of(stack("COAL", 0), stack("STONE", -2)), 6).isEmpty(),
                "нулевые и отрицательные стеки не показываются");
        assertTrue(TreasureLootText.summarize(
                List.of(stack("COAL", 0)), 6).isEmpty(), "нулевой стек не показывается");
    }

    @Test
    void plainSummarySeparatesPartsWithCommas() {
        TreasureLootText.Summary summary = TreasureLootText.summarize(
                List.of(stack("DIAMOND", 2), stack("COAL", 5)), 6);
        assertEquals("DIAMOND ×2, COAL ×5", summary.plain());
    }

    @Test
    void unknownMaterialFallsBackToItsName() {
        String tag = TreasureLootText.languageTag("НЕ_МАТЕРИАЛ");
        assertEquals("НЕ_МАТЕРИАЛ", tag, "неизвестный материал показывается как есть");
        assertEquals("", TreasureLootText.languageTag(null));
        assertEquals("", TreasureLootText.languageTag("  "));
    }

    @Test
    void realMaterialAlwaysGetsValidMiniMessageTag() {
        String tag = TreasureLootText.languageTag("DIAMOND");
        assertTrue(tag.startsWith("<lang:") && tag.endsWith(">"), tag);
        assertTrue(tag.contains("item.minecraft.diamond") || tag.contains("block.minecraft.diamond"),
                tag);
    }
}
