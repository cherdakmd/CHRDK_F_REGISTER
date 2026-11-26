package ru.cherdakmd.chrdkgenerator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.Messages;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GeneratorCoreVisualsTest {
    @TempDir Path dir;
    private GeneratorStore open() {
        var store = new GeneratorStore(dir, Logger.getAnonymousLogger());
        store.load(); assertTrue(store.healthy()); return store;
    }
    @Test void optInNewCraftAndRestorationNeverRewriteExistingItems() {
        var meta = mock(ItemMeta.class);
        GeneratorCoreVisuals.applyNew(meta, false); verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other", "core"));
        GeneratorCoreVisuals.applyNew(meta, true); verify(meta, never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null); when(meta.hasCustomModelData()).thenReturn(true);
        GeneratorCoreVisuals.applyNew(meta, true); verify(meta, never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false); GeneratorCoreVisuals.applyNew(meta, true);
        verify(meta).setItemModel(GeneratorCoreVisuals.MODEL);
        for (boolean enabled : new boolean[]{false, true}) {
            var plugin = mock(GeneratorPlugin.class); when(plugin.namespace()).thenReturn("chrdk_generator");
            var config = new YamlConfiguration(); config.set("textures.core-items", enabled); when(plugin.getConfig()).thenReturn(config);
            var messages = mock(Messages.class); when(plugin.messages()).thenReturn(messages);
            when(messages.get(eq("core-item-name"), any(Object[].class))).thenReturn(Component.text("Core"));
            when(messages.getList(eq("core-item-lore"), any(Object[].class))).thenReturn(List.of(Component.text("Lore")));
            var itemMeta = mock(ItemMeta.class); var pdc = mock(PersistentDataContainer.class);
            when(itemMeta.getPersistentDataContainer()).thenReturn(pdc);
            var id = UUID.randomUUID();
            try (var items = mockConstruction(ItemStack.class, (item, context) -> {
                when(item.getItemMeta()).thenReturn(itemMeta); when(item.hasItemMeta()).thenReturn(true);
                when(item.getType()).thenReturn(Material.LODESTONE);
            })) {
                var codec = new GeneratorCoreItem(plugin); var crafted = codec.buildNew(id);
                verify(pdc).set(new NamespacedKey("chrdk_generator", "core"), PersistentDataType.STRING, "1");
                verify(pdc).set(new NamespacedKey("chrdk_generator", "core_id"), PersistentDataType.STRING, id.toString());
                if (enabled) verify(itemMeta).setItemModel(GeneratorCoreVisuals.MODEL);
                else verify(itemMeta, never()).setItemModel(any());
                verify(crafted).setItemMeta(itemMeta);
                var old = mock(ItemStack.class); when(old.getType()).thenReturn(Material.LODESTONE);
                when(old.hasItemMeta()).thenReturn(true); when(old.getItemMeta()).thenReturn(itemMeta);
                when(pdc.has(new NamespacedKey("chrdk_generator", "core"), PersistentDataType.STRING)).thenReturn(true);
                when(pdc.get(new NamespacedKey("chrdk_generator", "core_id"), PersistentDataType.STRING)).thenReturn(id.toString());
                assertEquals(id, codec.coreIdOf(old)); verify(old, never()).setItemMeta(any());
                when(itemMeta.getItemModel()).thenReturn(GeneratorCoreVisuals.MODEL);
                assertTrue(codec.hasOwnModel(old)); when(itemMeta.hasCustomModelData()).thenReturn(true);
                assertFalse(codec.hasOwnModel(old)); verify(old, never()).setItemMeta(any());
            }
        }
    }
    @Test void appearanceSurvivesRestartAndPendingReturnButLegacyRemainsUnmodelled() throws Exception {
        UUID owner = UUID.randomUUID(), core = UUID.randomUUID(), tx = UUID.randomUUID();
        var s = open(); s.markRegistered();
        s.install(owner, core, "world", 0, 64, 0, 1, 1, true); s.flush();
        assertTrue(open().entry(owner, 0).coreTextured());
        s = open(); assertTrue(s.prepareCoreTake(owner, 0, new GeneratorStore.CoreTake(tx, core, "before", "after")));
        s = open(); assertTrue(s.entry(owner, 0).coreTextured()); assertNotNull(s.coreTake(owner, 0));
        assertTrue(s.finishCoreTake(owner, 0, tx, 2));
        s = open(); assertFalse(s.entry(owner, 0).coreTextured());
        s.install(owner, core, "world", 0, 64, 0, 3, 1, true); s.flush(); assertTrue(open().entry(owner, 0).coreTextured());
        // A legacy entry contains no appearance flag; never infer it from configuration.
        Path file = dir.resolve("generator.json"); String json = Files.readString(file);
        Files.writeString(file, json.replaceAll(",\\s*\\\"coreTextured\\\"\\s*:\\s*true", ""));
        assertFalse(open().entry(owner, 0).coreTextured());
    }
    @Test void invalidStoredAppearanceIsNotCoerced() throws Exception {
        UUID owner = UUID.randomUUID(); var s = open(); s.markRegistered();
        s.install(owner, UUID.randomUUID(), "world", 0, 64, 0, 1, 1, true); s.flush();
        Path file = dir.resolve("generator.json"); String json = Files.readString(file);
        assertTrue(json.contains("\"coreTextured\": true"));
        Files.writeString(file, json.replace("\"coreTextured\": true", "\"coreTextured\": \"true\""));
        var reopened = new GeneratorStore(dir, Logger.getAnonymousLogger()); reopened.load();
        assertFalse(reopened.healthy());
    }
}
