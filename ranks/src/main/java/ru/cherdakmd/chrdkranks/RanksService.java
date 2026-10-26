package ru.cherdakmd.chrdkranks;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.List;

/**
 * Тик-сервис: засчитывает наигранное время, вручает звания с церемонией
 * и раскладывает игроков по командам scoreboard для сортировки TAB.
 */
final class RanksService {

    private final RanksPlugin plugin;

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
        // Произвольные команды наград могли выдавать платные группы через LP/алиасы.
        // Звание за стаж остаётся заслуженным оформлением, но не донатным рангом.
        var api = Bukkit.getServicesManager().load(ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi.class);
        if (api != null) api.grantRewardBoostHours(player.getName(), Math.clamp(plugin.getConfig().getInt("rewards.boost-hours", 1), 0, 24), "playtime:" + rank.id());

    }

    /**
     * Команда TAB-сортировки: сначала донатный ранг, потом звание за стаж
     * (1.4.183).
     *
     * <p>Раньше команда кодировала только звание, и оплаченный ранг в списке
     * ничего не значил — Покровитель мог стоять под Странником. Теперь имя
     * команды собирает {@link RankLadder#teamNameFor(int, int)}. Чужие команды
     * (закреп ядра, LuckPerms, TAB) не трогаем: там свои правила.</p>
     */
    private void assignTeam(Player player, int tier) {
        Scoreboard board = Bukkit.getScoreboardManager() == null
                ? null : Bukkit.getScoreboardManager().getMainScoreboard();
        if (board == null) {
            return;
        }
        Team current = board.getEntryTeam(player.getName());
        if (current != null && !RankLadder.isManagedTeam(current.getName())) {
            return; // игрок закреплён другой командой — не спорим
        }
        String teamName = RankLadder.teamNameFor(plugin.rankLevel(player), tier);
        if (current != null && teamName.equals(current.getName())) {
            return; // уже в нужной команде — скорборд не трогаем
        }
        // снять со всех наших команд (игрок мог «спуститься» после /ranksadmin set)
        for (Team team : board.getTeams()) {
            if (RankLadder.isManagedTeam(team.getName()) && team.hasEntry(player.getName())) {
                team.removeEntry(player.getName());
            }
        }
        Team team = board.getTeam(teamName);
        if (team == null) {
            team = board.registerNewTeam(teamName);
        }
        team.addEntry(player.getName());
    }

    /** Зачистка при выгрузке: убрать управляемые команды целиком. */
    void clearManagedTeams() {
        if (Bukkit.getScoreboardManager() == null) {
            return;
        }
        for (Team team : Bukkit.getScoreboardManager().getMainScoreboard().getTeams()) {
            if (RankLadder.isManagedTeam(team.getName())) {
                team.unregister();
            }
        }
    }

    /** При смене лестницы через reload: пересадить онлайн в новые команды. */
    void refreshAllOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            evaluate(player, plugin.store().secondsOf(player.getUniqueId()));
            // устаревшие id вручённых званий после смены лестницы
            plugin.store().clearAwarded(player.getUniqueId(),
                    new java.util.HashSet<>(plugin.config().ladder().stream()
                            .map(RankLadder.Rank::id).toList()));
        }
    }
}
