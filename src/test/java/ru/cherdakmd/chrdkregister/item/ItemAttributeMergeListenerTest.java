package ru.cherdakmd.chrdkregister.item;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Статические контракты интеграции: приоритеты, порядок регистрации и quote-refresh. */
class ItemAttributeMergeListenerTest {

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }

    @Test
    void listenerCapturesLowestMergesHighestAndCleansMonitor() throws IOException {
        String source = read("src/main/java/ru/cherdakmd/chrdkregister/item/ItemAttributeMergeListener.java");
        assertTrue(source.contains("implements Listener"));
        assertTrue(source.contains("WeakHashMap"));
        for (String event : List.of("PrepareAnvilEvent", "PrepareItemCraftEvent", "PrepareSmithingEvent")) {
            assertTrue(source.contains(event), event + " должен участвовать в слиянии");
        }
        assertTrue(source.contains("EventPriority.LOWEST"));
        assertTrue(source.contains("EventPriority.HIGHEST"));
        assertTrue(source.contains("EventPriority.MONITOR"));
        assertTrue(source.contains("ItemAttributeMerge.merge(result, sources, ownedNamespaces)"));
    }

    @Test
    void coreRegistersMergeBeforeAnvilCostQuote() throws IOException {
        String plugin = read("src/main/java/ru/cherdakmd/chrdkregister/RegisterPlugin.java");
        int merge = plugin.indexOf("new ItemAttributeMergeListener(this)");
        int cost = plugin.indexOf("new AnvilCostListener(this)");
        assertTrue(merge >= 0, "слушатель слияния зарегистрирован в ядре");
        assertTrue(cost > merge, "HIGHEST слияния должен пройти раньше запоминания цены");
    }

    @Test
    void enchantAnvilQuoteIsRefreshedAfterMerge() throws IOException {
        String source = read("enchants/src/main/java/ru/cherdakmd/chrdkenchants/AnvilApplyListener.java");
        assertTrue(source.contains("void refreshResult(PrepareAnvilEvent event)"));
        assertTrue(source.contains("@EventHandler(priority = EventPriority.MONITOR)"));
        assertTrue(source.contains("new AnvilQuotes.Quote<AnvilView, ItemStack>"));
        assertTrue(source.contains("result.clone()"));
    }

    @Test
    void utilityUsesNamespacedKeyAsPrimaryIdentity() throws IOException {
        String source = read("src/main/java/ru/cherdakmd/chrdkregister/item/ItemAttributeMerge.java");
        assertFalse(source.contains("getUniqueId("), "UUID не должен быть первичным идентификатором");
        assertTrue(source.contains("stableId(Attribute attribute"));
        assertTrue(source.contains("_compat_"));
        assertTrue(source.contains("setAttributeModifiers(null)"));
        assertTrue(source.contains("getAsComponentString()"),
                "скрытый пустой компонент определяется до/после снятия");
        assertFalse(source.contains("setAttributeModifiers(ImmutableMultimap.of())"),
                "пустая карта перекрывает ванильные defaults");
    }
}
