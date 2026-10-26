package ru.cherdakmd.chrdkstreams;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Раздел стримерской программы в {@code /profile} (1.4.156).
 *
 * <p>Сколько часов ранга накоплено эфирами и сколько минут осталось до
 * следующего дня. Привязка хранится по UUID, поэтому раздел виден и оффлайн.</p>
 */
final class StreamsProfile implements ChrdkRegisterApi.ProfileProvider {

    private final StreamPlugin plugin;

    StreamsProfile(StreamPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public ChrdkRegisterApi.ProfileSection section(ChrdkRegisterApi.ProfileContext context) {
        if (context == null || plugin.store() == null) {
            return null;
        }
        Optional<StreamBinding> found = plugin.store().get(context.playerId());
        if (found.isEmpty()) {
            return null;
        }
        StreamBinding binding = found.get();
        StreamRewards.State state = binding.state() == null
                ? StreamRewards.State.empty() : binding.state();
        if (state.totalHours() <= 0 && state.pendingMillis() <= 0L) {
            return null;
        }
        List<Component> lines = new ArrayList<>();
        if (state.totalHours() > 0) {
            lines.add(plugin.messages().get("profile-hours",
                    "hours", String.valueOf(state.totalHours())));
        }
        if (state.pendingMillis() > 0L) {
            lines.add(plugin.messages().get("profile-pending",
                    "minutes", String.valueOf(state.pendingMillis() / 60_000L)));
        }
        if (binding.channels() != null && !binding.channels().isEmpty()) {
            lines.add(plugin.messages().get("profile-channels",
                    "count", String.valueOf(binding.channels().size())));
        }
        return new ChrdkRegisterApi.ProfileSection(plugin.messages().raw("profile-title"),
                Material.AMETHYST_SHARD, lines);
    }
}
