package ru.cherdakmd.chrdkregister;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ServerMenuVisualsTest {
    @Test void onlyKnownCategoriesWithTheirDefaultMaterialGetModels() {
        var defaults = Map.of("account", Material.PAPER, "progress", Material.EXPERIENCE_BOTTLE,
                "economy", Material.DIAMOND, "world", Material.GRASS_BLOCK,
                "gear", Material.ANVIL, "social", Material.BELL);
        Set<NamespacedKey> models = new HashSet<>();
        defaults.forEach((id, material) -> {
            var key = ServerMenuVisuals.category(id, material);
            assertEquals("chrdk_menu", key.getNamespace());
            assertEquals("category/" + id, key.getKey());
            assertTrue(models.add(key));
            assertNull(ServerMenuVisuals.category(id, Material.STONE));
        });
        assertNull(ServerMenuVisuals.category("custom", Material.PAPER));
        assertNull(ServerMenuVisuals.category(null, Material.PAPER));
        assertNull(ServerMenuVisuals.back(Material.STONE));
        assertNull(ServerMenuVisuals.close(Material.STONE));
        assertEquals("navigation/back", ServerMenuVisuals.back(Material.ARROW).getKey());
        assertEquals("navigation/close", ServerMenuVisuals.close(Material.BARRIER).getKey());
    }

    @Test void optInAndExistingModelsAreRespected() {
        var current = new AtomicReference<NamespacedKey>();
        var customData = new boolean[] { false };
        ItemMeta meta = (ItemMeta) Proxy.newProxyInstance(ItemMeta.class.getClassLoader(), new Class<?>[] { ItemMeta.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getItemModel" -> current.get();
                    case "hasCustomModelData" -> customData[0];
                    case "setItemModel" -> { current.set((NamespacedKey) args[0]); yield null; }
                    default -> throw new AssertionError("Unexpected method: " + method.getName());
                });
        var model = ServerMenuVisuals.back(Material.ARROW);
        ServerMenuVisuals.apply(meta, model, false);
        assertNull(current.get());
        var other = new NamespacedKey("other", "icon");
        current.set(other);
        ServerMenuVisuals.apply(meta, model, true);
        assertEquals(other, current.get());
        current.set(null); customData[0] = true;
        ServerMenuVisuals.apply(meta, model, true);
        assertNull(current.get());
        customData[0] = false;
        ServerMenuVisuals.apply(meta, null, true);
        assertNull(current.get());
        ServerMenuVisuals.apply(meta, model, true);
        assertEquals(model, current.get());
    }
}
