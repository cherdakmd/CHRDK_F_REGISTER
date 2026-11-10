package ru.cherdakmd.chrdkprofessions;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Регрессия печи: происхождение сырья не должно перезаписывать живой тайл.
 *
 * <p>Раньше {@code TileState#update} из FurnaceSmeltEvent и FurnaceExtractEvent
 * возвращал в печь снимок инвентаря, снятый до плавки: уголь сгорал, слиток
 * пропадал или появлялся повторно после выемки. Запись ушла в PDC чанка.</p>
 */
class ProfessionOriginsTest {

    @Test
    void stationRoundTripKeepsOwnerBudgetAndOutput() {
        ProfessionOrigins.Station station = new ProfessionOrigins.Station(
                "11111111-1111-1111-1111-111111111111", "RAW_IRON", 3,
                "22222222-2222-2222-2222-222222222222", "IRON_INGOT", 2);
        assertEquals(station, ProfessionOrigins.decode(ProfessionOrigins.encode(station)));
        assertEquals(ProfessionOrigins.Station.EMPTY,
                ProfessionOrigins.decode(ProfessionOrigins.encode(ProfessionOrigins.Station.EMPTY)));
    }

    @Test
    void brokenStationRecordIsRejectedInsteadOfRollingBackTheFurnace() {
        assertNull(ProfessionOrigins.decode(null));
        assertNull(ProfessionOrigins.decode("битая запись"));
        assertNull(ProfessionOrigins.decode("a|b|1|c|d"));
        assertNull(ProfessionOrigins.decode("a|b|1|c|d|e"));
        assertNull(ProfessionOrigins.decode("a|b|-1|c|d|0"));
        assertNull(ProfessionOrigins.decode("a|b|1|c|d|-2"));
        assertNull(ProfessionOrigins.decode("a|b|не число|c|d|0"));
    }

    @Test
    void furnaceEventsNeverUpdateTileState() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/ru/cherdakmd/chrdkprofessions/ProfessionOrigins.java"),
                StandardCharsets.UTF_8);
        assertFalse(source.contains("state.update("), "Снимок тайла нельзя записывать в живую печь");
        assertFalse(source.contains(".update(false"), "TileState#update перезаписывает инвентарь и burnTime");
        assertTrue(source.contains("getChunk().getPersistentDataContainer()"),
                "Происхождение должно жить в PDC чанка");
        assertTrue(source.contains("PersistentDataType.STRING"),
                "Станция пишется одной строкой, без snapshot тайла");
    }

    @Test
    void smeltAndExtractHandlersOnlyReadTheResult() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/ru/cherdakmd/chrdkprofessions/ProfessionListener.java"),
                StandardCharsets.UTF_8);
        assertFalse(source.contains("setSmelting("), "Опыт professions не меняет вход печи");
        assertFalse(source.contains("setFuel("), "Опыт professions не меняет топливо печи");
        assertFalse(source.contains("getInventory().setResult("), "Опыт professions не меняет результат печи");
    }
}
