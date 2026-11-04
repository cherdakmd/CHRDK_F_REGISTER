package ru.cherdakmd.chrdkguilds;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/** Только личный оплаченный выбор текущего главы; роль и срок проверяются при каждом показе. */
final class GuildStylePolicy {
    static boolean canManage(Guild guild, UUID actor) {
        return guild != null && actor != null && actor.equals(guild.leader()) && guild.isMember(actor);
    }
    static String active(List<GuildStyleStore.Row> rows, Guild guild, String kind, long now,
                         ToLongFunction<GuildStyleStore.Row> until, Predicate<GuildStyleStore.Row> knownOwner) {
        return rows.stream().filter(row -> row.guild().equals(guild.id()) && canManage(guild, row.owner())
                        && row.choices().containsKey(kind) && knownOwner.test(row) && until.applyAsLong(row) > now)
                .max(Comparator.<GuildStyleStore.Row>comparingLong(row -> row.choices().get(kind).changed())
                        .thenComparing(row -> row.owner().toString()))
                .map(row -> row.choices().get(kind).preset()).orElse(null);
    }
}
