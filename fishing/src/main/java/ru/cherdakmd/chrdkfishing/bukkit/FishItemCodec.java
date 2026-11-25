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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import ru.cherdakmd.chrdkfishing.FishCatalog;
import ru.cherdakmd.chrdkfishing.FishToken;
import ru.cherdakmd.chrdkfishing.FishingModel;

/**
 * Experimental item codec, not an event listener. A token is authenticated on every
 * read; names and lore are informational only and never determine value. No sale,
 * crafting or extra-catch permission is granted by possession of this codec.
 */
public final class FishItemCodec {
    private static final NamespacedKey MARKER = key("kind");
    private static final NamespacedKey ID = key("fish_id");
    private static final NamespacedKey SPECIES = key("species");
    private static final NamespacedKey SIZE = key("size");
    private static final NamespacedKey LENGTH = key("length_cm");
    private static final NamespacedKey PRICE = key("quoted_points");
    private static final NamespacedKey MAC = key("signature");
    private static final String VERSION = "fish-v1";
    private final byte[] secret;
    private final boolean textures;
    private static NamespacedKey key(String name) { return new NamespacedKey("chrdk_fishing", name); }

    public FishItemCodec(byte[] secret) { this(secret, false); }
    public FishItemCodec(byte[] secret, boolean textures) {
        if (secret == null || secret.length != FishToken.KEY_BYTES) throw new IllegalArgumentException("fish signing key");
        this.secret = secret.clone(); this.textures = textures;
    }
    public ItemStack build(FishToken.Token token) {
        if (!FishToken.verify(secret, token)) throw new IllegalArgumentException("invalid fish token");
        var species = FishCatalog.byId(token.fish().speciesId());
        var item = new ItemStack(Material.COD, 1);
        ItemMeta meta = item.getItemMeta();
        if (textures) meta.setItemModel(FishingVisuals.fish(species.rarity()));
        meta.displayName(Component.text(species.name(), NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Длина: " + token.fish().lengthCm() + " см", NamedTextColor.GRAY),
                Component.text("Размер: " + sizeName(token.fish().size()), NamedTextColor.GRAY),
                Component.text("Рыба для коллекции", NamedTextColor.DARK_GRAY)));
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(MARKER, PersistentDataType.STRING, VERSION);
        pdc.set(ID, PersistentDataType.STRING, token.id().toString());
        pdc.set(SPECIES, PersistentDataType.STRING, token.fish().speciesId());
        pdc.set(SIZE, PersistentDataType.STRING, token.fish().size().name());
        pdc.set(LENGTH, PersistentDataType.INTEGER, token.fish().lengthCm());
        pdc.set(PRICE, PersistentDataType.LONG, token.quotedPrice());
        pdc.set(MAC, PersistentDataType.STRING, token.mac());
        item.setItemMeta(meta);
        return item;
    }
    /** Includes invalid branded items, so callers can fail closed rather than treat them as vanilla cod. */
    public boolean marked(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(MARKER) || pdc.has(ID) || pdc.has(SPECIES) || pdc.has(SIZE)
                || pdc.has(LENGTH) || pdc.has(PRICE) || pdc.has(MAC);
    }
    public Optional<FishToken.Token> read(ItemStack item) {
        if (item == null || item.getType() != Material.COD || item.getAmount() != 1 || !item.hasItemMeta()) return Optional.empty();
        var pdc = item.getItemMeta().getPersistentDataContainer();
        if (!VERSION.equals(pdc.get(MARKER, PersistentDataType.STRING))) return Optional.empty();
        try {
            String rawId = pdc.get(ID, PersistentDataType.STRING);
            String species = pdc.get(SPECIES, PersistentDataType.STRING);
            String size = pdc.get(SIZE, PersistentDataType.STRING);
            Integer length = pdc.get(LENGTH, PersistentDataType.INTEGER);
            Long price = pdc.get(PRICE, PersistentDataType.LONG);
            String mac = pdc.get(MAC, PersistentDataType.STRING);
            if (rawId == null || species == null || size == null || length == null || price == null || mac == null) return Optional.empty();
            UUID id = UUID.fromString(rawId);
            if (!id.toString().equals(rawId)) return Optional.empty();
            var token = new FishToken.Token(id, new FishingModel.Catch(species, FishingModel.Size.valueOf(size), length), price, mac);
            return FishToken.verify(secret, token) ? Optional.of(token) : Optional.empty();
        } catch (IllegalArgumentException error) { return Optional.empty(); }
    }
    /** Only call from a confirmed extra-catch event; not a general-purpose copy operation. */
    public Optional<ItemStack> duplicateForCatch(ItemStack original, UUID freshId) {
        var token = read(original);
        if (token.isEmpty()) return Optional.empty();
        try { return Optional.of(build(FishToken.duplicateForCatch(secret, token.get(), freshId))); }
        catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }
    private static String sizeName(FishingModel.Size size) {
        return switch (size) {
            case SMALL -> "маленькая";
            case NORMAL -> "обычная";
            case LARGE -> "крупная";
            case TROPHY -> "трофейная";
        };
    }
}
