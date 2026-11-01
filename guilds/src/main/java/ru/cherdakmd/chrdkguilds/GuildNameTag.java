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
    private final boolean tab;

    GuildNameTag(GuildsPlugin plugin) {
        this(plugin,false);
    }

    GuildNameTag(GuildsPlugin plugin, boolean tab) {
        this.plugin=plugin;
        this.tab=tab;
    }

    /** Ключи TAB отделены от настройки chat-prefix и косметических мест чата. */
    static String tabKey(boolean peaceful, boolean protectedNow) {
        return peaceful ? (protectedNow ? "tab-peace-prefix" : "tab-peace-pending-prefix") : "tab-battle-prefix";
    }

    @Override
    public String decorate(String playerName, String decoratedName) {
        if (playerName == null || !plugin.config().enabled() || (!tab && !plugin.config().chatPrefix())) {
            return null;
        }
        Player player = Bukkit.getPlayerExact(playerName);
        if (player == null || (tab && !plugin.authenticated(player))) {
            return null;
        }
        Guild guild = plugin.service().guildOf(player.getUniqueId());
        if (guild == null || (!tab && guild.tag().isEmpty())) {
            return null;
        }
        if (tab) return plugin.messages().raw(tabKey(guild.peaceful(),plugin.peace().protects(player.getUniqueId(),System.currentTimeMillis())),
                "tag",guild.tag().isEmpty()?guild.name():guild.tag()) + decoratedName;
        return plugin.messages().raw(guild.peaceful()?(plugin.peace().protects(player.getUniqueId(),System.currentTimeMillis())?"peace-chat-prefix":"peace-pending-prefix"):"chat-prefix", "tag", ru.cherdakmd.chrdkregister.Messages.trusted(plugin.styles().tag(guild))) + decoratedName;
    }
}
