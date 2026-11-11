package ru.cherdakmd.chrdkdisasters;

import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DisasterRestoreTest {

    @Test
    void restoresWaterFireAndSnowLeftByDisaster() {
        for (String type : new String[]{"minecraft:water", "minecraft:fire", "minecraft:snow[layers=3]"}) {
            assertTrue(DisasterRestore.shouldRestore(data(type), data(type)), type);
        }
    }

    @Test
    void preservesLaterPlayerChanges() {
        BlockData applied = data("minecraft:air");
        assertFalse(DisasterRestore.shouldRestore(data("minecraft:stone_bricks"), applied));
        assertFalse(DisasterRestore.shouldRestore(null, applied));
        assertFalse(DisasterRestore.shouldRestore(applied, null));
    }

    @Test
    void comparesBlockStateNotOnlyMaterial() {
        assertFalse(DisasterRestore.shouldRestore(
                data("minecraft:snow[layers=8]"), data("minecraft:snow[layers=2]")));
    }

    /** Не требует Bukkit server lifecycle, в отличие от Bukkit.createBlockData. */
    private static BlockData data(String value) {
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(),
                new Class<?>[]{BlockData.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getAsString", "toString" -> value;
                    case "matches" -> args != null && args.length == 1 && args[0] instanceof BlockData other
                            && value.equals(other.getAsString());
                    case "clone" -> data(value);
                    case "hashCode" -> value.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
