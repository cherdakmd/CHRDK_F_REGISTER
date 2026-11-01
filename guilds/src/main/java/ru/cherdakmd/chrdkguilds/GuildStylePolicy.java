package ru.cherdakmd.chrdkguilds;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/** Лицензия личная: оплаченный срок, неизменный UUID и текущее членство обязательны при каждом показе. */
final class GuildStylePolicy {
    static String active(List<GuildStyleStore.Row> rows, Guild guild, String kind, long now,
                         ToLongFunction<GuildStyleStore.Row> until, Predicate<GuildStyleStore.Row> knownOwner) {
        return rows.stream().filter(row -> row.guild().equals(guild.id()) && guild.isMember(row.owner())
                        && row.choices().containsKey(kind) && knownOwner.test(row) && until.applyAsLong(row) > now)
                .max(Comparator.<GuildStyleStore.Row>comparingLong(row -> row.choices().get(kind).changed())
                        .thenComparing(row -> row.owner().toString()))
                .map(row -> row.choices().get(kind).preset()).orElse(null);
    }
}
