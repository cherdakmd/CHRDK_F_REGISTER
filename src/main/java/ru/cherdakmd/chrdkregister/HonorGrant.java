package ru.cherdakmd.chrdkregister;

import java.util.Locale;

/**
 * Выданное игроку особое звание.
 *
 * <p>Запись публичная: {@link #reason} и {@link #grantedAt} показываются в
 * {@code /honor list}, чтобы решение владельца было объяснимым, а не «тихим
 * кумовством». Кто выдал ({@link #grantedBy}) тоже виден и дублируется в
 * {@code audit/*.jsonl}.</p>
 *
 * @param player     ник в том регистре, в котором его ввели
 * @param uuid       UUID онлайн-игрока или пусто (выдача по нику)
 * @param honorId    id определения звания
 * @param grantedBy  кто выдал
 * @param grantedAt  epoch millis
 * @param reason     публичная причина выдачи
 */
public record HonorGrant(String player, String uuid, String honorId,
                         String grantedBy, long grantedAt, String reason) {

    public HonorGrant {
        player = player == null ? "" : player.trim();
        uuid = uuid == null ? "" : uuid.trim();
        honorId = HonorDefinition.normalizeId(honorId);
        grantedBy = grantedBy == null ? "" : grantedBy.trim();
        grantedAt = grantedAt <= 0 ? System.currentTimeMillis() : grantedAt;
        reason = reason == null ? "" : reason.trim();
    }

    /** Ключ хранилища: регистронезависимый ник. */
    public static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    public String key() {
        return key(player);
    }

    public boolean belongsTo(String playerName) {
        return !key().isEmpty() && key().equals(key(playerName));
    }

    /** Правка публичной причины: дата и автор выдачи сохраняются. */
    public HonorGrant withReason(String newReason) {
        return new HonorGrant(player, uuid, honorId, grantedBy, grantedAt, newReason);
    }
}
