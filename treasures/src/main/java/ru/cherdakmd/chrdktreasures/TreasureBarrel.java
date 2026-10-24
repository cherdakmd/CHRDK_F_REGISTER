package ru.cherdakmd.chrdktreasures;

import java.util.UUID;

/**
 * Данные клада-бочки (1.4.166).
 *
 * <p>Модель без Bukkit: владелец, редкость и время появления. Всё остальное —
 * содержимое и сама бочка — живёт в мире, поэтому дублировать добычу в файле
 * не нужно: бочка настоящая, и ванильная механика разрушения контейнера
 * высыпает её содержимое.</p>
 *
 * <p>Записи хранятся в самом блоке (PersistentDataContainer бочки), поэтому
 * перезапуск сервера и выгрузка чанка не теряют владельца и срок жизни.</p>
 *
 * @param owner     кому принадлежит клад
 * @param ownerName ник на момент находки — для сообщения «клад %player%»
 * @param tier      редкость находки (для анимации и подсказок)
 * @param createdAt время появления в миллисекундах
 */
public record TreasureBarrel(UUID owner, String ownerName, TreasureTier tier, long createdAt) {

    public TreasureBarrel {
        ownerName = ownerName == null ? "" : ownerName;
        tier = tier == null ? TreasureTier.COMMON : tier;
        createdAt = Math.max(0L, createdAt);
    }

    /** Принадлежит ли бочка игроку. */
    public boolean ownedBy(UUID player) {
        return owner != null && owner.equals(player);
    }

    /**
     * Истёк ли срок жизни.
     *
     * @param now       текущее время в миллисекундах
     * @param ttlMillis срок жизни; {@code 0} — бессрочно
     */
    public boolean expired(long now, long ttlMillis) {
        return ttlMillis > 0L && now - createdAt >= ttlMillis;
    }

    /** Ник для сообщений: пустая подпись не должна оставлять дыру в тексте. */
    public String ownerLabel() {
        return ownerName.isBlank() ? "?" : ownerName;
    }
}
