package ru.cherdakmd.chrdktierforge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Действия Кузни и их расходники. RC16: восемь whitelist-SKU; прежние три
 * расходника и остальные материалы доступны в магазине.
 *
 * <p>Чистая логика без Bukkit: проверяется юнит-тестами и вики-фактами.</p>
 */
public enum ForgeMaterial {
    ASCEND("ECHO_SHARD", "forge.ascend.name"),
    TEMPER("NAUTILUS_SHELL", "forge.temper.name"),
    REFORGE("PAPER", "forge.reforge.name"),
    SEED("NETHER_STAR", "forge.seed.name"),
    REPAIR("IRON_INGOT", "forge.repair.name"),
    INSCRIBE("GOLD_NUGGET", "forge.inscribe.name"),
    REMOVE("GUNPOWDER", "forge.remove.name"),
    NAME("INK_SAC", "forge.name.name");

    final String id;
    final String material;
    private final String nameKey;

    ForgeMaterial(String material, String nameKey) {
        this.id = name().toLowerCase(Locale.ROOT);
        this.material = material;
        this.nameKey = nameKey;
    }

    public String id() { return id; }
    public String material() { return material; }
    public String nameKey() { return nameKey; }
    public String detailsKey() { return "forge." + id + ".details"; }

    /** Все восемь расходников доступны только через магазин. */
    public static final List<ForgeMaterial> SHOP_ONLY = List.of(values());

    /** XP измеряется уровнями, не очками опыта. */
    public static final int SEED_XP = 8;
    public static final int REPAIR_XP = 6;
    public static final int INSCRIBE_XP = 10;
    public static final int REMOVE_XP = 4;
    public static final int NAME_XP = 2;

    /** Только искра расширяет eligibility до вещи без тира. */
    public boolean seed() { return this == SEED; }
    public boolean shopOnly() { return true; }
    /** Совместимость поля каталога: после снятия внешнего источника дропа всегда false. */
    public boolean huntDrop() { return false; }

    static final int[] ASCEND_COSTS = {1, 1, 2, 4, 8, 16};
    static final int[] ASCEND_XP = {9, 12, 15, 18, 21, 24};

    public static int ascendCost(int index) {
        if (index < 1 || index > ASCEND_COSTS.length) throw new IllegalArgumentException("tier index");
        return ASCEND_COSTS[index - 1];
    }

    public static int ascendXp(int index) {
        if (index < 1 || index > ASCEND_XP.length) throw new IllegalArgumentException("tier index");
        return ASCEND_XP[index - 1];
    }

    /** Полная цена уровней XP для новых действий. */
    public static int xp(ForgeMaterial kind) {
        if (kind == null) throw new IllegalArgumentException("kind");
        return switch (kind) {
            case SEED -> SEED_XP;
            case REPAIR -> REPAIR_XP;
            case INSCRIBE -> INSCRIBE_XP;
            case REMOVE -> REMOVE_XP;
            case NAME -> NAME_XP;
            default -> 0;
        };
    }

    public static ForgeMaterial byId(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 32) return null;
        String key = raw.toLowerCase(Locale.ROOT);
        for (ForgeMaterial value : values()) if (value.id.equals(key)) return value;
        return null;
    }

    public static long nextSequence(long current) {
        if (current < 0 || current == Long.MAX_VALUE) throw new IllegalArgumentException("sequence");
        return current + 1;
    }

    /** Детерминированный выбор: повторное открытие наковальни и relog не меняют результат. */
    public static Random choice(String identity, long sequence, ForgeMaterial kind, String selectedId) {
        String seed = identity + "|" + sequence + "|" + (kind == null ? "" : kind.id) + "|"
                + (selectedId == null ? "" : selectedId.toLowerCase(Locale.ROOT));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int i = 0; i < 8; i++) value = (value << 8) | (digest[i] & 0xffL);
            return new Random(value);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Канонический UUID искры (36 символов) → действующий 12-hex modifier UID.
     *
     * <p>ВАЖНО: сам 36-символьный UUID нельзя передавать в
     * {@link TierModifierIds#validUid(String)} — он ожидает ровно 12 hex. Искра
     * хранит и UUID, и производный UID, поэтому результат стабилен после
     * переоткрытия наковальни и relog.</p>
     */
    public static String seedUid(String canonicalUuid) {
        if (!ru.cherdakmd.chrdkregister.api.ForgeMarketCatalog.canonicalUuid(canonicalUuid)) {
            throw new IllegalArgumentException("Нужен канонический UUID искры");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalUuid.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(TierModifierIds.UID_LENGTH);
            for (int i = 0; i < TierModifierIds.UID_LENGTH / 2; i++) {
                builder.append(String.format("%02x", digest[i] & 0xff));
            }
            return builder.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
