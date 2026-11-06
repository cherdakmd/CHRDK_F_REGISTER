package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Регрессия «кривого меню» со скриншота: раньше 10 навыков ложились рядами
 * 5/3/2, 12 перков — 7/5, и половина окна на 54 слота пустовала.
 *
 * <p>Проверяем исходник, потому что {@code Inventory} требует живого сервера:
 * наборы слотов должны быть симметричными, без дублей и без захода в верхний
 * ряд вкладок, а пустые ячейки закрываются рамкой.</p>
 */
class SkillsLayoutTest {

    private static String source() throws IOException {
        return Files.readString(
                Path.of("src/main/java/ru/cherdakmd/chrdkskills/SkillsGui.java"),
                StandardCharsets.UTF_8);
    }

    private static int[] slots(String source, String name) {
        Matcher matcher = Pattern.compile(name + "\\s*=\\s*\\{([^}]*)}").matcher(source);
        assertTrue(matcher.find(), "нет массива слотов " + name);
        String[] parts = matcher.group(1).split(",");
        return Arrays.stream(parts)
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .mapToInt(Integer::parseInt)
                .toArray();
    }

    private static void assertSymmetric(String name, int[] slots, int rows, int perRow) {
        assertEquals(rows * perRow, slots.length, name + ": всего слотов");
        Set<Integer> unique = new HashSet<>();
        for (int slot : slots) {
            assertTrue(slot >= 9 && slot < 54, name + ": слот " + slot + " вне области 9..53");
            assertTrue(unique.add(slot), name + ": дубль слота " + slot);
        }
        for (int row = 0; row < rows; row++) {
            final int target = row;
            assertEquals(perRow, Arrays.stream(slots).filter(slot -> slot / 9 == 1 + target).count(),
                    name + ": ряд " + row + " должен содержать ровно " + perRow + " иконок");
        }
    }

    @Test
    void skillsAndTopUseFiveByFivePerksUseSixBySix() throws IOException {
        String source = source();
        assertSymmetric("SKILL_SLOTS", slots(source, "SKILL_SLOTS"), 2, 5);
        assertSymmetric("TOP_SLOTS", slots(source, "TOP_SLOTS"), 2, 5);
        assertSymmetric("PERK_SLOTS", slots(source, "PERK_SLOTS"), 2, 6);
    }

    @Test
    void prestigeTabIsWiredIntoTheSameInventory() throws IOException {
        String source = source();
        assertTrue(source.contains("TAB_PRESTIGE = 5"));
        assertTrue(source.contains("case PRESTIGE -> renderPrestige"));
        assertTrue(source.contains("Tab.PRESTIGE"));
        String api = Files.readString(
                Path.of("src/main/java/ru/cherdakmd/chrdkskills/SkillsMenuApi.java"),
                StandardCharsets.UTF_8);
        assertTrue(api.contains("PRESTIGE"), "вкладка должна быть в публичном контракте");
    }

    @Test
    void emptySlotsAreFilledAndDetailButtonsAreCentred() throws IOException {
        String source = source();
        assertTrue(source.contains("fillEmpty(inventory);"),
                "рамка должна вызываться после отрисовки вкладки");
        assertTrue(source.contains("GRAY_STAINED_GLASS_PANE"));
        assertTrue(source.contains("DETAIL_ABILITY = 31"),
                "одиночная иконка приёма должна быть в центре ряда, а не в слоте 28");

        for (int[] array : new int[][]{
                slots(source, "SKILL_SLOTS"), slots(source, "TOP_SLOTS"),
                slots(source, "PERK_SLOTS")}) {
            for (int slot : array) {
                assertTrue(slot != 45 && slot != 49,
                        "сетка не должна перекрывать «Назад» и сводку");
            }
        }
    }
}
