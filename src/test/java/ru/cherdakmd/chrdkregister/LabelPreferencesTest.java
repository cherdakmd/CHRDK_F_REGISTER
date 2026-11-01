package ru.cherdakmd.chrdkregister;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Переключатели надписей {@code /labels} (1.4.186): каждый источник отдельно,
 * по умолчанию видно всё, выбор переживает перезапуск, ник не зависит от
 * регистра, старый файл 1.4.183 читается без потерь, битый — не роняет плагин.
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
        for (LabelPreferences.Label label : LabelPreferences.Label.values()) {
            assertTrue(preferences.visible("Steve", label), "по умолчанию видно: " + label.id());
        }
        assertFalse(preferences.anyHidden("Steve"));
        assertEquals(0, preferences.hiddenCount("Steve"));
        assertTrue(preferences.hiddenLabels("Steve").isEmpty());
        assertFalse(Files.exists(preferences.file()), "пустая настройка не создаёт файл");
    }

    @Test
    void eachSourceIsIndependent(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        assertFalse(preferences.toggle("Steve", LabelPreferences.Label.RANK), "первое нажатие прячет");
        assertFalse(preferences.visible("Steve", LabelPreferences.Label.RANK));
        // 1.4.186: раньше все метки прятались одной группой — теперь сосед не задет.
        assertTrue(preferences.visible("Steve", LabelPreferences.Label.QUESTS),
                "метка квестов не задета рангом");
        assertTrue(preferences.visible("Steve", LabelPreferences.Label.TIERFORGE));
        assertTrue(preferences.visible("Steve", LabelPreferences.Label.HEADER));
        assertTrue(preferences.anyHidden("Steve"));
        assertEquals(List.of(LabelPreferences.Label.RANK), preferences.hiddenLabels("Steve"));

        assertTrue(preferences.toggle("Steve", LabelPreferences.Label.RANK), "второе возвращает");
        assertFalse(preferences.anyHidden("Steve"), "запись без скрытого удаляется из файла");
    }

    @Test
    void everyBadgeHasItsOwnSwitch(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        preferences.set("Steve", LabelPreferences.Label.GUILDS, false);
        preferences.set("Steve", LabelPreferences.Label.ACHIEVEMENTS, false);
        assertEquals(List.of(LabelPreferences.Label.GUILDS, LabelPreferences.Label.ACHIEVEMENTS),
                preferences.hiddenLabels("Steve"), "порядок объявления, а не нажатий");
        assertTrue(preferences.visible("Steve", LabelPreferences.Label.CLASSES));
        assertEquals(2, preferences.hiddenCount("Steve"));
    }

    @Test
    void nameTagIdsMapStraightToSources(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        preferences.set("Steve", LabelPreferences.Label.ACHIEVEMENTS, false);
        // Аддоны регистрируют оформление под этими id — фильтр ищет источник по ним.
        assertFalse(preferences.visibleNameTag("Steve", "achievements"));
        assertTrue(preferences.visibleNameTag("Steve", "guilds"));
        assertTrue(preferences.visibleNameTag("Steve", "сторонний-аддон"),
                "незнакомый id не теряет метку");
        assertTrue(preferences.visibleNameTag("Steve", null));
        assertEquals(LabelPreferences.Label.QUESTS,
                LabelPreferences.Label.byNameTagId("QUESTS"), "регистр не важен");
        assertNull(LabelPreferences.Label.byNameTagId("header"),
                "шапка таба — не метка рядом с ником");
    }

    @Test
    void choiceSurvivesRestart(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        preferences.toggle("Cherdak", LabelPreferences.Label.RANK);
        preferences.toggle("Cherdak", LabelPreferences.Label.QUESTS);
        preferences.saveNow();
        assertTrue(Files.exists(preferences.file()));

        LabelPreferences reloaded = fresh(dir);
        assertFalse(reloaded.visible("cherdak", LabelPreferences.Label.RANK));
        assertFalse(reloaded.visible("CHERDAK", LabelPreferences.Label.QUESTS));
        assertTrue(reloaded.visible("cherdak", LabelPreferences.Label.GUILDS),
                "соседняя метка не пострадала");
        assertTrue(reloaded.visible("cherdak", LabelPreferences.Label.HEADER));
        assertEquals(1, reloaded.size());
    }

    @Test
    void namesAreCaseInsensitiveAndTrimmed(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        preferences.toggle("  Steve_", LabelPreferences.Label.HEADER);
        assertFalse(preferences.visible("steve_", LabelPreferences.Label.HEADER));
        assertFalse(preferences.visible("STEVE_", LabelPreferences.Label.HEADER));
        assertEquals("steve_", LabelPreferences.key(" Steve_ "));
        assertEquals("", LabelPreferences.key(null));
    }

    @Test
    void setAllHidesAndRestoresEverything(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        assertFalse(preferences.setAll("Steve", false));
        for (LabelPreferences.Label label : LabelPreferences.Label.values()) {
            assertFalse(preferences.visible("Steve", label), "скрыто: " + label.id());
        }
        assertEquals(LabelPreferences.Label.values().length, preferences.hiddenCount("Steve"));
        assertTrue(preferences.setAll("Steve", true));
        for (LabelPreferences.Label label : LabelPreferences.Label.values()) {
            assertTrue(preferences.visible("Steve", label), "видно: " + label.id());
        }
        assertFalse(preferences.anyHidden("Steve"));
    }

    @Test
    void badgeGroupTogglesOnlyAddonMarks(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        preferences.setGroup("Steve", LabelPreferences.badgeLabels(), false);
        assertTrue(preferences.visible("Steve", LabelPreferences.Label.RANK),
                "группа меток не трогает ранг");
        assertFalse(preferences.visible("Steve", LabelPreferences.Label.QUESTS));
        assertFalse(preferences.visible("Steve", LabelPreferences.Label.TIERFORGE));
        assertFalse(preferences.visible("Steve", LabelPreferences.Label.ACHIEVEMENTS));
        assertTrue(preferences.visible("Steve", LabelPreferences.Label.HEADER),
                "шапка таба — отдельный переключатель");
        assertEquals(6, LabelPreferences.badgeLabels().size(),
                "все NAME-надписи, кроме ранга");

        preferences.setGroup("Steve", LabelPreferences.badgeLabels(), true);
        assertFalse(preferences.anyHidden("Steve"));
    }

    @Test
    void resetReturnsToDefaults(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        preferences.toggle("Steve", LabelPreferences.Label.RANK);
        preferences.toggle("Steve", LabelPreferences.Label.CLASSES);
        preferences.reset("Steve");
        assertTrue(preferences.visible("Steve", LabelPreferences.Label.RANK));
        assertEquals(0, preferences.size());
        // сброс того, у кого и так всё видно, — не ошибка
        preferences.reset("Steve");
        assertEquals(0, preferences.size());
    }

    @Test
    void snapshotHasReadableShape(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        preferences.toggle("Bob", LabelPreferences.Label.QUESTS);
        preferences.toggle("alice", LabelPreferences.Label.HEADER);

        @SuppressWarnings("unchecked")
        Map<String, Object> root = GSON.fromJson(preferences.snapshotJson(), LinkedHashMap.class);
        assertEquals(4.0, ((Number) root.get("version")).doubleValue(),
                "1.4.191 пишет формат версии 4: источники, места, цвет, пресеты");
        @SuppressWarnings("unchecked")
        Map<String, Object> players = (Map<String, Object>) root.get("players");
        assertEquals(List.of("alice", "bob"), List.copyOf(players.keySet()), "ключи отсортированы");
        @SuppressWarnings("unchecked")
        Map<String, Object> bob = (Map<String, Object>) players.get("bob");
        assertEquals(List.of("quests"), bob.get("hidden"));
    }

    @Test
    void legacyBadgesGroupExpandsToEveryBadge(@TempDir Path dir) throws IOException {
        // Файл 1.4.183: три категории. «badges» прятал все метки аддонов сразу.
        Files.writeString(dir.resolve("labels.json"),
                "{\"version\":1,\"players\":{\"steve\":{\"hidden\":[\"rank\",\"badges\"]}}}",
                StandardCharsets.UTF_8);
        LabelPreferences preferences = fresh(dir);

        assertFalse(preferences.visible("steve", LabelPreferences.Label.RANK));
        for (LabelPreferences.Label label : LabelPreferences.badgeLabels()) {
            assertFalse(preferences.visible("steve", label),
                    "старая группа раскрылась: " + label.id());
        }
        assertTrue(preferences.visible("steve", LabelPreferences.Label.HEADER),
                "шапку игрок не прятал");

        // Первое изменение переписывает файл в новом формате — без потери выбора.
        preferences.set("steve", LabelPreferences.Label.GUILDS, true);
        String json = Files.readString(preferences.file(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"version\":4"), json);
        assertTrue(json.contains("\"rank\""), json);
        assertFalse(json.contains("\"badges\""), "старая группа больше не пишется");
    }

    @Test
    void unknownIdsAreIgnored(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("labels.json");
        Files.writeString(file, "{\"version\":2,\"players\":{\"steve\":"
                + "{\"hidden\":[\"rank\",\"hologram\",\"\",\"badge\"]}}}", StandardCharsets.UTF_8);
        LabelPreferences preferences = fresh(dir);
        assertFalse(preferences.visible("steve", LabelPreferences.Label.RANK));
        assertTrue(preferences.visible("steve", LabelPreferences.Label.QUESTS),
                "неизвестный ид не прячет чужие надписи");
        assertTrue(preferences.visible("steve", LabelPreferences.Label.HEADER));
    }

    @Test
    void orderIsStoredAndRestored(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        assertTrue(preferences.order("Steve").isEmpty(), "по умолчанию порядок «как задумано»");

        preferences.setOrder("Steve", List.of(LabelPreferences.Label.GUILDS,
                LabelPreferences.Label.RANK, LabelPreferences.Label.CLASSES));
        assertEquals(List.of(LabelPreferences.Label.GUILDS, LabelPreferences.Label.RANK,
                LabelPreferences.Label.CLASSES), preferences.order("Steve"));
        // Шапка таба — не метка рядом с ником: в порядок она не попадает.
        preferences.setOrder("Steve", List.of(LabelPreferences.Label.GUILDS,
                LabelPreferences.Label.HEADER, LabelPreferences.Label.RANK,
                LabelPreferences.Label.CLASSES));
        assertEquals(List.of(LabelPreferences.Label.GUILDS, LabelPreferences.Label.RANK,
                LabelPreferences.Label.CLASSES), preferences.order("Steve"),
                "шапка таба отброшена, порядок сохранён");
        preferences.setOrder("Steve", List.of(LabelPreferences.Label.GUILDS,
                LabelPreferences.Label.RANK, LabelPreferences.Label.CLASSES));
        preferences.saveNow();
        assertEquals(List.of(LabelPreferences.Label.GUILDS, LabelPreferences.Label.RANK,
                LabelPreferences.Label.CLASSES), fresh(dir).order("Steve"));
    }

    @Test
    void orderIgnoresJunkAndDuplicates(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        preferences.setOrder("Steve", Arrays.asList(LabelPreferences.Label.RANK,
                LabelPreferences.Label.RANK, LabelPreferences.Label.HEADER, null));
        assertEquals(List.of(LabelPreferences.Label.RANK), preferences.order("Steve"),
                "повтор и шапка таба отброшены");
    }

    @Test
    void colorIsStoredLowerCase(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        assertEquals("", preferences.color("Steve"));
        preferences.setColor("Steve", "  AQUA ");
        assertEquals("aqua", preferences.color("steve"));
        preferences.saveNow();
        assertEquals("aqua", fresh(dir).color("Steve"));
        preferences.setColor("Steve", "");
        assertEquals("", preferences.color("Steve"), "пустая строка снимает выбор");
    }

    @Test
    void presetsSaveApplyAndDelete(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        preferences.set("Steve", LabelPreferences.Label.RANK, false);
        preferences.setColor("Steve", "aqua");
        preferences.setOrder("Steve", List.of(LabelPreferences.Label.GUILDS));

        assertTrue(preferences.savePreset("Steve", "p1"));
        assertTrue(preferences.presets("Steve").containsKey("p1"));

        // Меняем вид и возвращаемся к сохранённому.
        preferences.set("Steve", LabelPreferences.Label.RANK, true);
        preferences.setColor("Steve", "");
        preferences.apply("Steve", preferences.presets("Steve").get("p1"));
        assertFalse(preferences.visible("Steve", LabelPreferences.Label.RANK));
        assertEquals("aqua", preferences.color("Steve"));
        assertEquals(List.of(LabelPreferences.Label.GUILDS), preferences.order("Steve"));

        preferences.saveNow();
        assertEquals(1, fresh(dir).presets("Steve").size(), "пресеты переживают перезапуск");

        assertTrue(preferences.deletePreset("Steve", "p1"));
        assertFalse(preferences.presets("Steve").containsKey("p1"));
        assertFalse(preferences.deletePreset("Steve", "p1"), "повторное удаление — false");
    }

    @Test
    void resetKeepsPresets(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        preferences.toggle("Steve", LabelPreferences.Label.RANK);
        preferences.savePreset("Steve", "p1");
        preferences.reset("Steve");
        assertTrue(preferences.visible("Steve", LabelPreferences.Label.RANK),
                "вид вернулся к обычному");
        assertEquals(1, preferences.presets("Steve").size(), "а пресеты остались");
    }

    @Test
    void versionThreeKeepsEverythingInFile(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        preferences.set("Steve", LabelPreferences.Label.GUILDS, false);
        preferences.setOrder("Steve", List.of(LabelPreferences.Label.RANK,
                LabelPreferences.Label.GUILDS));
        preferences.setColor("Steve", "aqua");
        preferences.savePreset("Steve", "p2");

        String json = preferences.snapshotJson();
        assertTrue(json.contains("\"version\":4"), json);
        assertTrue(json.contains("\"order\":["), json);
        assertTrue(json.contains("\"color\":\"aqua\""), json);
        assertTrue(json.contains("\"presets\":{"), json);
    }

    @Test
    void oldFilesWithoutNewFieldsStillRead(@TempDir Path dir) throws IOException {
        // Формат 1.4.186: только hidden, без порядка, цвета и пресетов.
        Files.writeString(dir.resolve("labels.json"),
                "{\"version\":2,\"players\":{\"steve\":{\"hidden\":[\"quests\"]}}}",
                StandardCharsets.UTF_8);
        LabelPreferences preferences = fresh(dir);
        assertFalse(preferences.visible("steve", LabelPreferences.Label.QUESTS));
        assertEquals("", preferences.color("steve"));
        assertEquals(List.of(), preferences.order("steve"));
        assertTrue(preferences.presets("steve").isEmpty());
    }

    @Test
    void brokenFileFallsBackToVisibleEverything(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("labels.json");
        Files.writeString(file, "{ это не json", StandardCharsets.UTF_8);
        LabelPreferences preferences = fresh(dir);
        assertTrue(preferences.visible("Steve", LabelPreferences.Label.RANK),
                "битый файл не прячет надписи");
        assertEquals(0, preferences.size());
    }

    @Test
    void emptyAndUnknownPlayersBehaveLikeDefaults(@TempDir Path dir) {
        LabelPreferences preferences = fresh(dir);
        assertTrue(preferences.visible(null, LabelPreferences.Label.RANK));
        assertTrue(preferences.visible("nobody", LabelPreferences.Label.RANK));
        assertEquals(0, preferences.hiddenCount(null));
        assertTrue(preferences.toggle("", LabelPreferences.Label.RANK),
                "пустой ник — без изменений");
        assertTrue(preferences.setAll("", false));
        assertEquals(0, preferences.size());
        assertNull(LabelPreferences.Label.byId("хедер"), "неизвестный ид — null");
        assertEquals(7, LabelPreferences.Label.nameTags().size(),
                "семь надписей рядом с ником + шапка таба отдельно");
    }
}
