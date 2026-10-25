package ru.cherdakmd.chrdkregister;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Что можно надеть через {@code /hat} (1.4.167).
 *
 * <p>Проверка чистая: без сервера видно, что команда не превращается в лазейку
 * «спрячь в голову меч или элитру».</p>
 */
class HatCommandTest {

    @Test
    void blocksAreWearable() {
        assertTrue(HatCommand.isWearable(Material.STONE));
        assertTrue(HatCommand.isWearable(Material.PUMPKIN));
        assertTrue(HatCommand.isWearable(Material.CARVED_PUMPKIN));
        assertTrue(HatCommand.isWearable(Material.OAK_PLANKS));
        assertTrue(HatCommand.isWearable(Material.GLASS));
    }

    @Test
    void itemsAndAirAreNot() {
        assertFalse(HatCommand.isWearable(Material.AIR));
        assertFalse(HatCommand.isWearable(null));
        assertFalse(HatCommand.isWearable(Material.DIAMOND_SWORD), "меч — не шляпа");
        assertFalse(HatCommand.isWearable(Material.ELYTRA), "элитра выдавала бы полёт");
        assertFalse(HatCommand.isWearable(Material.SHIELD));
        assertFalse(HatCommand.isWearable(Material.GOLDEN_APPLE));
        assertFalse(HatCommand.isWearable(Material.IRON_HELMET), "шлем надевают обычным способом");
    }
}
