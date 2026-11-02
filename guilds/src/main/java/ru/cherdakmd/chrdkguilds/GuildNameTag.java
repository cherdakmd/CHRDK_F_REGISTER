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

    @Override public boolean animated(String playerName) {
        if (!plugin.config().enabled()) return false;
        Player player = Bukkit.getPlayerExact(playerName);
        if (player == null || !plugin.authenticated(player)) return false;
        Guild guild = plugin.service().guildOf(player.getUniqueId());
        return guild != null && plugin.styles().active(guild, "animation") != null;
    }

    @Override
    public String decorate(String playerName, String decoratedName) {
        if (playerName == null || !plugin.config().enabled()) {
            return null;
        }
        Player player = Bukkit.getPlayerExact(playerName);
        if (player == null || !plugin.authenticated(player)) {
            return null;
        }
        Guild guild = plugin.service().guildOf(player.getUniqueId());
        if (guild == null) {
            return null;
        }
        if (!tab && !plugin.config().chatPrefix() && plugin.styles().active(guild, "animation") == null) return null;
        if (tab) return prefix(tabKey(guild.peaceful(), plugin.peace().protects(player.getUniqueId(), System.currentTimeMillis())), guild) + decoratedName;
        return prefix(guild.peaceful() ? (plugin.peace().protects(player.getUniqueId(), System.currentTimeMillis())
                ? "peace-chat-prefix" : "peace-pending-prefix") : "chat-prefix", guild) + decoratedName;
    }
    private String prefix(String key, Guild guild) {
        return plugin.styles().active(guild, "animation") != null
                ? animatedPrefix(plugin.messages(), key, plugin.styles().tag(guild))
                : prefix(plugin.messages(), key, plugin.styles().tag(guild));
    }
    /** Рамка и готовый RGB-кадр — отдельные компоненты: внешний gradient не перекрашивает тело. */
    static String animatedPrefix(ru.cherdakmd.chrdkregister.Messages messages, String key, String tag) {
        var mini = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();
        return mini.serialize(net.kyori.adventure.text.Component.empty()
                .append(messages.get(key + "-open"))
                .append(mini.deserialize(tag))
                .append(messages.get(key + "-close")));
    }
    /** Общий путь чата/TAB: кадр вставляется как серверная разметка, не как ввод игрока. */
    static String prefix(ru.cherdakmd.chrdkregister.Messages messages, String key, String tag) {
        // Legacy-коды разрешены только в шаблоне владельца, не в подставляемом названии.
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(messages.get(key,
                "tag", ru.cherdakmd.chrdkregister.Messages.trusted(tag)));
    }
}
