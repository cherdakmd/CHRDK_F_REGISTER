package ru.cherdakmd.chrdkranks;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Тик-сервис: засчитывает наигранное время, вручает звания с церемонией
 * и раскладывает игроков по командам scoreboard для сортировки TAB.
 */
final class RanksService {

    private final RanksPlugin plugin;
    private final Map<UUID, Integer> assignedTier = new HashMap<>();

    RanksService(RanksPlugin plugin) {
        this.plugin = plugin;
    }

    /** Один тик учёта: +tickSeconds всем онлайн-игрокам, потом звание и команда. */
    void tick(int tickSeconds) {
        if (tickSeconds <= 0) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            long total = plugin.store().addSeconds(player.getUniqueId(), tickSeconds);
            evaluate(player, total);
        }
    }

    /** Применить звание/команду к одному игроку (join, /ranksadmin set). */
    void evaluate(Player player, long totalSeconds) {
        List<RankLadder.Rank> ladder = plugin.config().ladder();
        int tier = RankLadder.tierIndex(ladder, totalSeconds);
        awardIfNew(player, tier);
        if (plugin.config().sortTab()) {
            assignTeam(player, tier);
        }
    }

    /** Церемония: сообщение + консольные награды — один раз на звание. */
    private void awardIfNew(Player player, int tier) {
        if (tier < 0) {
            return;
        }
        List<RankLadder.Rank> ladder = plugin.config().ladder();
        RankLadder.Rank rank = ladder.get(tier);
        if (!plugin.store().markAwarded(player.getUniqueId(), rank.id())) {
            return; // это звание уже вручалось
        }
        plugin.messages().send(player, "earned-self", "rank", rank.displayName());
        if (plugin.config().announce()) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.equals(player)) {
                    plugin.messages().send(online, "earned-broadcast",
                            "player", player.getName(), "rank", rank.displayName());
                }
            }
        }
        for (String command : rank.commands()) {
            String parsed = command.replace("%player%", player.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
        }
    }

    /** Команда TAB-сортировки по текущему званию. Межзвание снимает старую команду. */
    private void assignTeam(Player player, int tier) {
        Integer previous = assignedTier.get(player.getUniqueId());
        if (previous != null && previous == tier) {
            return; // команда уже правильная — не дёргаем сеть
        }
        assignedTier.put(player.getUniqueId(), tier);
        Scoreboard board = Bukkit.getScoreboardManager() == null
                ? null : Bukkit.getScoreboardManager().getMainScoreboard();
        if (board == null) {
            return;
        }
        // снять со всех наших команд (игрок мог «спуститься» после /ranksadmin set)
        for (Team team : board.getTeams()) {
            if (RankLadder.isManagedTeam(team.getName()) && team.hasEntry(player.getName())) {
                team.removeEntry(player.getName());
            }
        }
        String teamName = RankLadder.teamNameFor(tier);
        if (teamName == null) {
            return; // без звания — обычная сортировка
        }
        Team team = board.getTeam(teamName);
        if (team == null) {
            team = board.registerNewTeam(teamName);
        }
        team.addEntry(player.getName());
    }

    /** Зачистка при выгрузке: убрать управляемые команды целиком. */
    void clearManagedTeams() {
        assignedTier.clear();
        if (Bukkit.getScoreboardManager() == null) {
            return;
        }
        for (Team team : Bukkit.getScoreboardManager().getMainScoreboard().getTeams()) {
            if (RankLadder.isManagedTeam(team.getName())) {
                team.unregister();
            }
        }
    }

    /** При смене лестницы через reload: сбросить кэш назначений и пересадить онлайн. */
    void refreshAllOnline() {
        assignedTier.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            evaluate(player, plugin.store().secondsOf(player.getUniqueId()));
            // устаревшие id вручённых званий после смены лестницы
            plugin.store().clearAwarded(player.getUniqueId(),
                    new java.util.HashSet<>(plugin.config().ladder().stream()
                            .map(RankLadder.Rank::id).toList()));
        }
    }
}
