package ru.cherdakmd.chrdkauction;

import com.google.gson.Gson;
import org.bukkit.inventory.ItemStack;
import java.util.*;

/** Paper's versioned binary item codec preserves components, PDC, enchants and container contents. */
final class ItemCodec {
    private static final Gson JSON = new Gson();
    static String encode(ItemStack item) {
        if (item.getAmount() <= 0 || item.getAmount() > item.getMaxStackSize()) throw new IllegalArgumentException("invalid stack size");
        String payload = Base64.getEncoder().encodeToString(item.serializeAsBytes());
        if (payload.length() > AuctionStore.MAX_PAYLOAD) throw new IllegalArgumentException("oversized item");
        return payload;
    }
    static ItemStack decode(String payload) {
        ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(payload));
        if (item == null || item.getType().isAir() || item.getAmount() <= 0 || item.getAmount() > item.getMaxStackSize())
            throw new IllegalArgumentException("invalid item");
        return item;
    }
    static String inventory(ItemStack[] items) {
        String[] encoded = new String[items.length];
        for (int i = 0; i < items.length; i++)
            if (items[i] != null && !items[i].getType().isAir()) encoded[i] = encode(items[i]);
        return JSON.toJson(encoded);
    }
    static ItemStack[] inventory(String json) {
        String[] encoded = JSON.fromJson(json, String[].class);
        if (encoded == null || encoded.length != 36) throw new IllegalArgumentException("inventory snapshot");
        ItemStack[] items = new ItemStack[encoded.length];
        for (int i = 0; i < items.length; i++) if (encoded[i] != null) items[i] = decode(encoded[i]);
        return items;
    }
    static boolean matches(ItemStack[] current, String snapshot) {
        ItemStack[] expected = inventory(snapshot);
        if (expected.length != current.length) return false;
        for (int i = 0; i < current.length; i++) {
            ItemStack a = current[i];
            if (a != null && a.getType().isAir()) a = null;
            if (!Objects.equals(a, expected[i])) return false;
        }
        return true;
    }
    static ItemStack[] copy(ItemStack[] input) {
        return Arrays.stream(input).map(s -> s == null ? null : s.clone()).toArray(ItemStack[]::new);
    }
    /** Fit a whole stack atomically; no partial item claims and no world drops. */
    static ItemStack[] insert(ItemStack[] before, ItemStack item) {
        ItemStack[] after = copy(before); int remaining = item.getAmount();
        for (int i = 0; i < after.length && remaining > 0; i++) {
            ItemStack slot = after[i];
            if (slot == null || slot.getType().isAir()) {
                int take = Math.min(remaining, item.getMaxStackSize());
                after[i] = item.clone(); after[i].setAmount(take); remaining -= take;
            } else if (slot.isSimilar(item)) {
                int take = Math.min(remaining, Math.max(0, slot.getMaxStackSize() - slot.getAmount()));
                slot.setAmount(slot.getAmount() + take); remaining -= take;
            }
        }
        return remaining == 0 ? after : null;
    }
}
