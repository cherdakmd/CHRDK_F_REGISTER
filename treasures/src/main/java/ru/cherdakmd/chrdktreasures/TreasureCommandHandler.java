package ru.cherdakmd.chrdktreasures;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /treasures} — витрина, коллекция, статистика, топ, сеты и настройки;
 * {@code /treasuresadmin} — выдача, сброс, шансы, полная очистка и reload.
 *
 * <p>Игровые подкоманды не раскрывают названия ненайденных предметов: до
 * находки виден только ид и редкость, иначе витрина потеряла бы смысл.
 * Админские операции пишутся в лог с ником администратора — выданный предмет
 * неотличим от найденного в коллекции, и по логу видно, откуда он взялся.</p>
 */
public final class TreasureCommandHandler implements CommandExecutor, TabCompleter {

    /** Сколько строк отчёта о бочках показывать: чат не резиновый. */
    private static final int BARREL_LIST_LIMIT = 10;

    private final TreasuresPlugin plugin;
    private final TreasureService service;
    private final TreasuresGui gui;

    public TreasureCommandHandler(TreasuresPlugin plugin, TreasureService service,
                                  TreasuresGui gui) {
        this.plugin = plugin;
        this.service = service;
        this.gui = gui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("treasures")) {
            return handlePlayer(sender, args);
        }
        if (command.getName().equalsIgnoreCase("treasuresadmin")) {
            return handleAdmin(sender, args);
        }
        return false;
    }

    /* --------------------------------- игроки --------------------------------- */

    private boolean handlePlayer(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdktreasures.use")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(sender, "treasures-disabled");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("menu")) {
            if (!(sender instanceof Player player)) {
                plugin.messages().send(sender, "player-only");
                return true;
            }
            gui.open(player);
            return true;
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "help" -> {
                help(player);
                yield true;
            }
            case "list" -> list(player, args.length > 1 ? args[1] : null);
            case "top" -> top(player);
            case "loot" -> loot(player, args.length > 1 ? args[1] : null);
            case "set", "sets", "сет", "сеты" -> sets(player, args.length > 1 ? args[1] : null);
            // 1.4.153: активные клад-карты и подсказки по ним.
            // 1.4.180: без аргумента — витрина карт, «list» — список в чат.
            case "map", "maps", "карта", "карты" ->
                    maps(player, args.length > 1 ? args[1] : null);
            case "stats" -> playerStats(player, args.length > 1 ? args[1] : null);
            // 1.4.166: обменник осколков кладов.
            case "shards", "осколки" -> shards(player);
            // 1.4.166: звание рядом с ником в чате и табе.
            case "badge", "звание", "титул" -> badge(player, args.length > 1 ? args[1] : null);
            case "toggle" -> toggle(player, args.length > 1 ? args[1] : null);
            default -> {
                plugin.messages().send(player, "unknown-subcommand", "sub", args[0]);
                yield true;
            }
        };
    }

    /**
     * {@code /treasures badge [auto|on|off]} — звание рядом с ником (1.4.166).
     *
     * <p>Без аргумента показывает текущее состояние и подсказку. Выбор хранится
     * в профиле, поэтому переживает перезаход и не сбрасывается при
     * {@code /treasuresadmin reload}.</p>
     */
    private boolean badge(Player player, String argument) {
        if (!plugin.config().rankTag().enabled()) {
            plugin.messages().send(player, "rank-tag-disabled");
            return true;
        }
        if (argument == null || argument.isBlank()) {
            plugin.messages().send(player, "rank-tag-current",
                    "state", Messages.trusted(plugin.rankTags().stateLabel(player)),
                    "rank", Messages.trusted(shownRank(player)));
            plugin.messages().send(player, "rank-tag-usage");
            return true;
        }
        TreasureRankTags.Choice choice = switch (argument.toLowerCase(Locale.ROOT)) {
            case "auto", "авто" -> TreasureRankTags.Choice.AUTO;
            case "on", "вкл", "включить" -> TreasureRankTags.Choice.ON;
            case "off", "выкл", "скрыть" -> TreasureRankTags.Choice.OFF;
            default -> null;
        };
        if (choice == null) {
            plugin.messages().send(player, "rank-tag-unknown", "value", argument);
            return true;
        }
        plugin.store().setRankTag(player.getUniqueId().toString(), choice);
        plugin.rankTags().refresh(player);
        if (choice != TreasureRankTags.Choice.OFF
                && plugin.rankTags().visibleRankLabel(player) == null) {
            // Порог показа выше звания игрока: честно говорим, почему метки нет.
            plugin.messages().send(player, "rank-tag-below",
                    "rank", Messages.trusted(shownRank(player)));
            return true;
        }
        plugin.messages().send(player, switch (choice) {
            case AUTO -> "rank-tag-set-auto";
            case ON -> "rank-tag-set-on";
            case OFF -> "rank-tag-set-off";
        }, "rank", Messages.trusted(shownRank(player)));
        return true;
    }

    /** Звание игрока для подсказок: подпись ранга, даже если оно скрыто. */
    private String shownRank(Player player) {
        return plugin.rankTags().rankLabel(player);
    }

    /** {@code /treasures shards} — баланс осколков и обменник (1.4.166). */
    private boolean shards(Player player) {
        if (!plugin.config().shards().enabled()) {
            plugin.messages().send(player, "shards-disabled");
            return true;
        }
        plugin.gui().openShards(player);
        return true;
    }

    /**
     * {@code /treasures map [list]} — активные карты (1.4.153).
     *
     * <p>С 1.4.180 без аргумента открывается витрина: карточки карт с полосой
     * раскопок и памятка «как искать». Список в чат никуда не делся —
     * {@code /treasures map list}: он удобен, когда карт несколько.</p>
     */
    private boolean maps(Player player, String argument) {
        if (!plugin.config().maps().enabled()) {
            plugin.messages().send(player, "map-disabled");
            return true;
        }
        if (argument != null
                && (argument.equalsIgnoreCase("list") || argument.equalsIgnoreCase("список"))) {
            plugin.maps().list(player);
            return true;
        }
        plugin.gui().openMaps(player);
        return true;
    }

    private void help(Player player) {
        plugin.messages().send(player, "help-header");
        plugin.messages().send(player, "help-menu");
        plugin.messages().send(player, "help-list");
        plugin.messages().send(player, "help-stats");
        plugin.messages().send(player, "help-stats-other");
        plugin.messages().send(player, "help-top");
        plugin.messages().send(player, "help-loot");
        plugin.messages().send(player, "help-set");
        plugin.messages().send(player, "help-map");
        plugin.messages().send(player, "help-shards");
        plugin.messages().send(player, "help-badge");
        plugin.messages().send(player, "help-toggle");
        plugin.messages().send(player, "help-footer");
    }

    /* ---------------------------------- сеты ---------------------------------- */

    /**
     * Показывает сетовые бонусы: сводку по всем регионам или разбор одного.
     * Прогресс считается по находкам в инвентаре, а не по коллекции: сет — это
     * награда за собранный набор, а не за факт находки.
     */
    private boolean sets(Player player, String filter) {
        if (!plugin.config().setsEnabled()) {
            plugin.messages().send(player, "set-disabled");
            return true;
        }
        if (filter == null || filter.isBlank() || filter.equalsIgnoreCase("all")) {
            plugin.messages().send(player, "set-header");
            for (TreasureSet set : TreasureSets.all()) {
                int pieces = plugin.sets().pieces(player, set.region());
                plugin.messages().send(player, "set-line",
                        "region", Messages.trusted(plugin.messages().raw(
                                "region." + set.region().id())),
                        "pieces", String.valueOf(pieces),
                        "total", String.valueOf(TreasureSet.FULL_PIECES));
            }
            TreasureSet active = plugin.sets().active(player);
            if (active == null) {
                plugin.messages().send(player, "set-none");
            } else {
                plugin.messages().send(player, "set-active",
                        "region", Messages.trusted(plugin.messages().raw(
                                "region." + active.region().id())));
            }
            plugin.messages().send(player, "set-hint");
            return true;
        }
        TreasureRegion region = TreasureRegion.byId(filter);
        TreasureSet set = TreasureSets.of(region);
        if (set == null) {
            plugin.messages().send(player, "unknown-filter", "filter", filter);
            return true;
        }
        int pieces = plugin.sets().pieces(player, region);
        plugin.messages().send(player, "set-detail-header",
                "region", Messages.trusted(plugin.messages().raw("region." + region.id())));
        plugin.messages().send(player, "set-detail-progress",
                "pieces", String.valueOf(pieces),
                "total", String.valueOf(TreasureSet.FULL_PIECES));
        plugin.messages().send(player, "set-partial",
                "pieces", String.valueOf(TreasureSet.PARTIAL_PIECES),
                "bonus", Messages.trusted(describe(set.partial())));
        plugin.messages().send(player, "set-full",
                "pieces", String.valueOf(TreasureSet.FULL_PIECES),
                "bonus", Messages.trusted(describe(set.full())));
        return true;
    }

    /** Строка бонуса из тех же текстов эффектов, что и лор предмета. */
    private String describe(List<TreasureEffect> effects) {
        List<String> parts = new ArrayList<>(effects.size());
        for (TreasureEffect effect : effects) {
            String key = "item-effect-"
                    + effect.kind().name().toLowerCase(Locale.ROOT).replace('_', '-');
            parts.add(plugin.messages().raw(key)
                    .replace("%value%", TreasureItems.format(effect)));
        }
        return String.join(" <dark_gray>·</dark_gray> ", parts);
    }

    /* ---------------------------------- список -------------------------------- */

    private boolean list(Player player, String filter) {
        if (filter == null || filter.isBlank() || filter.equalsIgnoreCase("all")) {
            plugin.messages().send(player, "list-header");
            for (TreasureRegion region : TreasureRegion.values()) {
                List<TreasureItemDef> items = TreasureRegistry.region(region);
                int found = 0;
                for (TreasureItemDef def : items) {
                    if (service.hasItem(player, def.id())) {
                        found++;
                    }
                }
                plugin.messages().send(player, "list-region-line",
                        "region", Messages.trusted(plugin.messages().raw("region." + region.id())),
                        "found", String.valueOf(found),
                        "total", String.valueOf(items.size()));
            }
            List<TreasureItemDef> mythical = TreasureRegistry.mythical();
            int mythic = 0;
            for (TreasureItemDef def : mythical) {
                if (service.hasItem(player, def.id())) {
                    mythic++;
                }
            }
            plugin.messages().send(player, "list-region-line",
                    "region", Messages.trusted(plugin.messages().raw("tier.mythical")),
                    "found", String.valueOf(mythic),
                    "total", String.valueOf(mythical.size()));
            plugin.messages().send(player, "list-footer");
            return true;
        }
        TreasureTier tier = TreasureTier.byId(filter);
        if (tier != null) {
            plugin.messages().send(player, "list-header");
            int shown = 0;
            for (TreasureItemDef def : TreasureRegistry.byTier(tier)) {
                itemLine(player, def);
                shown++;
            }
            if (shown == 0) {
                plugin.messages().send(player, "list-empty");
            }
            plugin.messages().send(player, "list-footer");
            return true;
        }
        TreasureRegion region = TreasureRegion.byId(filter);
        if (region != null) {
            plugin.messages().send(player, "list-header");
            for (TreasureItemDef def : TreasureRegistry.region(region)) {
                itemLine(player, def);
            }
            plugin.messages().send(player, "list-footer");
            return true;
        }
        plugin.messages().send(player, "unknown-filter", "filter", filter);
        return true;
    }

    /** Строка списка: найденное — с именем, ненайденное — только редкость и ид. */
    private void itemLine(Player player, TreasureItemDef def) {
        int count = service.itemCount(player, def.id());
        if (count > 0) {
            plugin.messages().send(player, "list-item-found",
                    "name", Messages.trusted(plugin.messages().raw("treasure." + def.id() + ".name")),
                    "id", def.id(),
                    "count", String.valueOf(count),
                    "tier", Messages.trusted(plugin.messages().raw("tier." + def.tier().id())));
        } else {
            plugin.messages().send(player, "list-item-locked",
                    "id", def.id(),
                    "tier", Messages.trusted(plugin.messages().raw("tier." + def.tier().id())));
        }
    }

    /* ----------------------------------- топ ---------------------------------- */

    private boolean top(Player player) {
        List<TreasureService.Entry> top = service.top();
        if (top.isEmpty()) {
            plugin.messages().send(player, "top-empty");
            return true;
        }
        plugin.messages().send(player, "top-header");
        int place = 1;
        for (TreasureService.Entry entry : top) {
            plugin.messages().send(player, "top-line",
                    "place", String.valueOf(place++),
                    "name", entry.name(),
                    "found", String.valueOf(entry.found()),
                    "collection", String.valueOf(entry.items()),
                    "total", String.valueOf(TreasureRegistry.size()));
        }
        return true;
    }

    /* ----------------------------------- лут ---------------------------------- */

    /** Сколько строк пула показывать за раз: 1009 записей не должны флудить чат. */
    private static final int LOOT_LINES = 40;

    /**
     * Показывает лут-таблицы: сводку, общий пул редкости или региональный пул.
     * Шансы считаются по действующим весам и прогрессу игрока, а не «на глазок».
     */
    private boolean loot(Player player, String filter) {
        long mined = service.mined(player);
        if (filter == null || filter.isBlank() || filter.equalsIgnoreCase("all")) {
            plugin.messages().send(player, "loot-header");
            plugin.messages().send(player, "loot-summary",
                    "entries", String.valueOf(TreasureLoot.active().size()),
                    "regions", String.valueOf(TreasureRegion.values().length),
                    "tiers", String.valueOf(TreasureTier.values().length));
            for (TreasureTier tier : TreasureTier.values()) {
                plugin.messages().send(player, "loot-tier-count",
                        "tier", Messages.trusted(plugin.messages().raw("tier." + tier.id())),
                        "count", String.valueOf(TreasureLoot.poolOf(tier).size()));
            }
            plugin.messages().send(player, "loot-hint");
            return true;
        }
        TreasureTier tier = TreasureTier.byId(filter);
        if (tier != null) {
            plugin.messages().send(player, "loot-tier-header",
                    "tier", Messages.trusted(plugin.messages().raw("tier." + tier.id())));
            var entries = TreasureLoot.poolOf(tier);
            if (entries.isEmpty()) {
                plugin.messages().send(player, "loot-empty");
                return true;
            }
            int shown = 0;
            for (TreasureLoot.LootEntry entry : entries) {
                if (shown >= LOOT_LINES) {
                    break;
                }
                shown++;
                double chance = TreasureLoot.chance(tier, null, entry, mined);
                if (entry.unlock() > 0) {
                    plugin.messages().send(player, "loot-line-locked",
                            "material", label(entry),
                            "min", String.valueOf(entry.min()),
                            "max", String.valueOf(entry.max()),
                            "chance", formatPercent(chance),
                            "blocks", String.valueOf(entry.unlock()));
                } else {
                    plugin.messages().send(player, "loot-line",
                            "material", label(entry),
                            "min", String.valueOf(entry.min()),
                            "max", String.valueOf(entry.max()),
                            "chance", formatPercent(chance));
                }
            }
            if (entries.size() > shown) {
                plugin.messages().send(player, "loot-truncated",
                        "count", String.valueOf(entries.size() - shown));
            }
            plugin.messages().send(player, "loot-hint");
            return true;
        }
        TreasureRegion region = TreasureRegion.byId(filter);
        if (region != null) {
            plugin.messages().send(player, "loot-region-header",
                    "region", Messages.trusted(plugin.messages().raw("region." + region.id())));
            boolean any = false;
            int shown = 0;
            int total = TreasureLoot.active().regional(region).size();
            for (TreasureTier itemTier : TreasureTier.values()) {
                var entries = TreasureLoot.active().regional(region, itemTier);
                if (entries.isEmpty()) {
                    continue;
                }
                any = true;
                if (shown >= LOOT_LINES) {
                    continue;
                }
                plugin.messages().send(player, "loot-region-tier",
                        "tier", Messages.trusted(plugin.messages().raw("tier." + itemTier.id())),
                        "count", String.valueOf(entries.size()));
                for (TreasureLoot.LootEntry entry : entries) {
                    if (shown >= LOOT_LINES) {
                        break;
                    }
                    shown++;
                    if (entry.unlock() > 0) {
                        plugin.messages().send(player, "loot-region-line-locked",
                                "material", label(entry),
                                "min", String.valueOf(entry.min()),
                                "max", String.valueOf(entry.max()),
                                "blocks", String.valueOf(entry.unlock()));
                    } else {
                        plugin.messages().send(player, "loot-region-line",
                                "material", label(entry),
                                "min", String.valueOf(entry.min()),
                                "max", String.valueOf(entry.max()));
                    }
                }
            }
            if (!any) {
                plugin.messages().send(player, "loot-empty");
            } else if (total > shown) {
                plugin.messages().send(player, "loot-truncated",
                        "count", String.valueOf(total - shown));
            }
            plugin.messages().send(player, "loot-hint");
            return true;
        }
        plugin.messages().send(player, "loot-empty");
        return true;
    }

    /** Подпись записи: особое имя, если оно есть, иначе читаемый материал. */
    private static String label(TreasureLoot.LootEntry entry) {
        return entry.name() == null || entry.name().isBlank()
                ? pretty(entry.material()) : entry.name();
    }

    private static String pretty(String material) {
        return material.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String formatPercent(double fraction) {
        double percent = Math.max(0.0, Math.min(1.0, fraction)) * 100.0;
        if (percent >= 1.0) {
            return String.valueOf(Math.round(percent * 10.0) / 10.0);
        }
        return String.valueOf(Math.round(percent * 100.0) / 100.0);
    }

    /* --------------------------------- статистика ------------------------------ */

    /** Статистика себя или другого игрока (право {@code chrdktreasures.stats.others}). */
    private boolean playerStats(Player sender, String name) {
        Player target = sender;
        if (name != null && !name.isBlank()) {
            if (!sender.hasPermission("chrdktreasures.stats.others")) {
                plugin.messages().send(sender, "no-permission");
                return true;
            }
            target = Bukkit.getPlayerExact(name);
            if (target == null) {
                plugin.messages().send(sender, "admin-player-not-found");
                return true;
            }
        }
        return stats(sender, target);
    }

    private boolean stats(CommandSender sender, Player target) {
        plugin.messages().send(sender, "stats-header", "player", target.getName());
        plugin.messages().send(sender, "stats-mined",
                "mined", String.valueOf(service.mined(target)));
        for (TreasureTier tier : TreasureTier.values()) {
            plugin.messages().send(sender, "stats-tier",
                    "tier", Messages.trusted(plugin.messages().raw("tier." + tier.id())),
                    "count", String.valueOf(service.tierCount(target, tier)),
                    "chance", formatChance(plugin.config().chance(tier)));
        }
        plugin.messages().send(sender, "stats-collection",
                "found", String.valueOf(service.foundItems(target)),
                "total", String.valueOf(TreasureRegistry.size()));
        plugin.messages().send(sender, "stats-unlocked",
                "unlocked", String.valueOf(service.unlockedTiers(target)),
                "all", String.valueOf(TreasureTier.values().length));
        TreasureRank rank = service.rank(target);
        plugin.messages().send(sender, "stats-rank",
                "rank", Messages.trusted(plugin.messages().raw("rank." + rank.id())),
                "found", String.valueOf(service.found(target)));
        TreasureRank nextRank = rank.next();
        if (nextRank == null) {
            plugin.messages().send(sender, "stats-rank-max");
        } else {
            plugin.messages().send(sender, "stats-rank-progress",
                    "next", Messages.trusted(plugin.messages().raw("rank." + nextRank.id())),
                    "remaining", String.valueOf(rank.remaining(service.found(target))));
        }
        plugin.messages().send(sender, "stats-streak",
                "streak", String.valueOf(service.streak(target)),
                "best", String.valueOf(service.bestStreak(target)));
        TreasureSet activeSet = plugin.config().setsEnabled() ? plugin.sets().active(target) : null;
        if (activeSet == null) {
            plugin.messages().send(sender, "stats-set-none");
        } else {
            plugin.messages().send(sender, "stats-set",
                    "set", Messages.trusted(plugin.messages().raw(
                            "region." + activeSet.region().id())),
                    "pieces", String.valueOf(plugin.sets().pieces(target, activeSet.region())),
                    "total", String.valueOf(TreasureSet.FULL_PIECES));
        }
        String next = service.nextTierHint(target);
        plugin.messages().send(sender, next.isBlank() ? "stats-next-none" : "stats-next",
                "hint", Messages.trusted(next));
        plugin.messages().send(sender, "stats-footer");
        return true;
    }

    private static String formatChance(double percent) {
        if (percent >= 1.0) {
            return String.valueOf(Math.round(percent * 100.0) / 100.0);
        }
        return String.valueOf(Math.round(percent * 10_000.0) / 10_000.0);
    }

    /* ---------------------------------- настройки ------------------------------ */

    private boolean toggle(Player player, String feature) {
        if (feature == null || feature.isBlank()) {
            plugin.messages().send(player, "toggle-usage");
            return true;
        }
        String key = player.getUniqueId().toString();
        boolean value;
        String name = "";
        switch (feature.toLowerCase(Locale.ROOT)) {
            case "sounds", "sound", "звук", "звуки" -> {
                value = !service.store().sounds(key);
                service.store().setSounds(key, value);
                name = "feature-sounds";
            }
            case "particles", "particle", "частицы" -> {
                value = !service.store().particles(key);
                service.store().setParticles(key, value);
                name = "feature-particles";
            }
            case "announce", "messages", "сообщения" -> {
                value = !service.store().announce(key);
                service.store().setAnnounce(key, value);
                name = "feature-announce";
            }
            default -> {
                plugin.messages().send(player, "toggle-usage");
                return true;
            }
        }
        plugin.messages().send(player, value ? "toggle-on" : "toggle-off",
                "feature", Messages.trusted(plugin.messages().raw(name)));
        return true;
    }

    /* ---------------------------------- админ --------------------------------- */

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdktreasures.admin")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("awards")) {
            plugin.finance().status(sender); return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadTreasuresConfig();
            plugin.messages().send(sender, "admin-reloaded");
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                plugin.messages().send(sender, "admin-player-not-found");
                return true;
            }
            service.reset(target);
            plugin.messages().send(sender, "admin-reset", "player", target.getName());
            plugin.getLogger().info("[CHRDK_Treasures] " + sender.getName()
                    + " сбросил прогресс кладоискателя " + target.getName());
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                plugin.messages().send(sender, "admin-player-not-found");
                return true;
            }
            TreasureItemDef def = TreasureRegistry.find(args[2]);
            if (def == null) {
                plugin.messages().send(sender, "admin-unknown-item", "id", args[2]);
                return true;
            }
            if (!service.giveItem(target, def)) {
                plugin.messages().send(sender, "admin-give-failed");
                return true;
            }
            plugin.messages().send(sender, "admin-gave",
                    "player", target.getName(),
                    "name", Messages.trusted(plugin.messages().raw("treasure." + def.id() + ".name")));
            plugin.getLogger().info("[CHRDK_Treasures] " + sender.getName()
                    + " выдал " + def.id() + " игроку " + target.getName());
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("shards")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                plugin.messages().send(sender, "admin-player-not-found");
                return true;
            }
            int amount;
            try {
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException exception) {
                plugin.messages().send(sender, "admin-shards-invalid");
                return true;
            }
            if (amount <= 0) {
                plugin.messages().send(sender, "admin-shards-invalid");
                return true;
            }
            long balance = plugin.store().addShards(target.getUniqueId().toString(), amount);
            plugin.messages().send(sender, "admin-shards-given",
                    "player", target.getName(),
                    "amount", String.valueOf(amount),
                    "balance", String.valueOf(balance));
            plugin.getLogger().info("[CHRDK_Treasures] " + sender.getName()
                    + " выдал " + amount + " осколков игроку " + target.getName());
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("map")) {
            if (!plugin.config().maps().enabled()) {
                plugin.messages().send(sender, "map-disabled");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                plugin.messages().send(sender, "admin-player-not-found");
                return true;
            }
            TreasureTier tier = TreasureTier.COMMON;
            if (args.length >= 3) {
                TreasureTier parsed = TreasureTier.byId(args[2]);
                if (parsed == null) {
                    plugin.messages().send(sender, "admin-unknown-tier", "tier", args[2]);
                    return true;
                }
                tier = parsed;
            }
            if (!plugin.maps().giveTier(target, tier)) {
                plugin.messages().send(sender, "admin-give-failed");
                return true;
            }
            plugin.messages().send(sender, "admin-map-given",
                    "player", target.getName(),
                    "tier", Messages.trusted(plugin.messages().raw("tier." + tier.id())));
            plugin.getLogger().info("[CHRDK_Treasures] " + sender.getName()
                    + " выдал клад-карту (" + tier.id() + ") игроку " + target.getName());
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("stats")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                plugin.messages().send(sender, "admin-player-not-found");
                return true;
            }
            return stats(sender, target);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("chance")) {
            TreasureTier tier = TreasureTier.byId(args[1]);
            if (tier == null) {
                plugin.messages().send(sender, "admin-chance-unknown-tier", "tier", args[1]);
                return true;
            }
            if (args[2].equalsIgnoreCase("reset")) {
                plugin.overrides().clear(tier);
                plugin.reloadTreasuresConfig();
                plugin.messages().send(sender, "admin-chance-reset",
                        "tier", Messages.trusted(plugin.messages().raw("tier." + tier.id())));
                plugin.getLogger().info("[CHRDK_Treasures] " + sender.getName()
                        + " вернул шанс " + tier.id() + " к config.yml");
                return true;
            }
            double percent;
            try {
                percent = Double.parseDouble(args[2].replace(',', '.'));
            } catch (NumberFormatException exception) {
                plugin.messages().send(sender, "admin-chance-invalid");
                return true;
            }
            if (percent < 0.0 || percent > 100.0) {
                plugin.messages().send(sender, "admin-chance-invalid");
                return true;
            }
            double applied = plugin.overrides().set(tier, percent);
            plugin.reloadTreasuresConfig();
            plugin.messages().send(sender, "admin-chance-set",
                    "tier", Messages.trusted(plugin.messages().raw("tier." + tier.id())),
                    "percent", formatChance(applied));
            plugin.getLogger().info("[CHRDK_Treasures] " + sender.getName()
                    + " установил шанс " + tier.id() + ": " + applied + " %");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("resetall")) {
            if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
                plugin.messages().send(sender, "admin-resetall-confirm");
                return true;
            }
            int removed = service.resetAll();
            plugin.messages().send(sender, "admin-resetall",
                    "players", String.valueOf(removed));
            plugin.getLogger().warning("[CHRDK_Treasures] " + sender.getName()
                    + " сбросил прогресс всех кладоискателей (" + removed + " профилей)");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("barrels")) {
            return barrels(sender, args);
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("barrel")) {
            return barrel(sender, args);
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("info")) {
            int abilities = 0;
            int curses = 0;
            for (TreasureItemDef def : TreasureRegistry.all()) {
                if (def.ability() != null) {
                    abilities++;
                }
                if (def.curse() != null) {
                    curses++;
                }
            }
            plugin.messages().send(sender, "admin-info",
                    "items", String.valueOf(TreasureRegistry.size()),
                    "regions", String.valueOf(TreasureRegion.values().length),
                    "tiers", String.valueOf(TreasureTier.values().length),
                    "blocks", String.valueOf(plugin.config().blockCount()),
                    "loot", String.valueOf(TreasureLoot.active().size()),
                    "abilities", String.valueOf(abilities),
                    "sets", String.valueOf(TreasureSets.all().size()),
                    "curses", String.valueOf(curses),
                    "maps", plugin.maps().summary(),
                    // 1.4.181: видно, падает добыча на землю или в бочку.
                    "drops", plugin.drops() == null ? "—" : plugin.drops().summary());
            return true;
        }
        plugin.messages().send(sender, "admin-usage");
        return true;
    }

    /* ----------------------------- бочки с кладом ------------------------------ */

    /**
     * {@code /treasuresadmin barrels [list|clear]} — диагностика живых бочек.
     *
     * <p>Команда появилась после жалобы «бочки пустые»: администратору нужно
     * видеть, что бочка вообще учтена, кому принадлежит и сколько ей осталось.
     * Без этого остаётся гадать по логам и координатам.</p>
     */
    private boolean barrels(CommandSender sender, String[] args) {
        TreasureBarrels barrels = plugin.barrels();
        if (barrels == null) {
            plugin.messages().send(sender, "barrels-disabled");
            return true;
        }
        TreasuresConfig.Barrels settings = plugin.config().barrels();
        if (args.length >= 2 && args[1].equalsIgnoreCase("clear")) {
            // Уборка всех бочек — разрушительная операция: требуем confirm,
            // как у resetall. Иначе один промах в табе сметёт живые клады.
            if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
                plugin.messages().send(sender, "admin-barrels-clear-confirm");
                return true;
            }
            int cleared = barrels.clearAll(true);
            plugin.messages().send(sender, "admin-barrels-cleared",
                    "count", String.valueOf(cleared));
            plugin.getLogger().warning("[CHRDK_Treasures] " + sender.getName()
                    + " убрал бочки с кладом: " + cleared);
            return true;
        }
        plugin.messages().send(sender, "admin-barrels-summary",
                "live", String.valueOf(barrels.liveCount()),
                "expired", String.valueOf(barrels.expiredCount()),
                "limit", String.valueOf(settings.maxPerPlayer()),
                "seconds", settings.expireSeconds() <= 0
                        ? "∞" : String.valueOf(settings.expireSeconds()));
        if (args.length < 2 || !args[1].equalsIgnoreCase("list")) {
            return true;
        }
        List<TreasureBarrels.Live> rows = barrels.snapshot();
        if (rows.isEmpty()) {
            plugin.messages().send(sender, "admin-barrels-empty");
            return true;
        }
        long now = System.currentTimeMillis();
        int shown = 0;
        for (TreasureBarrels.Live row : rows) {
            if (shown >= BARREL_LIST_LIMIT) {
                break;
            }
            String left;
            if (settings.expireMillis() <= 0L) {
                left = "∞";
            } else {
                long millis = Math.max(0L, settings.expireMillis() - (now - row.createdAt()));
                left = (millis + 999L) / 1000L + " с";
            }
            plugin.messages().send(sender, "admin-barrels-line",
                    "world", row.world(),
                    "x", String.valueOf(row.x()), "y", String.valueOf(row.y()),
                    "z", String.valueOf(row.z()),
                    "owner", row.ownerName(),
                    "tier", Messages.trusted(plugin.messages().raw("tier." + row.tier().id())),
                    "left", left);
            shown++;
        }
        if (rows.size() > shown) {
            plugin.messages().send(sender, "admin-barrels-more",
                    "count", String.valueOf(rows.size() - shown));
        }
        return true;
    }

    /**
     * {@code /treasuresadmin barrel test [редкость]} — поставить бочку с
     * настоящей добычей перед собой и проверить, что внутри не пусто.
     */
    private boolean barrel(CommandSender sender, String[] args) {
        TreasureBarrels barrels = plugin.barrels();
        if (barrels == null) {
            plugin.messages().send(sender, "barrels-disabled");
            return true;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("test")) {
            plugin.messages().send(sender, "admin-usage");
            return true;
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        TreasureTier tier = args.length > 2 ? TreasureTier.byId(args[2]) : TreasureTier.COMMON;
        if (tier == null) {
            plugin.messages().send(sender, "admin-unknown-tier", "tier", args[2]);
            return true;
        }
        List<ItemStack> payload = service.samplePayload(tier);
        Location target = player.getLocation().clone();
        target.setY(player.getLocation().getBlockY());
        target.add(player.getLocation().getDirection().setY(0).normalize().multiply(2.0));
        if (barrels.spawn(player, target, tier, payload)) {
            plugin.messages().send(sender, "admin-barrel-test",
                    "tier", Messages.trusted(plugin.messages().raw("tier." + tier.id())));
        } else {
            plugin.messages().send(sender, "admin-barrel-test-failed");
            service.givePayload(player, payload);
        }
        return true;
    }

    /* ---------------------------------- tab ----------------------------------- */

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias,
                                      String[] args) {
        if (command.getName().equalsIgnoreCase("treasuresadmin")) {
            if (!sender.hasPermission("chrdktreasures.admin")) {
                return List.of();
            }
            if (args.length == 1) {
                return filter(List.of("awards", "reload", "give", "reset", "resetall", "stats",
                        "chance", "map", "shards", "info", "barrels", "barrel"), args[0]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("chance")) {
                List<String> tiers = new ArrayList<>();
                for (TreasureTier tier : TreasureTier.values()) {
                    tiers.add(tier.id());
                }
                return filter(tiers, args[1]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("resetall")) {
                return filter(List.of("confirm"), args[1]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("barrels")) {
                return filter(List.of("list", "clear"), args[1]);
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("barrels")
                    && args[1].equalsIgnoreCase("clear")) {
                return filter(List.of("confirm"), args[2]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("barrel")) {
                return filter(List.of("test"), args[1]);
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("barrel")) {
                List<String> tiers = new ArrayList<>();
                for (TreasureTier tier : TreasureTier.values()) {
                    tiers.add(tier.id());
                }
                return filter(tiers, args[2]);
            }
            if (args.length == 2) {
                return filter(onlineNames(), args[1]);
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("chance")) {
                return filter(List.of("reset", "0.01", "0.05", "0.1", "1"), args[2]);
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("map")) {
                List<String> tiers = new ArrayList<>();
                for (TreasureTier tier : TreasureTier.values()) {
                    tiers.add(tier.id());
                }
                return filter(tiers, args[2]);
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
                List<String> ids = new ArrayList<>();
                for (TreasureItemDef def : TreasureRegistry.all()) {
                    ids.add(def.id());
                }
                return filter(ids, args[2]);
            }
            return List.of();
        }
        if (!sender.hasPermission("chrdktreasures.use")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(List.of("menu", "list", "stats", "top", "loot", "set", "map",
                    "toggle", "shards", "badge", "help"), args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("map") || args[0].equalsIgnoreCase("maps"))) {
            return filter(List.of("list"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("badge")) {
            return filter(List.of("auto", "on", "off"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("toggle")) {
            return filter(List.of("sounds", "particles", "announce"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("stats")) {
            return filter(onlineNames(), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("loot")) {
            List<String> options = new ArrayList<>();
            for (TreasureTier tier : TreasureTier.values()) {
                options.add(tier.id());
            }
            for (TreasureRegion region : TreasureRegion.values()) {
                options.add(region.id());
            }
            options.add("all");
            return filter(options, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            List<String> options = new ArrayList<>();
            for (TreasureRegion region : TreasureRegion.values()) {
                options.add(region.id());
            }
            options.add("all");
            return filter(options, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("list")) {
            List<String> options = new ArrayList<>();
            for (TreasureTier tier : TreasureTier.values()) {
                options.add(tier.id());
            }
            for (TreasureRegion region : TreasureRegion.values()) {
                options.add(region.id());
            }
            options.add("all");
            return filter(options, args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String needle = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(needle)) {
                out.add(option);
            }
        }
        return out;
    }

    private static List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }
}
