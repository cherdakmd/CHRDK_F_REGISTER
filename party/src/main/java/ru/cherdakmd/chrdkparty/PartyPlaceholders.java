package ru.cherdakmd.chrdkparty;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

/**
 * Плейсхолдеры PlaceholderAPI: {@code %chrdkparty_...%} (1.4.158).
 *
 * <p>Только данные группы: спрашивать их могут таб и скорборд десятки раз в
 * секунду, поэтому здесь нет ни обхода мира, ни расчёта расстояний — бонус
 * показывается потенциальный (по составу), а не «сколько сейчас рядом».</p>
 *
 * <ul>
 *   <li>{@code in_party} — да/нет;</li>
 *   <li>{@code size} / {@code max} — состав и потолок;</li>
 *   <li>{@code leader} — имя лидера;</li>
 *   <li>{@code bonus} — максимальный бонус группы в процентах;</li>
 *   <li>{@code invites} — сколько приглашений ждёт игрока;</li>
 *   <li>{@code members} — имена через запятую.</li>
 * </ul>
 */
public final class PartyPlaceholders extends PlaceholderExpansion {

    private final PartyPlugin plugin;

    PartyPlaceholders(PartyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "chrdkparty";
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
        if ("max".equals(key)) {
            return Integer.toString(plugin.config().maxSize());
        }
        if (player == null) {
            return switch (key) {
                case "in_party" -> "нет";
                case "size", "bonus", "invites" -> "0";
                default -> "";
            };
        }
        UUID id = player.getUniqueId();
        Party party = plugin.service().of(id);
        if ("invites".equals(key)) {
            return Integer.toString(plugin.service().invitesFor(id, System.currentTimeMillis()));
        }
        if (party == null) {
            return switch (key) {
                case "in_party" -> "нет";
                case "size", "bonus" -> "0";
                default -> "";
            };
        }
        return switch (key) {
            case "in_party" -> "да";
            case "size" -> Integer.toString(party.size());
            case "leader" -> party.leaderName();
            case "bonus" -> Integer.toString(plugin.service().potentialBonus(party));
            case "members" -> String.join(", ", party.members().values());
            default -> "";
        };
    }
}
