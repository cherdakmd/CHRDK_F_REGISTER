package ru.cherdakmd.chrdkregister;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuideBookVisualsTest {
    @Test void newGuideModelIsOptInAndRespectsOtherModels() {
        var current = new AtomicReference<NamespacedKey>();
        boolean[] cmd = { false };
        ItemMeta meta = (ItemMeta) Proxy.newProxyInstance(ItemMeta.class.getClassLoader(), new Class<?>[]{ItemMeta.class},
                (p, method, args) -> switch(method.getName()) {
                    case "getItemModel" -> current.get();
                    case "hasCustomModelData" -> cmd[0];
                    case "setItemModel" -> { current.set((NamespacedKey) args[0]); yield null; }
                    default -> throw new AssertionError(method.getName());
                });
        assertFalse(GuideBookVisuals.applyNew(meta, false)); assertNull(current.get());
        var other = new NamespacedKey("other", "book"); current.set(other);
        assertFalse(GuideBookVisuals.applyNew(meta, true)); assertEquals(other, current.get());
        current.set(null); cmd[0] = true;
        assertFalse(GuideBookVisuals.applyNew(meta, true)); assertNull(current.get());
        cmd[0] = false;
        assertTrue(GuideBookVisuals.applyNew(meta, true));
        assertEquals(new NamespacedKey("chrdk_core", "guide_book"), current.get());
        assertFalse(GuideBookVisuals.applyNew(null, true));
    }
}
