package ru.cherdakmd.chrdkgenerator;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Плейсхолдеры PlaceholderAPI для таб-листа, скорбордов и голограмм (п.31, §9).
 *
 * <p>Только память — никаких чтений мира: их спрашивают десятки раз в
 * секунду, в том числе асинхронно. Состояние камеры кэшируется тикером.</p>
 *
 * <ul>
 *   <li>{@code %chrdkgenerator_tier%} — тир 1…15, 0 — генератора нет;</li>
 *   <li>{@code %chrdkgenerator_fill%} — заполненность бункера, % (0…100);</li>
 *   <li>{@code %chrdkgenerator_rate%} — цикл производства, секунд;</li>
 *   <li>{@code %chrdkgenerator_status%} — состояние камеры текстом;</li>
 *   <li>{@code %chrdkgenerator_tier_progress%} — прогресс до следующего тира, %
 *       (доля копилки от цены шага; 100 на максимуме);</li>
 *   <li>{@code %chrdkgenerator_eta%} — время до полного бункера («3ч 20м», «—»).</li>
 * </ul>
 */
public final class GeneratorPlaceholders extends PlaceholderExpansion {

    private final GeneratorPlugin plugin;

    GeneratorPlaceholders(GeneratorPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "chrdkgenerator";
    }

    @Override
    public @NotNull String getAuthor() {
        return "cherdakmd";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    /** Переживает {@code /papi reload}: плейсхолдеры не пропадают до перезапуска. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return null;
        }
        GeneratorStore.Entry entry = plugin.combinedBunkerEntry(plugin.store().entry(player.getUniqueId()));
        return switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "tier" -> String.valueOf(GeneratorStats.tier(entry));
            case "fill" -> String.valueOf(GeneratorStats.fillPercent(entry, plugin.config()));
            case "rate" -> String.valueOf(GeneratorStats.cycleSeconds(entry, plugin.config()));
            case "status" -> status(entry);
            case "tier_progress" -> String.valueOf(GeneratorStats.tierProgressPercent(entry, plugin.config()));
            case "eta" -> GeneratorStats.etaText(entry, plugin.config());
            default -> null;
        };
    }

    private String status(GeneratorStore.Entry entry) {
        if (entry == null) {
            return "—";
        }
        if (!entry.installed()) {
            return plugin.messages().plain("state-carried");
        }
        return plugin.messages().plain(plugin.stateOf(entry.owner()));
    }
}
