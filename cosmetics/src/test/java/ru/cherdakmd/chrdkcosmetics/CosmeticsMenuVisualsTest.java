package ru.cherdakmd.chrdkcosmetics;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CosmeticsMenuVisualsTest {
    static java.util.List<CosmeticsDefs.TrailDef> stock() throws Exception {
        var yaml = new YamlConfiguration();
        try (var stream = CosmeticsMenuVisualsTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(stream); yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return CosmeticsDefs.parseTrails(yaml.getMapList("cosmetics.trail.defs"), true);
    }
    @Test void stockDefinitionsCoverAllStatesAndControls() throws Exception {
        var models = new HashSet<NamespacedKey>();
        var defs = stock(); assertEquals(7, defs.size());
        for (var def : defs) {
            assertTrue(models.add(CosmeticsMenuVisuals.trail(def, true, false, true)));
            assertTrue(models.add(CosmeticsMenuVisuals.trail(def, true, true, true)));
            assertTrue(models.add(CosmeticsMenuVisuals.trail(def, false, false, true)));
            assertTrue(models.add(CosmeticsMenuVisuals.trail(def, false, true, true)));
            assertTrue(models.add(CosmeticsMenuVisuals.trail(def, true, true, false)));
        }
        for (var control : CosmeticsMenuVisuals.Control.values()) assertTrue(models.add(control.model()));
        assertEquals(52, models.size());
        assertEquals("menu/trail/flame/selected", CosmeticsMenuVisuals.trail(defs.getFirst(), true, true, true).getKey());
        assertEquals("menu/trail/flame/suspended", CosmeticsMenuVisuals.trail(defs.getFirst(), false, true, true).getKey());
        assertEquals("menu/trail/flame/unavailable", CosmeticsMenuVisuals.trail(defs.getFirst(), true, true, false).getKey());
        assertEquals(CosmeticsMenuVisuals.Control.CLOSE, CosmeticsMenuVisuals.button("gui-close"));
        assertNull(CosmeticsMenuVisuals.button("other"));
    }
    @Test void changedOrCustomDefinitionsNeverReceiveStockModels() throws Exception {
        for (var d : stock()) {
            assertNull(CosmeticsMenuVisuals.trail(new CosmeticsDefs.TrailDef(d.id(), "custom", d.particle(), d.count(), d.spread(), d.speed(), d.product(), d.rank()), true, false, true));
            assertNull(CosmeticsMenuVisuals.trail(new CosmeticsDefs.TrailDef(d.id(), d.displayName(), "other", d.count(), d.spread(), d.speed(), d.product(), d.rank()), true, false, true));
            assertNull(CosmeticsMenuVisuals.trail(new CosmeticsDefs.TrailDef(d.id(), d.displayName(), d.particle(), d.count() + 1, d.spread(), d.speed(), d.product(), d.rank()), true, false, true));
            assertNull(CosmeticsMenuVisuals.trail(new CosmeticsDefs.TrailDef(d.id(), d.displayName(), d.particle(), d.count(), d.spread(), d.speed(), "other", d.rank()), true, false, true));
        }
        assertNull(CosmeticsMenuVisuals.trail(new CosmeticsDefs.TrailDef("custom", "custom", "flame", 1, 0, 0, "", 0), true, false, true));
        assertNull(CosmeticsMenuVisuals.trail(null, true, false, true));
    }
    @Test void disabledAndForeignMetadataAreNeverOverwritten() {
        var meta = mock(ItemMeta.class); var key = CosmeticsMenuVisuals.Control.CLOSE.model();
        CosmeticsMenuVisuals.apply(meta, key, false); verifyNoInteractions(meta);
        CosmeticsMenuVisuals.apply(null, key, true);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other", "model"));
        CosmeticsMenuVisuals.apply(meta, key, true); verify(meta, never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null); when(meta.hasCustomModelData()).thenReturn(true);
        CosmeticsMenuVisuals.apply(meta, key, true); verify(meta, never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false);
        CosmeticsMenuVisuals.apply(meta, key, true); verify(meta).setItemModel(key);
        verify(meta, never()).getPersistentDataContainer();
    }
}
