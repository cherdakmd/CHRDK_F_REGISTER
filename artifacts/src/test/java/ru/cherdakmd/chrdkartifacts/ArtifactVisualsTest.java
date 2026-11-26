package ru.cherdakmd.chrdkartifacts;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactVisualsTest {
    @Test void everyRegisteredArtifactGetsAUniqueModel() {
        var keys = new HashSet<NamespacedKey>();
        for (var def : ArtifactRegistry.all()) {
            var key = ArtifactVisuals.model(def);
            assertEquals("chrdk_artifacts", key.getNamespace());
            assertEquals("artifact/" + def.id(), key.getKey());
            assertTrue(keys.add(key));
        }
        assertEquals(65, keys.size());
        assertThrows(IllegalArgumentException.class, () -> ArtifactVisuals.model(null));
        var unknown = new ArtifactDef("not-in-catalogue", ArtifactDef.Side.GOOD, ArtifactDef.Mode.PASSIVE, Material.PAPER);
        assertThrows(IllegalArgumentException.class, () -> ArtifactVisuals.model(unknown));
        var original = ArtifactRegistry.all().get(0);
        var forged = new ArtifactDef(original.id(), original.side(), original.mode(), Material.PAPER);
        assertThrows(IllegalArgumentException.class, () -> ArtifactVisuals.model(forged));
    }

    @Test void disabledOrForeignModelAndCustomDataAreUntouched() {
        var model = new AtomicReference<NamespacedKey>();
        var customData = new boolean[] {false};
        var reads = new int[] {0};
        ItemMeta meta = (ItemMeta) Proxy.newProxyInstance(ItemMeta.class.getClassLoader(), new Class<?>[] {ItemMeta.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getItemModel" -> { reads[0]++; yield model.get(); }
                    case "hasCustomModelData" -> { reads[0]++; yield customData[0]; }
                    case "setItemModel" -> { model.set((NamespacedKey) args[0]); yield null; }
                    default -> throw new AssertionError("Unexpected meta access: " + method.getName());
                });
        var def = ArtifactRegistry.all().get(0);
        ArtifactVisuals.applyNew(meta, def, false);
        assertEquals(0, reads[0]); assertNull(model.get());
        var external = new NamespacedKey("other", "artifact"); model.set(external);
        ArtifactVisuals.applyNew(meta, def, true); assertEquals(external, model.get());
        model.set(null); customData[0] = true;
        ArtifactVisuals.applyNew(meta, def, true); assertNull(model.get());
        customData[0] = false;
        ArtifactVisuals.applyNew(meta, def, true); assertEquals(ArtifactVisuals.model(def), model.get());
    }

    @Test void creationOnlyIsOptInAndDoesNotRefreshOldItems() throws Exception {
        var source = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkartifacts/ArtifactItems.java"));
        var config = Files.readString(Path.of("src/main/resources/config.yml"));
        assertTrue(config.contains("textures:\n  artifacts: false"));
        assertTrue(source.contains("getBoolean(\"textures.artifacts\", false)"));
        assertTrue(source.contains("ArtifactVisuals.applyNew"));
        assertEquals(source.indexOf("ArtifactVisuals.applyNew"), source.lastIndexOf("ArtifactVisuals.applyNew"));
        assertTrue(source.indexOf("ArtifactVisuals.applyNew") < source.indexOf("public void refreshCarriedLore"));
    }
}
