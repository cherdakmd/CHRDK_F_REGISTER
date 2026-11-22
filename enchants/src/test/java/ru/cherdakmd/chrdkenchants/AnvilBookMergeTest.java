package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Совместимость кастомных и ванильных книг: книга+книга и книга+вещь дают
 * один предмет с обоими видами чар, пока этого не запрещает логика чар
 * (группы несовместимостей, потолки уровней, лимиты, применимость к вещам).
 */
class AnvilBookMergeTest {

    @Test
    void bookPairsOfEitherOrderEnterTheCustomPath() throws Exception {
        String anvil = source("AnvilApplyListener");
        assertTrue(anvil.contains("if (!ourPair(left, right))"),
                "наша книга участвует в паре независимо от стороны");
        assertTrue(anvil.contains("customBook(left) && right != null && right.getType() == Material.ENCHANTED_BOOK"),
                "наша книга слева + ванильная справа — тоже наше слияние");
        assertTrue(anvil.contains("private boolean transferBlocked(ItemStack left, ItemStack right)"));
        assertTrue(anvil.contains("return plugin.items().validated(left) == null"),
                "ванильная книга справа требует лишь валидной PDC слева");
        assertTrue(anvil.contains("data == null || data.isEmpty() || plugin.service().canApplyFromBook(left, right) != null"),
                "повреждённая или пустая наша книга не становится ванильной и не расходуется");
        assertTrue(anvil.contains("if (!plugin.items().read(right).isEmpty())"),
                "без кастомной части книги applyFromBook не вызывается");
        assertTrue(anvil.contains("plugin.items().write(result, before)"),
                "кастомные чары левой стороны восстанавливаются на результате");
        assertTrue(anvil.contains("if ((pureBook || (manualVanilla != null && manualVanilla.nativeCost() == 0)) && surcharge == 0)"),
                "слияние без единого усиления не выдаёт результат — как ванильная наковальня");
    }

    @Test
    void bookTargetsKeepEnchantLogicButSkipMaterialSupport() throws Exception {
        String service = source("EnchantService");
        assertTrue(service.contains("boolean bookTarget = item.getType() == Material.ENCHANTED_BOOK"));
        assertTrue(service.contains("if (!bookTarget && !def.supports(item.getType().name()))"),
                "книга хранит любые чары: применимость к материалу проверяется только у вещей");
        assertTrue(service.contains("bookTarget ? null : item.getType().name()"));
        assertTrue(service.contains("item.getType() == Material.BOOK || item.getType() == Material.AIR"),
                "простая книга по-прежнему не принимает чары");
        assertTrue(service.contains("EnchantConflicts.validSet(bookEnchants)"),
                "группы несовместимостей действуют и на книгах");
        assertTrue(service.contains("plugin.config().maxPerItem()"),
                "лимиты чар на книге совпадают с лимитами вещи — книга не становится мёртвым грузом");
    }

    @Test
    void vanillaMergeCombinesStoredEnchantsOntoBooks() throws Exception {
        String merge = source("VanillaBookMerge");
        assertTrue(merge.contains("boolean bookTarget = source.getType() == Material.ENCHANTED_BOOK"));
        assertTrue(merge.contains("storage.getStoredEnchants()"));
        assertTrue(merge.contains("storage.addStoredEnchant(enchantment, merged.get(enchantment), true)"),
                "надванильные уровни допустимы: потолок уже применён в merge");
        assertTrue(merge.contains("meta.addEnchant(enchantment, merged.get(enchantment), true)"),
                "перенос на вещь тоже принимает надванильные уровни");
        assertTrue(merge.contains("(!bookTarget && !enchantment.canEnchantItem(result))"),
                "книга принимает любые хранёные чары, вещь — только применимые");
        assertTrue(merge.contains("existing.conflictsWith(enchantment)"),
                "ванильные конфликты не пропускаются");
    }

    @Test
    void vanillaPreviewMustPreserveEveryExpectedEnchantAndLevel() throws Exception {
        assertTrue(VanillaBookMerge.preserves(Map.of("fortune", 3, "mending", 1), Map.of("fortune", 3, "mending", 1, "extra", 1)));
        assertFalse(VanillaBookMerge.preserves(Map.of("fortune", 3, "mending", 1), Map.of("fortune", 3)));
        assertFalse(VanillaBookMerge.preserves(Map.of("fortune", 3), Map.of("fortune", 2)));
        String listener = source("AnvilApplyListener");
        assertTrue(listener.contains("checkedVanilla == null"));
        assertTrue(listener.contains("!preservesVanillaBook(left, right, result)"));
        assertTrue(source("VanillaBookMerge").contains("if (existing == merged.get(enchantment)) continue;"));
    }

    @Test
    void containerMaterialSkipsSupportButKeepsLimits() {
        EnchantDef def = EnchantRegistry.byId("careful_work");
        assertNotNull(def);
        // Книга-контейнер: без материала чара допустима…
        assertTrue(EnchantRoller.applicable(List.of(def), null, Map.of(), 4, 1).contains(def));
        // …а с материалом книги применимость по-прежнему отсекается.
        assertTrue(EnchantRoller.applicable(List.of(def), "ENCHANTED_BOOK", Map.of(), 4, 1).isEmpty());
        assertFalse(def.supports("ENCHANTED_BOOK"));
        // Уникальность и лимиты на книге сохраняются.
        assertTrue(EnchantRoller.applicable(List.of(def), null, Map.of(def.id(), 1), 4, 1).isEmpty());
        assertTrue(EnchantRoller.applicable(List.of(def), null,
                Map.of("seer", 1, "scholar", 1, "garden_step", 1), 3, 1).isEmpty());
    }

    private String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/" + name + ".java"));
    }
}
