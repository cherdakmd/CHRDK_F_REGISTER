package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** RC16.1: забор предмета из наковальни после HIGHEST-слияния атрибутов ядром.
 *  Source-inspection: поведение листенера завязано на Bukkit-конвейер prepare. */
class ForgeAnvilTakeContractTest {

    private static String listener() throws Exception {
        return Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdktierforge/ForgeAnvilListener.java"));
    }

    @Test void displayChasesMergedCoreResultOnMonitor() throws Exception {
        String source=listener();
        int monitor=source.indexOf("priority=EventPriority.MONITOR");
        assertTrue(monitor>=0,"нужен MONITOR-обработчик после HIGHEST-слияния атрибутов ядром");
        assertTrue(source.contains("ForgeAnvilPreview.stale(state.display,event.getResult())"));
        assertTrue(source.contains("state.display=event.getResult().clone()"));
        // проверка current() остаётся строгой — её оживляет именно синхронизация display
        assertTrue(source.contains("Objects.equals(state.display,view.getTopInventory().getItem(2))"));
    }

    @Test void takeGivesShownResultWithoutPreviewLoreNotRawPlanResult() throws Exception {
        String source=listener();
        assertTrue(source.contains("ForgeAnvilPreview.give(state.display,state.previewLines)"));
        assertTrue(source.contains("player.setItemOnCursor(given)"));
        assertTrue(source.contains("plugin.collection().record(player,given)"));
        assertFalse(source.contains("setItemOnCursor(plan.result()"),
                "выдача plan.result() теряет атрибуты, восстановленные ядром в показанном результате");
        assertFalse(source.contains("record(player,plan.result())"));
    }

    @Test void previewTailLengthComesFromRenderedLore() throws Exception {
        String source=listener();
        assertTrue(source.contains("int previewLines"));
        assertTrue(source.contains("state.previewLines=preview.size()"));
    }
}
