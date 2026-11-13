package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** RC16.1: display догоняет объединённый ядром стек; выдача — без служебного preview-лора. */
class ForgeAnvilPreviewTest {

    @Test void mergedResultMakesDisplayStaleUntilRefreshed() {
        var display=List.of("base");
        var merged=List.of("base","restored-mine-treasure-base");
        assertTrue(ForgeAnvilPreview.stale(display,merged), "HIGHEST-слияние атрибутов изменило результат — display обязан догнать");
        assertFalse(ForgeAnvilPreview.stale(merged,merged), "уже объединённый display не требует обновления");
        assertFalse(ForgeAnvilPreview.stale(display,null), "null-результат (нет превью) не перетирает display");
        assertFalse(ForgeAnvilPreview.stale(display,display));
    }

    @Test void giveStripsExactlyThePreviewTail() {
        assertEquals(List.of("tier","property"),ForgeAnvilPreview.strip(List.of("tier","property","Выбрано","Цена","ПКМ"),3));
        assertEquals(List.of(),ForgeAnvilPreview.strip(List.of("Выбрано","Цена"),2));
        assertEquals(List.of(),ForgeAnvilPreview.strip(List.of("tier"),3), "неполный лор не выдаётся вовсе — служебные строки не утекают");
        assertEquals(List.of("tier"),ForgeAnvilPreview.strip(List.of("tier"),0));
        assertEquals(List.of("tier"),ForgeAnvilPreview.strip(List.of("tier"),-1));
        assertNull(ForgeAnvilPreview.strip(null,2));
    }

    @Test void previewLineCountIsTakenFromMessagesNotHardcoded() throws Exception {
        String messages=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/messages.yml"));
        assertTrue(messages.contains("forge-anvil-preview:"));
        String listener=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/ru/cherdakmd/chrdktierforge/ForgeAnvilListener.java"));
        assertTrue(listener.contains("state.previewLines=preview.size()"), "размер preview берётся из конфигурации сообщений");
    }
}
