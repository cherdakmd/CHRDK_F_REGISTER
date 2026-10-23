package ru.cherdakmd.chrdkorders;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.List;

/**
 * Раздел заказов в {@code /profile} (1.4.156).
 *
 * <p>Репутация у ботов, звание и сдачи — то, что игрок копил на доске. Заказы
 * общие, поэтому раздел читается и для оффлайн-игрока.</p>
 */
final class OrdersProfile implements ChrdkRegisterApi.ProfileProvider {

    private final OrdersPlugin plugin;

    OrdersProfile(OrdersPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public ChrdkRegisterApi.ProfileSection section(ChrdkRegisterApi.ProfileContext context) {
        if (context == null || plugin.store() == null) {
            return null;
        }
        OrdersStore.PlayerData data = plugin.store().player(context.playerId());
        if (data == null || (data.completed() <= 0 && data.reputation() <= 0)) {
            return null;
        }
        Messages messages = plugin.messages();
        List<Component> lines = new ArrayList<>();
        lines.add(messages.get("profile-reputation",
                "reputation", String.valueOf(data.reputation()),
                "rank", Messages.trusted(plugin.service().rankText(data.reputation()))));
        lines.add(messages.get("profile-completed",
                "completed", String.valueOf(data.completed())));
        lines.add(messages.get("profile-today",
                "today", String.valueOf(plugin.store().today(context.playerId(),
                        System.currentTimeMillis())),
                "limit", String.valueOf(plugin.config().maxPerDay())));
        return new ChrdkRegisterApi.ProfileSection(messages.raw("profile-title"),
                Material.PAPER, lines);
    }
}
