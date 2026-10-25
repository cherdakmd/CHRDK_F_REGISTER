package ru.cherdakmd.chrdkquests;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

/**
 * Плейсхолдеры PlaceholderAPI: {@code %chrdkquests_...%} (1.4.161).
 *
 * <p>Только чтение готового состояния: таб и скорборд спрашивают их часто,
 * поэтому здесь нет ни обхода инвентаря, ни пересчёта цепочек. Прогресс
 * конкретного задания доступен как {@code quests_<ид>}.</p>
 */
public final class QuestsPlaceholders extends PlaceholderExpansion {

    private final QuestsPlugin plugin;

    QuestsPlaceholders(QuestsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "chrdkquests";
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
        if (player == null) {
            return switch (key) {
                case "total" -> Integer.toString(plugin.service().totalQuests());
                case "claims_limit" -> Integer.toString(plugin.config().claimsPerDay());
                default -> "";
            };
        }
        UUID id = player.getUniqueId();
        QuestFaction faction = plugin.service().faction(id);
        if (key.startsWith("quest_")) {
            Quest quest = plugin.service().find(key.substring("quest_".length()));
            return quest == null ? "0" : Integer.toString(plugin.service().progress(id, quest));
        }
        return switch (key) {
            case "faction" -> faction == null ? "" : faction.name();
            case "faction_id" -> plugin.service().factionId(id);
            case "rank" -> faction == null ? "" : plugin.messages().plain(
                    "rank." + plugin.service().rankId(id));
            case "rank_number" -> Integer.toString(plugin.service().rankNumber(id));
            case "reputation" -> Integer.toString(plugin.service().reputation(id));
            case "done" -> Integer.toString(plugin.service().doneCount(id));
            case "total" -> Integer.toString(plugin.service().totalQuests());
            case "available" -> Integer.toString(plugin.service().currentQuests(id).size());
            case "ready" -> Integer.toString(plugin.service().readyCount(id));
            case "claims_left" -> Integer.toString(plugin.service().claimsLeft(id, plugin.today()));
            case "claims_limit" -> Integer.toString(plugin.config().claimsPerDay());
            default -> "";
        };
    }
}
