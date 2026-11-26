package ru.cherdakmd.chrdkregister.api;

import org.bukkit.inventory.ItemStack;

/** Test-only reflection target: duplicate preflight must return before any snapshot work. */
public final class ChargeInventory {
    public static String encode(ItemStack[] items) { throw new AssertionError("snapshot must not be encoded"); }
    public static ItemStack[] decode(String snapshot) { throw new AssertionError("snapshot must not be decoded"); }
    public static boolean matches(ItemStack[] items, String snapshot) { throw new AssertionError("snapshot must not be compared"); }
    private ChargeInventory() { }
}
