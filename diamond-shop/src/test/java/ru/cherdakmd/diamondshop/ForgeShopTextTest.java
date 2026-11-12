package ru.cherdakmd.diamondshop;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ForgeMarketCatalog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * В /shop forge подсказка раньше показывала только «SKU: repair» и цену — игрок
 * не понимал, зачем нужен «Кузнечный лом». Тест следит, что у каждого из восьми
 * whitelist-SKU есть русское имя и две строки назначения (что делает + как
 * применять в наковальне).
 */
class ForgeShopTextTest {

    private static final Pattern CYRILLIC = Pattern.compile("[А-Яа-яЁё]");

    private static List<String> lines() throws Exception {
        return Files.readAllLines(Path.of("src/main/resources/messages.yml"),
                StandardCharsets.UTF_8);
    }

    private static Map<String, String> names() throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        boolean inside = false;
        for (String line : lines()) {
            if (line.equals("    name:")) {
                inside = true;
                continue;
            }
            if (inside && line.startsWith("    ") && !line.startsWith("      ")) {
                break;
            }
            if (!inside || !line.startsWith("      ")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String sku = line.substring(6, colon).trim();
            String value = line.substring(colon + 1).trim().replace("'", "");
            result.put(sku, value);
        }
        return result;
    }

    private static Map<String, List<String>> purposes() throws Exception {
        Map<String, List<String>> result = new LinkedHashMap<>();
        boolean inside = false;
        String current = null;
        for (String line : lines()) {
            if (line.equals("    purpose:")) {
                inside = true;
                continue;
            }
            if (inside && line.startsWith("    ") && !line.startsWith("      ")) {
                break;
            }
            if (!inside) continue;
            if (line.startsWith("      ") && !line.startsWith("        ")) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                current = line.substring(6, colon).trim();
                result.put(current, new ArrayList<>());
            } else if (line.startsWith("        - ") && current != null) {
                result.get(current).add(line.substring(line.indexOf("- ") + 2).trim());
            }
        }
        return result;
    }

    @Test
    void everyForgeSkuHasRussianName() throws Exception {
        Map<String, String> names = names();
        assertEquals(8, ForgeMarketCatalog.SKUS.stream()
                .filter(sku -> !ForgeShopService.isPotionSku(sku.id())).count(),
                "в магазине остаются только восемь расходников Кузни");
        for (var sku : ForgeMarketCatalog.SKUS) {
            if (ForgeShopService.isPotionSku(sku.id())) continue;
            assertTrue(names.containsKey(sku.id()), "нет русского имени у SKU " + sku.id());
            String name = names.get(sku.id());
            assertFalse(name.isBlank(), "пустое имя у SKU " + sku.id());
            assertTrue(CYRILLIC.matcher(name).find(),
                    "имя SKU " + sku.id() + " не по-русски: " + name);
            assertFalse(name.equals(sku.id()), "имя SKU " + sku.id() + " осталось голым id");
        }
    }

    @Test
    void everyForgeSkuExplainsWhatItDoesAndHowToUseIt() throws Exception {
        Map<String, List<String>> purposes = purposes();
        for (var sku : ForgeMarketCatalog.SKUS) {
            if (ForgeShopService.isPotionSku(sku.id())) continue;
            assertTrue(purposes.containsKey(sku.id()), "нет назначения у SKU " + sku.id());
            List<String> description = purposes.get(sku.id());
            assertTrue(description.size() >= 2,
                    "у SKU " + sku.id() + " должно быть действие и способ применения");
            for (String line : description) {
                assertTrue(CYRILLIC.matcher(line).find(),
                        "строка назначения SKU " + sku.id() + " не по-русски");
            }
            assertTrue(description.stream().anyMatch(
                    line -> line.toLowerCase(Locale.ROOT).contains("наковальн")),
                    "у SKU " + sku.id() + " не сказано, как применять расходник");
        }
        assertTrue(purposes.get("repair").stream().anyMatch(line -> line.contains("ремонт")),
                "«Кузнечный лом» со скриншота обязан объяснять ремонт");
    }


    @Test
    void realMessagesLoaderResolvesNamesAndPurposes() throws Exception {
        Messages messages = Messages.from(
                Files.newInputStream(Path.of("src/main/resources/messages.yml")), null,
                Logger.getLogger("ForgeShopTextTest"));
        for (var sku : ForgeMarketCatalog.SKUS) {
            if (ForgeShopService.isPotionSku(sku.id())) {
                assertFalse(messages.has("forge.shop.name." + sku.id()));
                continue;
            }
            assertTrue(messages.has("forge.shop.name." + sku.id()),
                    "загрузчик не видит имя SKU " + sku.id());
            assertTrue(messages.has("forge.shop.purpose." + sku.id()),
                    "загрузчик не видит назначение SKU " + sku.id());
        }
        assertEquals("Кузнечный лом", ForgeShopText.name(messages, "repair"));
        assertEquals(2, ForgeShopText.purpose(messages, "repair").size());
        assertEquals("Кристалл возвышения", ForgeShopText.name(messages, "ascend"));
    }

    @Test
    void shopTemplatesUseHumanNameInsteadOfBareSku() throws Exception {
        String all = String.join("\n", lines());
        assertTrue(all.contains("Расходник: <aqua>%name%</aqua>"),
                "подсказка витрины должна называть предмет по-русски");
        assertTrue(all.contains("Товар: <aqua>%name%</aqua>"),
                "окно подтверждения должно называть предмет по-русски");
        assertTrue(all.contains("Куплено <aqua>%name%</aqua>"),
                "сообщение о покупке должно называть предмет по-русски");
        assertTrue(all.contains("forge.shop.details-hint") || all.contains("details-hint:"),
                "нужна ссылка на полную инструкцию");
    }
}
