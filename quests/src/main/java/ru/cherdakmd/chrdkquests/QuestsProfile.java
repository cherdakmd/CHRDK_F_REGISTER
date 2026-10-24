package ru.cherdakmd.chrdkquests;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Раздел «Задания» в {@code /profile} (1.4.161).
 *
 * <p>Фракция, ранг, репутация и текущие задания. Игроку без фракции раздел
 * всё равно показывается: пустая карточка объясняет, куда идти, — иначе о
 * квестах узнают только те, кто случайно набрал команду.</p>
 */
final class QuestsProfile implements ChrdkRegisterApi.ProfileProvider {

    private final QuestsPlugin plugin;

    QuestsProfile(QuestsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public int order() {
        return 35;
    }

    @Override
    public ChrdkRegisterApi.ProfileSection section(ChrdkRegisterApi.ProfileContext context) {
        if (context == null || plugin.service() == null) {
            return null;
        }
        Messages messages = plugin.messages();
        UUID id = context.playerId();
        QuestFaction faction = plugin.service().faction(id);
        List<Component> lines = new ArrayList<>();
        if (faction == null) {
            lines.add(messages.get("profile-none"));
        } else {
            int reputation = plugin.service().reputation(id);
            lines.add(messages.get("profile-faction",
                    "faction", faction.name(),
                    "rank", messages.plain("rank." + plugin.service().rankId(id))));
            lines.add(messages.get("profile-reputation",
                    "reputation", String.valueOf(reputation),
                    "next", faction.nextThreshold(reputation) < 0
                            ? messages.plain("faction-max")
                            : String.valueOf(faction.nextThreshold(reputation))));
        }
        lines.add(messages.get("profile-progress",
                "done", String.valueOf(plugin.service().doneCount(id)),
                "total", String.valueOf(plugin.service().totalQuests())));
        lines.add(messages.get("profile-claims",
                "left", String.valueOf(plugin.service().claimsLeft(id, plugin.today())),
                "limit", String.valueOf(plugin.config().claimsPerDay())));
        List<Quest> current = plugin.service().currentQuests(id);
        int shown = 0;
        Player online = context.online() ? Bukkit.getPlayer(id) : null;
        for (Quest quest : current) {
            if (shown >= 3) {
                break;
            }
            int have = quest.type() == QuestType.GATHER && online != null
                    ? Math.min(plugin.countItems(online, quest), quest.amount())
                    : plugin.service().progress(id, quest);
            lines.add(messages.get("profile-quest",
                    "name", quest.name(),
                    "have", String.valueOf(have),
                    "need", String.valueOf(quest.amount())));
            shown++;
        }
        return new ChrdkRegisterApi.ProfileSection(messages.raw("profile-title"),
                faction == null ? Material.WRITABLE_BOOK : faction.icon(), lines);
    }
}
