package ru.cherdakmd.chrdkprofessions;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

/**
 * Плейсхолдеры PlaceholderAPI: {@code %chrdkprofessions_...%} (1.4.282).
 *
 * <p>Таб и скорборд спрашивают их часто, поэтому здесь только чтение готового
 * состояния. Выбор профессии отменён: {@code profession}, {@code level},
 * {@code xp} и соседние ключи показывают ремесло с наибольшим опытом, а
 * {@code income_today}/{@code income_total} — заработок по всем восьми сразу.</p>
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
        Profession active = id == null ? null : service.primary(id);
        if (key.equals("profession")) {
            return active == null ? "" : plugin.messages().plain("profession." + active.id() + ".name");
        }
        if (key.equals("profession_id")) {
            return active == null ? "" : active.id();
        }
        if (id == null) {
            return "";
        }
        if (key.equals("income_today")) {
            return Long.toString(service.incomeToday(id, System.currentTimeMillis()));
        }
        if (key.equals("income_total")) {
            return Long.toString(service.incomeTotal(id));
        }
        if (key.equals("income_micro")) {
            return Long.toString(service.incomeMicro(id));
        }
        if (key.equals("income_rate")) {
            return Integer.toString(plugin.config().xpPerDiamond());
        }
        int level = service.level(id, active);
        int maxLevel = service.maxLevel(id);
        int needed = ProfessionMath.needed(level, maxLevel);
        return switch (key) {
            case "mastery" -> {
                var mastery = service.mastery(id, active);
                yield mastery == null ? "" : plugin.messages().plain(mastery.nameKey());
            }
            case "mastery_id" -> service.mastery(id, active) == null ? "" : service.mastery(id, active).id();
            case "work_cooldown" -> Long.toString(service.workCooldownLeft(id, active, System.currentTimeMillis()));
            case "profession_rank" -> Integer.toString(service.trials().rank(id,active));
            case "max_level" -> "100";
            case "power_cap" -> Integer.toString(service.powerCap(id));
            case "level" -> Integer.toString(level);
            case "rank" -> ProfessionText.rankPlain(plugin.messages(), level);
            case "xp" -> Integer.toString(service.progress(id, active));
            case "next" -> needed <= 0 ? "" : Integer.toString(
                    Math.max(0, needed - service.progress(id, active)));
            case "percent" -> Integer.toString(service.progressPercent(id, active));
            case "total" -> Integer.toString(service.totalXp(id));
            // 1.4.179: полоса опыта без разметки — для таба и скорбордов.
            case "bar" -> ProfessionText.barPlain(service.progressPercent(id, active));
            case "perk" -> {
                if(active.ordinal()>=Profession.FORESTER.ordinal())yield plugin.messages().plain("perk.utility");
                int value = ProfessionMath.perkValue(active, level, service.powerCap(id));
                yield value <= 0 ? plugin.messages().plain("perk.none")
                        : plugin.messages().plain("perk." + active.id(),
                        "value", String.valueOf(value));
            }
            case "daily_left" -> dailyLeft(service, id, active);
            case "cooldown" -> "0";
            default -> "";
        };
    }

    /** Остаток суточного потолка; пусто, если потолок выключен. */
    private static String dailyLeft(ProfessionService service, UUID id, Profession profession) {
        int left = service.dailyLeft(id, profession, System.currentTimeMillis());
        return left < 0 ? "" : Integer.toString(left);
    }
}
