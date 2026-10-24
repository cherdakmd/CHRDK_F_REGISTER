package ru.cherdakmd.chrdkevents;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Плейсхолдеры PlaceholderAPI: {@code %chrdkevents_...%} (1.4.160).
 *
 * <p>Таб и скорборд спрашивают их часто, поэтому здесь только чтение готового
 * состояния: ни пересчёта расписания, ни обхода игроков.</p>
 *
 * <ul>
 *   <li>{@code active} — да/нет;</li>
 *   <li>{@code name} / {@code kind} — что идёт;</li>
 *   <li>{@code time_left} / {@code seconds_left} — остаток текущего события;</li>
 *   <li>{@code next} / {@code next_seconds} — до следующего события;</li>
 *   <li>{@code total} — сколько событий провёл сервер;</li>
 *   <li>{@code last} — имя последнего прошедшего события.</li>
 * </ul>
 */
public final class EventsPlaceholders extends PlaceholderExpansion {

    private final EventsPlugin plugin;

    EventsPlaceholders(EventsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "chrdkevents";
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
        EventService service = plugin.service();
        long now = System.currentTimeMillis();
        WorldEvent current = service.current();
        return switch (key) {
            case "active" -> current == null ? "нет" : "да";
            case "name" -> current == null ? "" : current.name();
            case "kind" -> current == null ? "" : current.kind().id();
            case "time_left" -> EventText.clock(service.secondsLeft(now));
            case "seconds_left" -> Long.toString(service.secondsLeft(now));
            case "next" -> EventText.clock(service.secondsToNext(now));
            case "next_seconds" -> Long.toString(service.secondsToNext(now));
            case "total" -> Integer.toString(plugin.store().totalAll());
            case "last" -> {
                EventsStore.Entry last = plugin.store().last();
                yield last == null ? "" : last.name();
            }
            case "enabled" -> plugin.config().enabled() ? "да" : "нет";
            default -> "";
        };
    }
}
