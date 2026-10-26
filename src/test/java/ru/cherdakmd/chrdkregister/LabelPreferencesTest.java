package ru.cherdakmd.chrdkregister;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Переключатели надписей `/labels` (1.4.183): по умолчанию видно всё, выбор
 * переживает перезаход и перезапуск, ник не зависит от регистра, битый файл не
 * роняет плагин.
 */
class LabelPreferencesTest {

    private static final Logger LOGGER = Logger.getLogger("LabelPreferencesTest");
    private static final Gson GSON = new GsonBuilder().create();

    private static LabelPreferences fresh(Path dir) {
        LabelPreferences preferences = new LabelPreferences(dir, LOGGER);
        preferences.load();
        return preferences;
    }

    @Test
    void everythingIsVisibleByDefault(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        for (LabelPreferences.Category category : LabelPreferences.Category.values()) {
            assertTrue(preferences.visible("Steve", category), "по умолчанию видно: " + category.id());
        }
        assertFalse(preferences.anyHidden("Steve"));
        assertFalse(Files.exists(preferences.file()), "пустая настройка не создаёт файл");
    }

    @Test
    void togglesAreIndependent(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        assertFalse(preferences.toggle("Steve", LabelPreferences.Category.RANK), "первое нажатие прячет");
        assertFalse(preferences.visible("Steve", LabelPreferences.Category.RANK));
        assertTrue(preferences.visible("Steve", LabelPreferences.Category.BADGES), "метки не задеты");
        assertTrue(preferences.visible("Steve", LabelPreferences.Category.HEADER), "шапка не задета");
        assertTrue(preferences.anyHidden("Steve"));

        assertTrue(preferences.toggle("Steve", LabelPreferences.Category.RANK), "второе возвращает");
        assertTrue(preferences.visible("Steve", LabelPreferences.Category.RANK));
        assertFalse(preferences.anyHidden("Steve"), "запись без скрытого удаляется из файла");
    }

    @Test
    void choiceSurvivesRestart(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        preferences.toggle("Cherdak", LabelPreferences.Category.RANK);
        preferences.toggle("Cherdak", LabelPreferences.Category.BADGES);
        preferences.saveNow();
        assertTrue(Files.exists(preferences.file()));

        LabelPreferences reloaded = fresh(dir);
        assertFalse(reloaded.visible("cherdak", LabelPreferences.Category.RANK));
        assertFalse(reloaded.visible("CHERDAK", LabelPreferences.Category.BADGES));
        assertTrue(reloaded.visible("cherdak", LabelPreferences.Category.HEADER));
        assertEquals(1, reloaded.size());
    }

    @Test
    void namesAreCaseInsensitiveAndTrimmed(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        preferences.toggle("  Steve_", LabelPreferences.Category.HEADER);
        assertFalse(preferences.visible("steve_", LabelPreferences.Category.HEADER));
        assertFalse(preferences.visible("STEVE_", LabelPreferences.Category.HEADER));
        assertEquals("steve_", LabelPreferences.key(" Steve_ "));
        assertEquals("", LabelPreferences.key(null));
    }

    @Test
    void setAllHidesAndRestoresEverything(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        assertFalse(preferences.setAll("Steve", false));
        for (LabelPreferences.Category category : LabelPreferences.Category.values()) {
            assertFalse(preferences.visible("Steve", category), "скрыто: " + category.id());
        }
        assertTrue(preferences.setAll("Steve", true));
        for (LabelPreferences.Category category : LabelPreferences.Category.values()) {
            assertTrue(preferences.visible("Steve", category), "видно: " + category.id());
        }
        assertFalse(preferences.anyHidden("Steve"));
    }

    @Test
    void resetReturnsToDefaults(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        preferences.toggle("Steve", LabelPreferences.Category.RANK);
        preferences.reset("Steve");
        assertTrue(preferences.visible("Steve", LabelPreferences.Category.RANK));
        assertEquals(0, preferences.size());
        // сброс того, у кого и так всё видно, — не ошибка
        preferences.reset("Steve");
        assertEquals(0, preferences.size());
    }

    @Test
    void snapshotHasReadableShape(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        preferences.toggle("Bob", LabelPreferences.Category.BADGES);
        preferences.toggle("alice", LabelPreferences.Category.HEADER);

        @SuppressWarnings("unchecked")
        Map<String, Object> root = GSON.fromJson(preferences.snapshotJson(), LinkedHashMap.class);
        assertEquals(1.0, ((Number) root.get("version")).doubleValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> players = (Map<String, Object>) root.get("players");
        assertEquals(List.of("alice", "bob"), List.copyOf(players.keySet()), "ключи отсортированы");
        @SuppressWarnings("unchecked")
        Map<String, Object> bob = (Map<String, Object>) players.get("bob");
        assertEquals(List.of("badges"), bob.get("hidden"));
    }

    @Test
    void unknownCategoryIdsAreIgnored(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("labels.json");
        Files.writeString(file, "{\"version\":1,\"players\":{\"steve\":"
                + "{\"hidden\":[\"rank\",\"hologram\",\"\"]}}}", StandardCharsets.UTF_8);
        LabelPreferences preferences = fresh(dir);
        assertFalse(preferences.visible("steve", LabelPreferences.Category.RANK));
        assertTrue(preferences.visible("steve", LabelPreferences.Category.BADGES),
                "неизвестный ид не прячет чужие категории");
        assertTrue(preferences.visible("steve", LabelPreferences.Category.HEADER));
    }

    @Test
    void brokenFileFallsBackToVisibleEverything(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("labels.json");
        Files.writeString(file, "{ это не json", StandardCharsets.UTF_8);
        LabelPreferences preferences = fresh(dir);
        assertTrue(preferences.visible("Steve", LabelPreferences.Category.RANK),
                "битый файл не прячет надписи");
        assertEquals(0, preferences.size());
    }

    @Test
    void emptyAndUnknownPlayersBehaveLikeDefaults(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        assertTrue(preferences.visible(null, LabelPreferences.Category.RANK));
        assertTrue(preferences.visible("nobody", LabelPreferences.Category.RANK));
        assertTrue(preferences.toggle("", LabelPreferences.Category.RANK), "пустой ник — без изменений");
        assertTrue(preferences.setAll("", false));
        assertEquals(0, preferences.size());
        assertNull(LabelPreferences.Category.byId("хедер"), "неизвестный ид — null");
    }
}
