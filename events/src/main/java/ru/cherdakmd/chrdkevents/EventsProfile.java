package ru.cherdakmd.chrdkevents;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.List;

/**
 * Раздел «Мировые события» в {@code /profile} (1.4.160).
 *
 * <p>Игрок видит, что идёт сейчас и сколько осталось, когда ждать следующее и
 * сколько событий сервер уже провёл. Раздел общий для всех, но это и есть его
 * смысл: событие — общая новость, а не личная награда.</p>
 */
final class EventsProfile implements ChrdkRegisterApi.ProfileProvider {

    private final EventsPlugin plugin;

    EventsProfile(EventsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public ChrdkRegisterApi.ProfileSection section(ChrdkRegisterApi.ProfileContext context) {
        if (context == null || plugin.service() == null) {
            return null;
        }
        Messages messages = plugin.messages();
        EventService service = plugin.service();
        long now = System.currentTimeMillis();
        List<Component> lines = new ArrayList<>();
        WorldEvent current = service.current();
        if (current != null) {
            lines.add(messages.get("profile-active",
                    "name", current.name(),
                    "time", EventText.clock(service.secondsLeft(now))));
            if (current.fallingStars()) {
                lines.add(messages.get("profile-meteors"));
            }
            if (current.hordeActive()) {
                lines.add(messages.get("profile-horde", "multiplier",
                        String.format(java.util.Locale.ROOT, "%.1f",
                                current.horde().multiplier())));
            }
        } else {
            lines.add(messages.get("profile-none"));
            long seconds = service.secondsToNext(now);
            if (seconds > 0L) {
                lines.add(messages.get("profile-next", "time", EventText.clock(seconds)));
            }
        }
        lines.add(messages.get("profile-stats",
                "total", String.valueOf(plugin.store().totalAll())));
        return new ChrdkRegisterApi.ProfileSection(messages.raw("profile-title"),
                Material.CLOCK, lines);
    }
}
