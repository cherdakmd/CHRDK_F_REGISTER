package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** honors.json: шаблон по умолчанию, checksum и защита .broken. */
class HonorStoreTest {

    @TempDir Path temp;

    private HonorStore store() {
        return new HonorStore(temp, Logger.getAnonymousLogger());
    }

    @Test
    void missingFileStartsWithCitrusTemplate() {
        HonorLedger ledger = store().load();
        assertEquals(1, ledger.definitions().size());
        assertEquals("old-citrus", ledger.definitions().get(0).id());
        assertTrue(ledger.allGrants().isEmpty());
    }

    @Test
    void savedStateRoundTripsWithChecksum() throws Exception {
        HonorStore store = store();
        HonorLedger ledger = HonorLedger.withDefaults();
        ledger.grant(new HonorGrant("Steve", "", "old-citrus", "Owner", 1_000L, "За выслугу лет"));
        assertTrue(store.save(ledger));
        String text = Files.readString(store.file(), StandardCharsets.UTF_8);
        assertTrue(text.contains("\"checksum\""));
        assertTrue(text.contains("old-citrus"));

        HonorLedger restored = store.load();
        assertEquals(1, restored.definitions().size());
        assertEquals(1, restored.allGrants().size());
        assertEquals("За выслугу лет", restored.grantOf("Steve").reason());
        assertEquals(5, restored.grantLevel("Steve"));
    }

    @Test
    void manualEditGoesToBrokenAndTemplateIsRestored() throws Exception {
        HonorStore store = store();
        assertTrue(store.save(HonorLedger.withDefaults()));
        String text = Files.readString(store.file(), StandardCharsets.UTF_8);
        Files.writeString(store.file(), text.replace("Старый цитрус", "Подделка"), StandardCharsets.UTF_8);

        HonorLedger restored = store.load();
        assertEquals(1, restored.definitions().size());
        assertEquals("Старый цитрус", restored.definitions().get(0).name(),
                "подделанный файл не загружается");
        assertTrue(Files.exists(temp.resolve("honors.json.broken")),
                "оригинал сохранён для разбора");
    }

    @Test
    void missingFileSeedsStaveLimeButExistingFileWins() {
        HonorStore store = store();
        HonorConfig config = new HonorConfig(true, false, 160, HonorConfig.DEFAULT_REASON, true,
                java.util.List.of(new HonorConfig.InitialGrant("StaveLime", "old-citrus",
                        "За выслугу лет")));
        HonorLedger fresh = store.load(config);
        assertEquals(5, fresh.grantLevel("StaveLime"));

        assertTrue(store.save(HonorLedger.withDefaults()), "файл без выдач");
        HonorLedger existing = store.load(config);
        assertTrue(existing.allGrants().isEmpty(),
                "существующий honors.json не переписывается настройками конфига");
    }

    @Test
    void firstLoadReportsSeededGrantsThatAreSavedAndAudited() {
        HonorStore store = store();
        HonorConfig config = new HonorConfig(true, false, 160, HonorConfig.DEFAULT_REASON, true,
                java.util.List.of(new HonorConfig.InitialGrant("StaveLime", "old-citrus",
                        "За выслугу лет")));
        HonorStore.Load first = store.loadWithStatus(config);
        assertTrue(first.created(), "стартовое состояние ещё не на диске");
        assertEquals(1, first.seeded().size());
        assertEquals("StaveLime", first.seeded().get(0).player());
        assertTrue(store.save(first.ledger()), "плагин обязан сохранить сразу при старте");
        HonorStore.Load second = store.loadWithStatus(config);
        assertFalse(second.created(), "повторный старт не должен пересевать выдачи");
        assertTrue(second.seeded().isEmpty());
        assertEquals(first.seeded().get(0).grantedAt(),
                second.ledger().grantOf("StaveLime").grantedAt(),
                "публичная дата зафиксирована, а не перегенерирована");
    }

    @Test
    void legacyWithoutChecksumIsAccepted() throws Exception {
        HonorStore store = store();
        String legacy = "{\"version\":1,\"definitions\":[{\"id\":\"old-citrus\","
                + "\"name\":\"Старый цитрус\",\"format\":\"%name%\",\"grantLevel\":5,"
                + "\"listed\":true}],\"grants\":[]}";
        Files.writeString(store.file(), legacy, StandardCharsets.UTF_8);
        HonorLedger restored = store.load();
        assertEquals(1, restored.definitions().size());
        assertEquals(5, restored.definitions().get(0).grantLevel());
    }
}
