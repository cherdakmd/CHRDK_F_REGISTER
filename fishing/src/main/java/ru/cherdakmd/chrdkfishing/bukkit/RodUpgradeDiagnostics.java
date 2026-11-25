package ru.cherdakmd.chrdkfishing.bukkit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import org.bukkit.inventory.ItemStack;

/** Read-only checks of the native storage round trip used by the bank; never logs the snapshot. */
final class RodUpgradeDiagnostics {
    interface SnapshotCodec {
        String encode(ItemStack[] storage) throws Exception;
        ItemStack[] decode(String snapshot) throws Exception;
        boolean matches(ItemStack[] storage, String snapshot) throws Exception;
    }
    record Result(boolean semanticMatch, boolean exactMatch, boolean stableSnapshot, boolean bankHashMatch) { }

    static Result inspect(ItemStack[] current, SnapshotCodec codec) throws Exception {
        if (current == null || current.length != 36 || codec == null) throw new IllegalArgumentException("invalid storage");
        // Mirror the planner's defensive copies plus Plan.before(); do not touch real items.
        String snapshot = codec.encode(copy(copy(copy(current))));
        ItemStack[] restored = codec.decode(snapshot);
        if (restored == null || restored.length != 36) throw new IllegalArgumentException("invalid decoded storage");
        boolean semantic = codec.matches(current, snapshot);
        boolean exact = Arrays.equals(restored, current);
        String restoredSnapshot = codec.encode(restored);
        ItemStack[] planAfter = copy(restored);
        String afterSnapshot = codec.encode(planAfter);
        boolean bankHash = MessageDigest.isEqual(hash(snapshot, snapshot), hash(restoredSnapshot, afterSnapshot));
        return new Result(semantic, exact, snapshot.equals(restoredSnapshot), bankHash);
    }
    private static ItemStack[] copy(ItemStack[] items) {
        return Arrays.stream(items).map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
    }
    private static byte[] hash(String before, String after) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update(before.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
        return digest.digest(after.getBytes(StandardCharsets.UTF_8));
    }
    private RodUpgradeDiagnostics() { }
}
