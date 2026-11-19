package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
