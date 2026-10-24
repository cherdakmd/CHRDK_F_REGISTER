package ru.cherdakmd.chrdkguilds;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

/**
 * Тег гильдии в чате (1.4.163).
 *
 * <p>Оформление — единственное, что гильдия даёт всем участникам без условий:
 * это видно, но не даёт силы. Тег берётся из той же модели, что и меню, поэтому
 * после переименования он меняется сам, а у игрока без гильдии ничего не
 * появляется.</p>
 */
final class GuildNameTag implements ChrdkRegisterApi.NameTagProvider {

    private final GuildsPlugin plugin;

    GuildNameTag(GuildsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String decorate(String playerName, String decoratedName) {
        if (playerName == null || !plugin.config().chatPrefix()) {
            return null;
        }
        Player player = Bukkit.getPlayerExact(playerName);
        if (player == null) {
            return null;
        }
        Guild guild = plugin.service().guildOf(player.getUniqueId());
        if (guild == null || guild.tag().isEmpty()) {
            return null;
        }
        return plugin.messages().raw("chat-prefix", "tag", guild.tag()) + decoratedName;
    }
}
