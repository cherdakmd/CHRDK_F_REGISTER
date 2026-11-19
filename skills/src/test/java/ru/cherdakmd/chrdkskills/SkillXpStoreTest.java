package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Хранилище расходников кристаллов: журнал паузы и суточного счётчика.
 */
class SkillXpStoreTest {

    /**
     * Суточный счётчик — санитарный потолок файла. Писатель не должен за него
     * выходить: иначе после перезапуска load() отвергнет файл и закроет все
     * кристаллы с сообщением «хранилище повреждено».
     */
    @Test
    void writerStaysWithinSanityCapSoRestartKeepsCrystalsOpen(@TempDir Path dir) {
        UUID player = UUID.randomUUID();
        SkillXpStore store = new SkillXpStore(dir, java.util.logging.Logger.getLogger("SkillXpStoreTest"));
        for (int i = 0; i < 2_100; i++) {
            assertTrue(store.consume(player, 1_000L + i, 0L), "использование без паузы разрешено");
        }
        store.saveNow();
        SkillXpStore reopened = new SkillXpStore(dir, java.util.logging.Logger.getLogger("SkillXpStoreTest"));
        reopened.load();
        assertTrue(reopened.available(), "файл переживает перезапуск: расходники не закрыты");
        assertEquals(2_000, reopened.used(player), "счётчик ограничен потолком здравого смысла");
    }

    /**
     * Повреждённый журнал уходит в карантин, а не закрывает все кристаллы и
     * дроп навсегда: паузы и счётчик не стоят того, чтобы ломать расходники.
     */
    @Test
    void brokenFileIsQuarantinedAndCrystalsStayOpen(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("skill-consumables.json"), "{повреждённый json");
        SkillXpStore store = new SkillXpStore(dir, java.util.logging.Logger.getLogger("SkillXpStoreTest"));
        store.load();
        assertTrue(store.available(), "повреждённый журнал не закрывает расходники");
        assertTrue(Files.notExists(dir.resolve("skill-consumables.json")), "повреждённый файл убран с пути");
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("skill-consumables.json.broken-")),
                    "повреждённый файл сохранён для разбора");
        }
        assertTrue(store.consume(UUID.randomUUID(), 1_000L, 0L), "журнал продолжает работать после карантина");
    }
}
