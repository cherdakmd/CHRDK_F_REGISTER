package ru.cherdakmd.chrdkguilds;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.List;

/**
 * Раздел «Гильдия» в {@code /profile} (1.4.163).
 *
 * <p>Название, тег, ранг игрока, состав, казна и война. Раздел виден и тем, кто
 * без гильдии: пустая карточка объясняет, как её создать, — иначе о гильдиях
 * узнают только по слухам.</p>
 */
final class GuildsProfile implements ChrdkRegisterApi.ProfileProvider {

    private final GuildsPlugin plugin;

    GuildsProfile(GuildsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public ChrdkRegisterApi.ProfileSection section(ChrdkRegisterApi.ProfileContext context) {
        if (context == null || plugin.service() == null) {
            return null;
        }
        Messages messages = plugin.messages();
        Guild guild = plugin.service().guildOf(context.playerId());
        List<Component> lines = new ArrayList<>();
        if (guild == null) {
            lines.add(messages.get("profile-none", "cost",
                    GuildMath.diamonds(plugin.config().creationCost())));
            lines.add(messages.get("profile-none-hint"));
        } else {
            lines.add(messages.get("peace-profile-mode","mode",guild.mode().label(),"rate",GuildPeaceService.DAILY));
            var term=guild.peaceTerm(context.playerId());
            if(term!=null)lines.add(messages.get("peace-profile-term","paid",GuildPeaceController.time(term.paidUntil())));
            lines.add(messages.get("peace-debt-status","debt",plugin.store().sanction(context.playerId()).debt()));
            GuildRank rank = guild.rankOf(context.playerId());
            lines.add(messages.get("profile-name",
                    "name", guild.name(),
                    "tag", guild.tag().isEmpty() ? "—" : guild.tag()));
            lines.add(messages.get("profile-rank",
                    "rank", messages.mini("rank." + (rank == null
                            ? GuildRank.MEMBER.id() : rank.id()))));
            lines.add(messages.get("profile-members",
                    "count", String.valueOf(guild.size()),
                    "cap", String.valueOf(GuildMath.memberCap(guild.level()))));
            lines.add(messages.get("profile-treasury",
                    "amount", GuildMath.diamonds(guild.treasury()),
                    "level", GuildMath.roman(guild.level())));
            GuildWar war = plugin.service().warOf(guild.id());
            if (war != null) {
                Guild opponent = plugin.store().guild(war.opponentOf(guild.id()));
                lines.add(messages.get("profile-war",
                        "opponent", opponent == null ? "?" : opponent.label(),
                        "score", war.pointsOf(guild.id()) + ":"
                                + war.pointsOf(war.opponentOf(guild.id())),
                        "state", messages.mini(war.pending()
                                ? "war-state-pending" : "war-state-active")));
            } else {
                lines.add(messages.get("profile-glory",
                        "glory", String.valueOf(guild.glory()),
                        "won", String.valueOf(guild.warsWon())));
            }
        }
        return new ChrdkRegisterApi.ProfileSection(messages.raw("profile-title"),
                Material.WHITE_BANNER, lines);
    }
}
