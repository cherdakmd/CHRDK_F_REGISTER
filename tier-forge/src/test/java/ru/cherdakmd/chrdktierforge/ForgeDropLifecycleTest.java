package ru.cherdakmd.chrdktierforge;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Контракт API без запуска мира: detached-сущность, список события, приоритеты. */
class ForgeDropLifecycleTest {
    @Test
    void extrasAreCreatedNotSpawnedAndKeepPickupOwnershipInCancellableList() {
        Map<String, Object> assigned = new HashMap<>();
        Item extra = (Item) Proxy.newProxyInstance(Item.class.getClassLoader(), new Class<?>[]{Item.class},
                (proxy, method, args) -> {
                    if (method.getName().startsWith("set")) {
                        assigned.put(method.getName(), args[0]);
                        return null;
                    }
                    throw new AssertionError("Неожиданный вызов Item: " + method.getName());
                });
        List<Location> created = new ArrayList<>();
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    assertEquals("createEntity", method.getName(), "До решения события спавнить нельзя");
                    assertSame(Item.class, args[1]);
                    created.add((Location) args[0]);
                    return extra;
                });
        UUID owner = UUID.randomUUID();
        UUID thrower = UUID.randomUUID();
        Vector velocity = new Vector(.1, .2, .3);
        Item source = (Item) Proxy.newProxyInstance(Item.class.getClassLoader(), new Class<?>[]{Item.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getOwner" -> owner;
                    case "getThrower" -> thrower;
                    case "getPickupDelay" -> 12;
                    case "getVelocity" -> velocity;
                    default -> throw new AssertionError("Неожиданный вызов источника: " + method.getName());
                });
        // Тест проверяет передачу стека, не обращается к серверной реализации его компонентов.
        ItemStack stack = new ItemStack() { };
        Location location = new Location(world, 1, -20, 3);
        assertSame(extra, ForgeUpgradeListener.detachedDrop(location, stack, source));
        assertEquals(1, created.size());
        assertSame(location, created.getFirst());
        assertSame(stack, assigned.get("setItemStack"));
        assertSame(velocity, assigned.get("setVelocity"));
        assertEquals(owner, assigned.get("setOwner"));
        assertEquals(thrower, assigned.get("setThrower"));
        assertEquals(12, assigned.get("setPickupDelay"));
        var event = new BlockDropItemEvent(null, null, null, new ArrayList<>(List.of(source)));
        event.getItems().add(extra);
        event.setCancelled(true);
        assertTrue(event.isCancelled());
        assertSame(extra, event.getItems().get(1));
        event.getItems().remove(1);
        assertEquals(1, event.getItems().size());
        assertEquals(1, created.size()); // мировые spawn/drop методы вообще не вызывались
    }

    @Test
    void breakOnlyCapturesAndColumnDefersFromMonitoredActualDrops() throws Exception {
        assertHandler("captureBreakTool", BlockBreakEvent.class, EventPriority.MONITOR);
        assertHandler("onOreBreak", BlockBreakEvent.class, EventPriority.NORMAL);
        assertHandler("onBlockDrops", BlockDropItemEvent.class, EventPriority.NORMAL);
        assertHandler("onColumnDrops", BlockDropItemEvent.class, EventPriority.MONITOR);
    }

    private static void assertHandler(String name, Class<?> event, EventPriority priority) throws Exception {
        EventHandler handler = ForgeUpgradeListener.class.getMethod(name, event).getAnnotation(EventHandler.class);
        assertNotNull(handler);
        assertTrue(handler.ignoreCancelled());
        assertEquals(priority, handler.priority());
    }
}
