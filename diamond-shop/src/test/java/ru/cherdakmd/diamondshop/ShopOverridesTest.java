package ru.cherdakmd.diamondshop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Правки каталога вне config.yml: валидация, удаление, восстановление и JSON. */
final class ShopOverridesTest {

    private static final Logger LOG = Logger.getLogger("shop-overrides-test");

    private static ShopConfig.ShopItem stone() {
        return new ShopConfig.ShopItem("STONE", "blocks", 0.05, 8000,
                0.25, 4.0, true, 4);
    }

    private static ShopConfig.ShopItem shell() {
        return new ShopConfig.ShopItem("SHULKER_SHELL", "mobs", 25.0, 300,
                0.25, 4.0, true, 6);
    }

    @Test
    void setChangesOnlyTheRequestedField() {
        ShopOverrides overrides = ShopOverrides.EMPTY.copy();
        assertTrue(overrides.set("stone", "base-price", "1.5"));
        ShopConfig.ShopItem changed = overrides.apply(List.of(stone()), Set.of()).get(0);
        assertEquals(1.5d, changed.basePrice());
        assertEquals(8000, changed.refStock());
        assertEquals("blocks", changed.category());
        assertEquals(4d, changed.purchaseMultiplier());
        assertTrue(changed.sellEnabled());
    }

    @Test
    void badValuesAreRejectedAndNeverStored() {
        ShopOverrides overrides = ShopOverrides.EMPTY.copy();
        assertFalse(overrides.set("STONE", "base-price", "не-число"));
        assertFalse(overrides.set("STONE", "base-price", "-4"));
        assertFalse(overrides.set("STONE", "category", "призрак"));
        assertFalse(overrides.set("STONE", "unknown-field", "1"));
        assertFalse(overrides.set("STONE", "sell-enabled", "может быть"));
        assertTrue(overrides.changes().isEmpty());
    }

    @Test
    void removeRestoreAndRetiredMaterials() {
        ShopOverrides overrides = ShopOverrides.EMPTY.copy();
        overrides.remove("shulker_shell");
        assertTrue(overrides.isRemoved("SHULKER_SHELL"));
        assertEquals(1, overrides.apply(List.of(stone(), shell()), Set.of()).size());

        // Строка осталась в config.yml владельца, но retiredMaterials всё равно убирает её.
        overrides.restore("SHULKER_SHELL");
        assertEquals(0, overrides.apply(List.of(stone(), shell()),
                Set.of("SHULKER_SHELL")).stream()
                .filter(item -> item.material().equals("SHULKER_SHELL")).count());

        overrides.set("STONE", "sell-enabled", "false");
        overrides.restore("STONE");
        assertTrue(overrides.changes().isEmpty());
        assertEquals(2, overrides.apply(List.of(stone(), shell()), Set.of()).size());
    }

    @Test
    void copyIsIndependent() {
        ShopOverrides original = ShopOverrides.EMPTY.copy();
        ShopOverrides draft = original.copy();
        draft.remove("STONE");
        assertTrue(original.changes().isEmpty());
        assertFalse(original.isRemoved("STONE"));
        assertTrue(draft.isRemoved("STONE"));

        ShopOverrides target = ShopOverrides.EMPTY.copy();
        target.replaceWith(draft);
        assertTrue(target.isRemoved("STONE"));
    }

    @Test
    void jsonRoundTripKeepsEveryField(@TempDir Path dir) throws Exception {
        ShopOverrides overrides = ShopOverrides.load(dir, LOG);
        overrides.remove("SHULKER_SHELL");
        assertTrue(overrides.set("STONE", "base-price", "2.5"));
        assertTrue(overrides.set("STONE", "ref-stock", "9000"));
        assertTrue(overrides.set("STONE", "min-multiplier", "0.3"));
        assertTrue(overrides.set("STONE", "max-multiplier", "3"));
        assertTrue(overrides.set("STONE", "sell-enabled", "false"));
        assertTrue(overrides.set("STONE", "purchase-multiplier", "5"));
        assertTrue(overrides.set("STONE", "category", "minerals"));
        overrides.save();

        Path file = dir.resolve("shop-overrides.json");
        assertTrue(Files.isRegularFile(file));
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("\"schema\": 1"));

        ShopOverrides reloaded = ShopOverrides.load(dir, LOG);
        assertTrue(reloaded.isRemoved("SHULKER_SHELL"));
        ShopConfig.ShopItem item = reloaded.apply(List.of(stone(), shell()), Set.of()).get(0);
        assertEquals(2.5d, item.basePrice());
        assertEquals(9000, item.refStock());
        assertEquals(0.3d, item.minMultiplier());
        assertEquals(3d, item.maxMultiplier());
        assertFalse(item.sellEnabled());
        assertEquals(5d, item.purchaseMultiplier());
        assertEquals("minerals", item.category());
    }

    @Test
    void corruptOrForeignFileNeverKillsTheCatalog(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("shop-overrides.json");
        Files.writeString(file, "{ broken json", StandardCharsets.UTF_8);
        ShopOverrides broken = ShopOverrides.load(dir, LOG);
        assertTrue(broken.changes().isEmpty());
        assertEquals(2, broken.apply(List.of(stone(), shell()), Set.of()).size());

        Files.writeString(file,
                "{\"schema\":1,\"changes\":{\"STONE\":{\"alien-field\":1}}}",
                StandardCharsets.UTF_8);
        ShopOverrides foreign = ShopOverrides.load(dir, LOG);
        assertTrue(foreign.changes().isEmpty());
        assertNotNull(foreign.apply(List.of(stone()), Set.of()));
    }

    @Test
    void duplicateFieldsAreRejected(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("shop-overrides.json");
        Files.writeString(file,
                "{\"schema\":1,\"changes\":{\"STONE\":{\"base-price\":1,\"base-price\":2}}}",
                StandardCharsets.UTF_8);
        ShopOverrides overrides = ShopOverrides.load(dir, LOG);
        assertTrue(overrides.changes().isEmpty(), "дубликат поля в финансовом JSON запрещён");
    }
}
