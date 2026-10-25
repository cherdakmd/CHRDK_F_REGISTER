package ru.cherdakmd.chrdktierforge;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Плейсхолдеры PlaceholderAPI для таб-листа, скорбордов и голограмм (1.4.152).
 *
 * <p>Только память — никаких запросов к профилям и файлам: плейсхолдер может
 * спрашиваться десятки раз в секунду. Книга игрока адресуется UUID, поэтому
 * офлайн-ник отдаёт те же значения.</p>
 *
 * <ul>
 *   <li>{@code %chrdktierforge_title%} — титул в MiniMessage ({@code <gold>Кузнец</gold>}) или пусто;</li>
 *   <li>{@code %chrdktierforge_title_plain%} — титул без разметки («Кузнец»);</li>
 *   <li>{@code %chrdktierforge_title_id%} — ид раздела ({@code tiers|affixes|properties|all});</li>
 *   <li>{@code %chrdktierforge_title_choice%} — выбор игрока ({@code auto|off|ид});</li>
 *   <li>{@code %chrdktierforge_found%} / {@code _total%} — собрано позиций / весь каталог;</li>
 *   <li>{@code %chrdktierforge_percent%} — процент коллекции;</li>
 *   <li>{@code %chrdktierforge_tiers% / _properties% / _affixes%} — счётчики по разделам;</li>
 *   <li>{@code %chrdktierforge_next%} — ближайший незакрытый раздел, {@code _next_left%} — сколько до него;</li>
 *   <li>{@code %chrdktierforge_players%} — сколько книг вообще есть на сервере.</li>
 * </ul>
 */
public final class TierForgePlaceholders extends PlaceholderExpansion {

    private final TierForgePlugin plugin;

    TierForgePlaceholders(TierForgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "chrdktierforge";
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
        String key = params.toLowerCase(Locale.ROOT);
        TierCollectionService collection = plugin.collection();
        if (collection == null) {
            return "";
        }
        if ("players".equals(key)) {
            return Integer.toString(collection.keeper().players());
        }
        if (player == null) {
            return "";
        }
        TierCollectionBook.Progress progress = collection.progress(player.getUniqueId());
        String section = collection.shownSection(player.getUniqueId());
        return switch (key) {
            case "title" -> section == null ? "" : plugin.messages().raw("title." + section);
            case "title_plain" -> section == null ? "" : plugin.messages().plain("title." + section);
            case "title_id" -> section == null ? "" : section;
            case "title_choice" -> {
                String choice = collection.choice(player.getUniqueId());
                yield choice == null ? TierCollectionTitles.AUTO : choice;
            }
            case "found" -> Integer.toString(progress.total());
            case "total" -> Integer.toString(TierCollectionBook.CATALOG_TOTAL);
            case "percent" -> Integer.toString(progress.percent());
            case "tiers" -> Integer.toString(progress.tiers());
            case "properties" -> Integer.toString(progress.properties());
            case "affixes" -> Integer.toString(progress.affixes());
            case "next" -> nextName(progress);
            case "next_left" -> {
                String next = TierCollectionTitles.next(progress);
                yield next == null ? "0" : Integer.toString(
                        TierCollectionConfig.threshold(next) - progress.count(next));
            }
            default -> null;
        };
    }

    /** Имя ближайшего незакрытого раздела без разметки; пусто — закрыто всё. */
    private String nextName(TierCollectionBook.Progress progress) {
        String next = TierCollectionTitles.next(progress);
        if (next == null) {
            return "";
        }
        return switch (next) {
            case "tiers" -> plugin.messages().plain("collection-section-tiers");
            case "affixes" -> plugin.messages().plain("collection-section-affixes");
            case "properties" -> plugin.messages().plain("collection-section-properties");
            default -> plugin.messages().plain("collection-section-all");
        };
    }
}
