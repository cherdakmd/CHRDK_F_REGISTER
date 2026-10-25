package ru.cherdakmd.chrdkguilds;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Плейсхолдеры PlaceholderAPI: {@code %chrdkguilds_...%} (1.4.163).
 *
 * <p>Только чтение: таб и скорборд спрашивают их часто. У игрока без гильдии
 * пустая строка, а не «0», чтобы условие в скорборде не срабатывало ложно.</p>
 */
public final class GuildsPlaceholders extends PlaceholderExpansion {

    private final GuildsPlugin plugin;

    GuildsPlaceholders(GuildsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "chrdkguilds";
    }

    @Override
    public @NotNull String getAuthor() {
        return "cherdakmd";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        String key = params.toLowerCase(Locale.ROOT);
        GuildService service = plugin.service();
        if (service == null) {
            return "";
        }
        Guild guild = player == null ? null : service.guildOf(player.getUniqueId());
        return switch (key) {
            case "total" -> Integer.toString(service.count());
            case "name" -> guild == null ? "" : guild.name();
            case "tag" -> guild == null ? "" : guild.tag();
            case "rank" -> guild == null || player == null ? ""
                    : plugin.messages().plain("rank." + rankId(guild, player));
            case "rank_id" -> guild == null || player == null ? "" : rankId(guild, player);
            case "members" -> guild == null ? "" : Integer.toString(guild.size());
            case "cap" -> guild == null ? "" : Integer.toString(GuildMath.memberCap(guild.level()));
            case "level" -> guild == null ? "" : Integer.toString(guild.level());
            case "level_roman" -> guild == null ? "" : GuildMath.roman(guild.level());
            case "treasury" -> guild == null ? "" : Long.toString(guild.treasury());
            case "glory" -> guild == null ? "" : Integer.toString(guild.glory());
            case "leader" -> guild == null || guild.leader() == null ? ""
                    : leaderName(guild);
            case "war" -> warName(guild);
            case "war_score" -> warScore(guild);
            case "war_time" -> warTime(guild);
            case "at_war" -> warActive(guild);
            case "wars_won" -> guild == null ? "" : Integer.toString(guild.warsWon());
            case "wars_lost" -> guild == null ? "" : Integer.toString(guild.warsLost());
            case "enabled" -> plugin.config().enabled() ? "true" : "false";
            case "wars_enabled" -> plugin.config().warsEnabled() ? "true" : "false";
            case "invites" -> player == null ? "" : Integer.toString(
                    plugin.store().inviteOf(player.getUniqueId()) == null ? 0 : 1);
            default -> "";
        };
    }

    private static String rankId(Guild guild, OfflinePlayer player) {
        GuildRank rank = guild.rankOf(player.getUniqueId());
        return rank == null ? GuildRank.MEMBER.id() : rank.id();
    }

    private String leaderName(Guild guild) {
        OfflinePlayer leader = plugin.getServer().getOfflinePlayer(guild.leader());
        return leader.getName() == null ? "?" : leader.getName();
    }

    private String warName(Guild guild) {
        if (guild == null) {
            return "";
        }
        GuildWar war = plugin.service().warOf(guild.id());
        if (war == null) {
            return "";
        }
        Guild opponent = plugin.store().guild(war.opponentOf(guild.id()));
        return opponent == null ? "" : opponent.name();
    }

    private String warScore(Guild guild) {
        if (guild == null) {
            return "";
        }
        GuildWar war = plugin.service().warOf(guild.id());
        if (war == null) {
            return "";
        }
        return war.pointsOf(guild.id()) + ":" + war.pointsOf(war.opponentOf(guild.id()));
    }

    private String warTime(Guild guild) {
        if (guild == null) {
            return "";
        }
        GuildWar war = plugin.service().warOf(guild.id());
        if (war == null || !war.active()) {
            return "";
        }
        return GuildMath.clock(war.secondsLeft(System.currentTimeMillis()));
    }

    private String warActive(Guild guild) {
        if (guild == null) {
            return "false";
        }
        GuildWar war = plugin.service().warOf(guild.id());
        return war != null && war.active() ? "true" : "false";
    }
}
