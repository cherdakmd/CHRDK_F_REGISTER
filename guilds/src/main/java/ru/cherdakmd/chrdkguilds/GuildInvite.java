package ru.cherdakmd.chrdkguilds;

import java.util.UUID;

/**
 * Приглашение в гильдию (1.4.163).
 *
 * <p>У приглашения есть срок: вчерашнее «заходи к нам» не должно срабатывать
 * через неделю, когда игрок уже забыл, кто его звал. Срок проверяется по
 * времени, а не удалением по таймеру, — так приглашение не теряется, если
 * сервер был выключен.</p>
 *
 * @param guildId   ид гильдии
 * @param guildName название на момент приглашения (для текста)
 * @param player    кого зовут
 * @param inviter   кто позвал
 * @param createdAt когда позвали
 * @param expiresAt когда приглашение сгорит
 */
public record GuildInvite(String guildId, String guildName, UUID player, UUID inviter,
                          long createdAt, long expiresAt) {

    public GuildInvite {
        guildId = guildId == null ? "" : guildId.trim();
        guildName = guildName == null ? "" : guildName.trim();
    }

    /** Истекло ли приглашение. */
    public boolean expired(long now) {
        return expiresAt > 0 && now >= expiresAt;
    }

    /** Сколько секунд осталось — для напоминаний. */
    public long remainingSeconds(long now) {
        return expiresAt <= now ? 0L : (expiresAt - now + 999) / 1_000L;
    }
}
