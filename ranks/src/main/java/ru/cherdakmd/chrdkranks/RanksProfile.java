package ru.cherdakmd.chrdkranks;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.List;

/**
 * Раздел стажа в {@code /profile} (1.4.156).
 *
 * <p>Наигранные часы, текущее звание и сколько осталось до следующего. Стаж
 * лежит в {@code ranks-data.json} по UUID, поэтому работает и для игрока,
 * которого сейчас нет на сервере.</p>
 */
final class RanksProfile implements ChrdkRegisterApi.ProfileProvider {

    private final RanksPlugin plugin;

    RanksProfile(RanksPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public ChrdkRegisterApi.ProfileSection section(ChrdkRegisterApi.ProfileContext context) {
        if (context == null || plugin.store() == null || plugin.config() == null) {
            return null;
        }
        long seconds = plugin.store().secondsOf(context.playerId());
        if (seconds <= 0L) {
            return null;
        }
        long hours = seconds / 3600L;
        List<RankLadder.Rank> ladder = plugin.config().ladder();
        int tier = RankLadder.tierIndex(ladder, seconds);
        Messages messages = plugin.messages();
        List<Component> lines = new ArrayList<>();
        lines.add(messages.get("profile-playtime", "hours", String.valueOf(hours)));
        if (tier < 0 || tier >= ladder.size()) {
            lines.add(messages.get("profile-no-rank"));
        } else {
            lines.add(messages.get("profile-rank",
                    "rank", Messages.trusted(ladder.get(tier).displayName())));
        }
        RankLadder.NextRank next = RankLadder.nextUp(ladder, seconds);
        if (next != null) {
            lines.add(messages.get("profile-next",
                    "rank", Messages.trusted(next.rank().displayName()),
                    "hours", String.valueOf(next.hoursLeft())));
        }
        return new ChrdkRegisterApi.ProfileSection(messages.raw("profile-title"),
                Material.CLOCK, lines);
    }
}
