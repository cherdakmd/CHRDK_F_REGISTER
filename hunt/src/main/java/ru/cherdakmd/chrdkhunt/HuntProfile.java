package ru.cherdakmd.chrdkhunt;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.List;

/**
 * Раздел охоты в {@code /profile} (1.4.156).
 *
 * <p>Показывает то, чем охотник гордится: кошелёк, звание, убийства, трофеи и
 * запас осколков с ключами. Игрок без единого убийства раздела не получает —
 * пустые строки ядро всё равно скрывает.</p>
 */
final class HuntProfile implements ChrdkRegisterApi.ProfileProvider {

    private final HuntPlugin plugin;

    HuntProfile(HuntPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public ChrdkRegisterApi.ProfileSection section(ChrdkRegisterApi.ProfileContext context) {
        if (context == null || plugin.store() == null) {
            return null;
        }
        HuntStore.PlayerData data = plugin.store().player(context.playerId());
        if (data == null || (data.earned() <= 0L && data.killsTotal() <= 0L
                && data.trophies().isEmpty())) {
            return null;
        }
        Messages messages = plugin.messages();
        List<Component> lines = new ArrayList<>();
        lines.add(messages.get("profile-coins",
                "coins", String.valueOf(data.coins()),
                "earned", String.valueOf(data.earned())));
        lines.add(messages.get("profile-rank",
                "rank", Messages.trusted(plugin.service().rankText(data.earned())),
                "earned", String.valueOf(data.earned())));
        lines.add(messages.get("profile-kills",
                "kills", String.valueOf(data.killsTotal()),
                "trophies", String.valueOf(data.trophies().size()),
                "total", String.valueOf(plugin.config() == null
                        ? 0 : plugin.config().trophies().size())));
        lines.add(messages.get("profile-stock",
                "shards", String.valueOf(plugin.store().totalShards(context.playerId())),
                "keys", String.valueOf(plugin.store().totalKeys(context.playerId()))));
        HuntConfig config = plugin.config();
        if (config != null && !config.bestiary().empty()) {
            lines.add(messages.get("profile-bestiary",
                    "seen", String.valueOf(plugin.store().bestiarySeen(context.playerId())),
                    "total", String.valueOf(config.bestiary().totalEntries())));
        }
        return new ChrdkRegisterApi.ProfileSection(messages.raw("profile-title"),
                Material.GOLD_NUGGET, lines);
    }
}
