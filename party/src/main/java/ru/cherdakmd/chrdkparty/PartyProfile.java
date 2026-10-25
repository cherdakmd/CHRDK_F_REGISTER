package ru.cherdakmd.chrdkparty;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.List;

/**
 * Раздел groups в {@code /profile} (1.4.158).
 *
 * <p>Показывает состав и лидера: по карточке сразу видно, с кем игрок ходит в
 * мир. Одиночке раздел не показывается — пустые разделы ядро всё равно
 * скрывает, но лучше не отдавать их вовсе.</p>
 */
final class PartyProfile implements ChrdkRegisterApi.ProfileProvider {

    private final PartyPlugin plugin;

    PartyProfile(PartyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public int order() {
        return 25;
    }

    @Override
    public ChrdkRegisterApi.ProfileSection section(ChrdkRegisterApi.ProfileContext context) {
        if (context == null || plugin.service() == null) {
            return null;
        }
        Party party = plugin.service().of(context.playerId());
        if (party == null) {
            return null;
        }
        Messages messages = plugin.messages();
        List<Component> lines = new ArrayList<>();
        lines.add(messages.get("profile-size",
                "size", String.valueOf(party.size()),
                "max", String.valueOf(plugin.config().maxSize()),
                "bonus", String.valueOf(plugin.service().potentialBonus(party))));
        lines.add(messages.get("profile-leader", "leader", party.leaderName()));
        return new ChrdkRegisterApi.ProfileSection(messages.raw("profile-title"),
                Material.NAME_TAG, lines);
    }
}
