package ru.cherdakmd.chrdkregister.api;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Общий whitelist восьми PDC-расходников Кузни и проверка замороженного гранта.
 *
 * <p>Живёт в Core, а не в аддоне: восстановление уже принятой сделки обязано
 * обходиться без живого {@code CHRDK_TierForge}, без текущих цен и без нового
 * кошелька. Всё, что нужно для проверки, уже заморожено в native-снимке одного
 * прототипа внутри {@link ShopExchange#grant()}. Обычные материалы без PDC не
 * являются расходниками: подмена предмета «по материалу» здесь запрещена.</p>
 */
public final class ForgeMarketCatalog {

    /** Namespace PDC совпадает с именем плагина CHRDK_TierForge в lower case. */
    public static final String NAMESPACE = "chrdk_tierforge";
    /** Общий тег всех восьми расходников; значение — id SKU. */
    public static final String TOKEN_KEY = "forge_material_v1";
    /** Канонический UUID искры: 36 символов, НЕ передаётся в validUid напрямую. */
    public static final String SEED_UUID_KEY = "forge_seed_uuid";
    /** Страховочный маркер max-stack-size=1 для сборок без ItemMeta#setMaxStackSize. */
    public static final String SEED_STACK_KEY = "forge_seed_stack1";
    public static final long MAX_PRICE = 1_000_000_000L;

    /** Неизменяемый каталог: id, материал, цена за штуку, искра ли это, дроп ли с Hunt. */
    public record Sku(String id, String material, long price, boolean seed, boolean huntDrop) { }

    /** Ровно восемь настоящих PDC-расходников. Порядок — порядок витрины. */
    public static final List<Sku> SKUS = List.of(
            new Sku("ascend", "ECHO_SHARD", 128, false, true),
            new Sku("temper", "NAUTILUS_SHELL", 96, false, true),
            new Sku("reforge", "PAPER", 160, false, true),
            new Sku("seed", "NETHER_STAR", 64, true, true),
            new Sku("repair", "IRON_INGOT", 24, false, false),
            new Sku("inscribe", "GOLD_NUGGET", 192, false, false),
            new Sku("remove", "GUNPOWDER", 32, false, false),
            new Sku("name", "INK_SAC", 16, false, false)
    );

    public static Optional<Sku> sku(String id) {
        if (id == null) return Optional.empty();
        String key = id.toLowerCase(Locale.ROOT);
        return SKUS.stream().filter(s -> s.id().equals(key)).findFirst();
    }

    /** Неверная цена закрывает SKU, а не подменяется значением по умолчанию. */
    public static boolean validPrice(long price) {
        return price >= 1 && price <= MAX_PRICE;
    }

    public static NamespacedKey tokenKey() {
        return Objects.requireNonNull(NamespacedKey.fromString(NAMESPACE + ":" + TOKEN_KEY));
    }

    public static NamespacedKey seedUuidKey() {
        return Objects.requireNonNull(NamespacedKey.fromString(NAMESPACE + ":" + SEED_UUID_KEY));
    }

    public static NamespacedKey seedStackKey() {
        return Objects.requireNonNull(NamespacedKey.fromString(NAMESPACE + ":" + SEED_STACK_KEY));
    }

    /** Строго каноническая форма UUID из 36 символов. */
    public static boolean canonicalUuid(String value) {
        if (value == null || value.length() != 36) return false;
        try {
            return UUID.fromString(value).toString().equals(value.toLowerCase(Locale.ROOT));
        } catch (RuntimeException error) {
            return false;
        }
    }

    /** Native-снимок одного прототипа: слот 0, количество 1, остальные 35 пустые. */
    public static String snapshot(ItemStack prototype) {
        if (prototype == null || prototype.getType().isAir() || !prototype.hasItemMeta()
                || prototype.getAmount() != 1) {
            throw new IllegalArgumentException("Нужен один PDC-прототип");
        }
        ItemStack[] items = new ItemStack[36];
        ItemStack copy = prototype.clone();
        copy.setAmount(1);
        items[0] = copy;
        return ChargeInventory.encode(items);
    }

    /** Прототип из снимка, или {@code null}, если снимок/SKU не прошли проверку. */
    public static ItemStack prototype(String grant) {
        if (!validPrototype(grant)) return null;
        return ChargeInventory.decode(grant)[0];
    }

    /** Whitelist SKU + материал + PDC; для искры ещё канонический UUID и max-stack-size=1. */
    public static boolean validPrototype(String grant) {
        try {
            if (grant == null || grant.length() > 4 * 1024 * 1024) return false;
            ItemStack[] items = ChargeInventory.decode(grant);
            ItemStack first = items[0];
            if (first == null || first.getType().isAir() || first.getAmount() != 1
                    || !first.hasItemMeta()) return false;
            for (int i = 1; i < 36; i++) if (items[i] != null) return false;
            ItemMeta meta = first.getItemMeta();
            if (meta == null) return false;
            String id = meta.getPersistentDataContainer().get(tokenKey(), PersistentDataType.STRING);
            Optional<Sku> found = sku(id);
            if (found.isEmpty() || !first.getType().name().equals(found.get().material())) return false;
            if (found.get().seed()) {
                String uuid = meta.getPersistentDataContainer().get(seedUuidKey(), PersistentDataType.STRING);
                if (!canonicalUuid(uuid)) return false;
                boolean marked = meta.getPersistentDataContainer()
                        .has(seedStackKey(), PersistentDataType.INTEGER);
                if (first.getMaxStackSize() != 1 && !marked) return false;
            }
            return true;
        } catch (RuntimeException | LinkageError error) {
            return false;
        }
    }

    /** Грант согласован с материей сделки: прототип в один предмет, тот же материал. */
    public static boolean validTradeGrant(String grant, String material, long units) {
        if (units < 1 || units > 64 || !validPrototype(grant)) return false;
        ItemStack first = ChargeInventory.decode(grant)[0];
        return first.getType().name().equals(material);
    }

    private ForgeMarketCatalog() { }
}
