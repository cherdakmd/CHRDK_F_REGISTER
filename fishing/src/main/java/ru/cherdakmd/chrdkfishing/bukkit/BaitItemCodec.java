package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import ru.cherdakmd.chrdkfishing.BaitCatalog;
import ru.cherdakmd.chrdkfishing.BaitToken;
import ru.cherdakmd.chrdkfishing.FishToken;

/** Encodes one unique bait, not an authorization to use or deliver it. */
final class BaitItemCodec {
    private static final NamespacedKey KIND = key("bait_kind"), ID = key("bait_id"), MAC = key("bait_signature");
    private final byte[] secret;
    private static NamespacedKey key(String name) { return new NamespacedKey("chrdk_fishing", name); }
    BaitItemCodec(byte[] secret) {
        if (secret == null || secret.length != FishToken.KEY_BYTES) throw new IllegalArgumentException("bait key");
        this.secret = secret.clone();
    }
    boolean marked(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        var pdc = item.getItemMeta().getPersistentDataContainer(); return pdc.has(KIND) || pdc.has(ID) || pdc.has(MAC);
    }
    ItemStack build(BaitToken.Token token) {
        if (!BaitToken.verify(secret, token)) throw new IllegalArgumentException("bait signature");
        var bait = BaitCatalog.byId(token.kind());
        var item = new ItemStack(Material.PAPER, 1); var meta = item.getItemMeta();
        meta.displayName(Component.text(bait.name(), NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Одноразовая приманка", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        var pdc = meta.getPersistentDataContainer();
        pdc.set(KIND, PersistentDataType.STRING, token.kind()); pdc.set(ID, PersistentDataType.STRING, token.id().toString());
        pdc.set(MAC, PersistentDataType.STRING, token.mac()); item.setItemMeta(meta); return item;
    }
    Optional<BaitToken.Token> read(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || item.getAmount() != 1 || !item.hasItemMeta()) return Optional.empty();
        var pdc = item.getItemMeta().getPersistentDataContainer();
        try {
            String raw = pdc.get(ID, PersistentDataType.STRING), kind = pdc.get(KIND, PersistentDataType.STRING), mac = pdc.get(MAC, PersistentDataType.STRING);
            if (raw == null || kind == null || mac == null) return Optional.empty();
            UUID id = UUID.fromString(raw); if (!id.toString().equals(raw)) return Optional.empty();
            var token = new BaitToken.Token(id, kind, mac);
            return BaitToken.verify(secret, token) ? Optional.of(token) : Optional.empty();
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }
}
