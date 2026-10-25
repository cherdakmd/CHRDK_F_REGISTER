package ru.cherdakmd.chrdkprofessions;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

/**
 * Плейсхолдеры PlaceholderAPI: {@code %chrdkprofessions_...%} (1.4.165).
 *
 * <p>Таб и скорборд спрашивают их часто, поэтому здесь только чтение готового
 * состояния: без записи и без создания записи игрока. У игрока без профессии —
 * пустая строка, а не «0»: иначе условие в скорборде срабатывало бы ложно и
 * показывало «кузнец 0 уровня» всем подряд.</p>
 */
public final class ProfessionsPlaceholders extends PlaceholderExpansion {

    private final ProfessionsPlugin plugin;

    ProfessionsPlaceholders(ProfessionsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "chrdkprofessions";
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
        ProfessionService service = plugin.service();
        if (service == null) {
            return "";
        }
        String key = params.toLowerCase(Locale.ROOT);
        if (key.equals("enabled")) {
            return plugin.config().enabled() ? "true" : "false";
        }
        if (key.equals("count")) {
            return Integer.toString(plugin.store().size());
        }
        if (key.startsWith("count_")) {
            Profession profession = Profession.parse(key.substring("count_".length()));
            return profession == null ? "" : Integer.toString(plugin.store().countOf(profession));
        }
        UUID id = player == null ? null : player.getUniqueId();
        Profession active = id == null ? null : service.active(id);
        if (key.equals("profession")) {
            return active == null ? "" : plugin.messages().plain("profession." + active.id() + ".name");
        }
        if (key.equals("profession_id")) {
            return active == null ? "" : active.id();
        }
        if (id == null || active == null) {
            return "";
        }
        int level = service.level(id, active);
        int maxLevel = service.maxLevel(id);
        int needed = ProfessionMath.needed(level, maxLevel);
        return switch (key) {
            case "level" -> Integer.toString(level);
            case "rank" -> ProfessionText.rankPlain(plugin.messages(), level);
            case "xp" -> Integer.toString(service.progress(id, active));
            case "next" -> needed <= 0 ? "" : Integer.toString(
                    Math.max(0, needed - service.progress(id, active)));
            case "percent" -> Integer.toString(service.progressPercent(id, active));
            case "total" -> Integer.toString(service.totalXp(id));
            case "perk" -> {
                int value = ProfessionMath.perkValue(active, level, maxLevel);
                yield value <= 0 ? plugin.messages().plain("perk.none")
                        : plugin.messages().plain("perk." + active.id(),
                        "value", String.valueOf(value));
            }
            case "daily_left" -> dailyLeft(service, id, active);
            case "cooldown" -> Long.toString(service.chooseCooldownLeft(id,
                    System.currentTimeMillis()));
            default -> "";
        };
    }

    /** Остаток суточного потолка; пусто, если потолок выключен. */
    private static String dailyLeft(ProfessionService service, UUID id, Profession profession) {
        int left = service.dailyLeft(id, profession, System.currentTimeMillis());
        return left < 0 ? "" : Integer.toString(left);
    }
}
