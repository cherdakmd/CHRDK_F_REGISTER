package ru.cherdakmd.chrdkquests;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.UUID;

/**
 * Префикс фракции в чате (1.4.161).
 *
 * <p>Мягкая косметика: префикс появляется только у тех, кто вступил во фракцию
 * и добрался до нужного ранга. Он не даёт ни урона, ни денег — но видно, кто
 * есть кто. Ядро применяет провайдера поверх своего оформления, поэтому тег
 * каталога и префикс не конфликтуют.</p>
 */
final class QuestsNameTag implements ChrdkRegisterApi.NameTagProvider {

    private final QuestsPlugin plugin;

    QuestsNameTag(QuestsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String decorate(String playerName, String decoratedName) {
        if (!plugin.config().factionPrefix() || playerName == null) {
            return null;
        }
        Player player = Bukkit.getPlayerExact(playerName);
        if (player == null) {
            return null;
        }
        UUID id = player.getUniqueId();
        QuestFaction faction = plugin.service().faction(id);
        if (faction == null || !faction.hasPrefix()) {
            return null;
        }
        if (plugin.service().rankNumber(id) < plugin.config().prefixFromRank()) {
            return null;
        }
        return faction.prefix() + decoratedName;
    }
}
