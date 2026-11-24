package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import ru.cherdakmd.chrdkfishing.FishToken;
import ru.cherdakmd.chrdkfishing.RodToken;

/** Draft rod item format; the upgrade transaction must separately preserve and verify every other item property. */
public final class RodItemCodec {
    private static final NamespacedKey MARKER = key("rod_kind"), ID = key("rod_id"),
            LEVEL = key("rod_level"), MAC = key("rod_signature");
    private static final String VERSION = "rod-v1";
    private final byte[] secret;
    private static NamespacedKey key(String name) { return new NamespacedKey("chrdk_fishing", name); }
    public RodItemCodec(byte[] secret) {
        if (secret == null || secret.length != FishToken.KEY_BYTES) throw new IllegalArgumentException("rod signing key");
        this.secret = secret.clone();
    }
    /** Detect even invalid/partial tagged items; they must never be silently downgraded to vanilla. */
    public boolean marked(ItemStack rod) {
        if (rod == null || !rod.hasItemMeta()) return false;
        var pdc = rod.getItemMeta().getPersistentDataContainer();
        return pdc.has(MARKER) || pdc.has(ID) || pdc.has(LEVEL) || pdc.has(MAC);
    }
    public Optional<RodToken.Token> read(ItemStack rod) {
        if (rod == null || rod.getType() != Material.FISHING_ROD || rod.getAmount() != 1 || !rod.hasItemMeta()) return Optional.empty();
        var pdc = rod.getItemMeta().getPersistentDataContainer();
        if (!VERSION.equals(pdc.get(MARKER, PersistentDataType.STRING))) return Optional.empty();
        try {
            String rawId = pdc.get(ID, PersistentDataType.STRING);
            Integer level = pdc.get(LEVEL, PersistentDataType.INTEGER);
            String mac = pdc.get(MAC, PersistentDataType.STRING);
            if (rawId == null || level == null || mac == null) return Optional.empty();
            UUID id = UUID.fromString(rawId);
            if (!id.toString().equals(rawId)) return Optional.empty();
            var token = new RodToken.Token(id, level, mac);
            return RodToken.verify(secret, token) ? Optional.of(token) : Optional.empty();
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }
    /** Never mutate the original item. A separate transaction must authorize the token and its delivery. */
    public ItemStack stamp(ItemStack original, RodToken.Token token) {
        if (original == null || original.getType() != Material.FISHING_ROD || original.getAmount() != 1
                || !RodToken.verify(secret, token)) throw new IllegalArgumentException("invalid rod");
        ItemStack clone = original.clone(); var meta = clone.getItemMeta(); var pdc = meta.getPersistentDataContainer();
        pdc.set(MARKER, PersistentDataType.STRING, VERSION); pdc.set(ID, PersistentDataType.STRING, token.id().toString());
        pdc.set(LEVEL, PersistentDataType.INTEGER, token.level()); pdc.set(MAC, PersistentDataType.STRING, token.mac());
        clone.setItemMeta(meta); return clone;
    }
}
