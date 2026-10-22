package ru.cherdakmd.chrdktierforge;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import ru.cherdakmd.chrdkregister.Messages;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** /tiers — таблица редкости + личная статистика; /tiersadmin give|give-unidentified|reload. */
public final class TierCommandHandler implements TabExecutor {

    private final TierForgePlugin plugin;

    public TierCommandHandler(TierForgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (plugin.coreApi() == null) {
            plugin.messages().send(sender, "no-api");
            return true;
        }
        if ("tiersadmin".equalsIgnoreCase(command.getName())) {
            return handleAdmin(sender, args);
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (!plugin.coreApi().isAuthenticated(player)) {
            plugin.messages().send(player, "need-auth");
            return true;
        }
        // 1.4.85: chrdktierforge.use объявлен в plugin.yml, но не проверялся —
        // права, выданные менеджером разрешений, игнорировались.
        if (!player.hasPermission("chrdktierforge.use")) {
            plugin.messages().send(player, "no-perm");
            return true;
        }
        if (args.length >= 1 && "stats".equalsIgnoreCase(args[0])) {
            return handleStats(player, args.length >= 2 && "reset".equalsIgnoreCase(args[1]));
        }
        if (args.length >= 1 && "info".equalsIgnoreCase(args[0])) {
            return handleInfo(player);
        }
        if (args.length >= 1 && "top".equalsIgnoreCase(args[0])) {
            return handleTop(player, args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : null);
        }
        if (args.length >= 1 && "effects".equalsIgnoreCase(args[0])) {
            return handleEffects(player, args.length >= 2 ? args[1] : null);
        }
        // 1.4.150: витрина «Коллекция кузни» — редкости, свойства и аффиксы.
        if (args.length >= 1 && "collection".equalsIgnoreCase(args[0])) {
            if (args.length >= 2 && "top".equalsIgnoreCase(args[1])) {
                return handleCollectionTop(player, args.length >= 3 ? args[2] : null);
            }
            if (plugin.collectionMenu() == null) {
                plugin.messages().send(player, "collection-disabled");
                return true;
            }
            plugin.collectionMenu().open(player);
            return true;
        }
        return handleTiers(player);
    }

    private boolean handleTiers(Player player) {
        plugin.messages().send(player, "list-header");
        // 1.4.80: показываем ДЕЙСТВУЮЩИЙ шанс каждой группы, а не только общий —
        // craft.weapons/armor/tools-chance-percent раньше никак не отражались в UI.
        // 1.4.142: четвёртая группа — утилита (щит, рог, фонарь, тотем).
        plugin.messages().send(player, "list-chances",
                "weapons", plugin.config().craftChanceFor(ItemKind.SWORD),
                "armor", plugin.config().craftChanceFor(ItemKind.CHESTPLATE),
                "tools", plugin.config().craftChanceFor(ItemKind.PICKAXE),
                "utilities", plugin.config().craftChanceFor(ItemKind.UTILITY));
        // 1.4.117: инструменты больше не получают урон — игрок должен видеть, что даёт тир.
        plugin.messages().send(player, "list-bonuses");
        if (plugin.config().mobDropEnabled()) {
            plugin.messages().send(player, "list-drop-chances",
                    "weapons", plugin.config().mobDropChanceFor(ItemKind.SWORD),
                    "armor", plugin.config().mobDropChanceFor(ItemKind.CHESTPLATE),
                    "tools", plugin.config().mobDropChanceFor(ItemKind.PICKAXE),
                    "utilities", plugin.config().mobDropChanceFor(ItemKind.UTILITY));
        }
        for (TierDef.Snapshot tier : plugin.config().tiers()) {
            // 1.4.90: строка тира тоже живёт в messages.yml — раньше русский текст
            // и MiniMessage собирались прямо в Java в обход локализации.
            String color = plugin.messages().raw("tier." + tier.id() + ".color");
            String name = plugin.messages().raw("tier." + tier.id() + ".name");
            String percent = (tier.percent() > 0 ? "+" : "") + tier.percent();
            plugin.messages().send(player, "list-row",
                    "color", color, "name", name,
                    "weight", tier.weight(), "percent", percent);
        }
        plugin.messages().send(player, "list-footer",
                "own_count", plugin.stats().total(player.getUniqueId()),
                "total_count", plugin.stats().grandTotal());
        return true;
    }

    /**
     * /tiers effects [on|off] — личный выключатель ауры редкости (1.4.119).
     * Без аргумента переключает; флаг хранится в PDC игрока и переживает
     * перезапуск. Серверный выключатель — {@code visuals.enabled}.
     */
    private boolean handleEffects(Player player, String mode) {
        if (!plugin.config().visuals().enabled()) {
            plugin.messages().send(player, "effects-disabled");
            return true;
        }
        boolean hidden;
        if (mode == null || mode.isBlank()) {
            hidden = plugin.visuals().toggle(player);
        } else if ("on".equalsIgnoreCase(mode)) {
            hidden = plugin.visuals().setHidden(player, false);
        } else if ("off".equalsIgnoreCase(mode)) {
            hidden = plugin.visuals().setHidden(player, true);
        } else {
            plugin.messages().send(player, "effects-usage");
            return true;
        }
        plugin.messages().send(player, hidden ? "effects-off" : "effects-on");
        return true;
    }

    /**
     * /tiers info — разбор предмета в руке (1.4.84): тир, кличка, источник,
     * все кузнечные улучшения с описанием, цена слияния и переката.
     */
    private boolean handleInfo(Player player) {
        // 1.4.90: если в основной руке пусто/обычный предмет, смотрим во вторую —
        // раньше /tiers info молчал, хотя тир-предмет был в левой руке.
        ItemStack item = player.getInventory().getItemInMainHand();
        boolean offhand = false;
        if (!isTierItem(item)) {
            ItemStack second = player.getInventory().getItemInOffHand();
            if (isTierItem(second)) {
                item = second;
                offhand = true;
            }
        }
        if (item == null || item.getType() == Material.AIR) {
            plugin.messages().send(player, "info-empty");
            return true;
        }
        if (!isTierItem(item)) {
            plugin.messages().send(player, "info-not-tier");
            return true;
        }
        boolean unidentified = plugin.items().isUnidentified(item);
        String tierId = plugin.items().tierOf(item);
        String itemTag = TierInfoFormat.languageTag(item.getType());
        if (offhand) {
            plugin.messages().send(player, "info-offhand");
        }
        plugin.messages().send(player, "info-header");
        if (unidentified) {
            plugin.messages().send(player, "info-unidentified",
                    "item", Messages.trusted(itemTag),
                    "source", sourceLabel(plugin.items().unidentifiedSource(item)));
            return true;
        }

        String color = plugin.messages().raw("tier." + tierId + ".color");
        String name = plugin.messages().raw("tier." + tierId + ".name");
        plugin.messages().send(player, "info-line",
                "item", Messages.trusted(itemTag),
                "tier", Messages.trusted(color + name));
        String source = plugin.items().unidentifiedSource(item);
        if (!source.isBlank()) {
            plugin.messages().send(player, "info-source", "source", sourceLabel(source));
        }
        String forgeName = plugin.items().forgeNameOf(item);
        if (!forgeName.isBlank()) {
            plugin.messages().send(player, "info-forge-name", "name", forgeName);
        }

        Map<ForgeUpgrade.Def, Integer> upgrades = plugin.items().upgradesOf(item);
        if (upgrades.isEmpty()) {
            plugin.messages().send(player, "info-no-upgrades");
        } else {
            plugin.messages().send(player, "info-upgrades-header", "count", upgrades.size());
            for (Map.Entry<ForgeUpgrade.Def, Integer> entry : upgrades.entrySet()) {
                plugin.messages().send(player, "info-upgrade",
                        "name", entry.getKey().displayName(),
                        "level", ForgeUpgrade.roman(entry.getValue()),
                        "effect", ForgeUpgrade.describe(entry.getKey(), entry.getValue()));
            }
        }

        // 1.4.118: уникальные свойства тира — отдельным блоком после улучшений
        java.util.List<TierProperty> properties = plugin.items().propertiesOf(item);
        if (!properties.isEmpty()) {
            plugin.messages().send(player, "info-properties-header", "count", properties.size());
            for (TierProperty property : properties) {
                plugin.messages().send(player, "info-property",
                        "name", property.name(),
                        "effect", property.describe());
            }
        }

        // Волна 17: универсальные аффиксы — бонусы ◆ и дефекты ✖ отдельными блоками
        java.util.List<TierAffix> affixes = plugin.items().affixesOf(item);
        java.util.List<TierAffix> good = affixes.stream().filter(TierAffix::good).toList();
        java.util.List<TierAffix> bad = affixes.stream().filter(affix -> !affix.good()).toList();
        if (!good.isEmpty()) {
            plugin.messages().send(player, "info-affixes-good-header", "count", good.size());
            for (TierAffix affix : good) {
                plugin.messages().send(player, "info-affix-good",
                        "name", affix.name(), "effect", affix.describe());
            }
        }
        if (!bad.isEmpty()) {
            plugin.messages().send(player, "info-affixes-bad-header", "count", bad.size());
            for (TierAffix affix : bad) {
                plugin.messages().send(player, "info-affix-bad",
                        "name", affix.name(), "effect", affix.describe());
            }
        }

        // Волна 17: зачарования, выпавшие вместе с тиром (или поставленные игроком)
        java.util.Map<org.bukkit.enchantments.Enchantment, Integer> enchants =
                plugin.items().enchantmentsOf(item);
        if (!enchants.isEmpty()) {
            plugin.messages().send(player, "info-enchants-header", "count", enchants.size());
            for (java.util.Map.Entry<org.bukkit.enchantments.Enchantment, Integer> entry
                    : enchants.entrySet()) {
                plugin.messages().send(player, "info-enchant",
                        "name", TierEnchants.displayName(entry.getKey().getKey().getKey()),
                        "level", ForgeUpgrade.roman(entry.getValue()));
            }
        }

        if (!plugin.config().anvilEnabled()) {
            plugin.messages().send(player, "info-anvil-off");
            return true;
        }
        String next = TierDef.next(tierId);
        if (next == null) {
            plugin.messages().send(player, "info-merge-max");
        } else {
            plugin.messages().send(player, "info-merge",
                    "next", Messages.trusted(plugin.messages().raw("tier." + next + ".color")
                            + plugin.messages().raw("tier." + next + ".name")),
                    "xp", TierMath.upgradeXpCost(plugin.config().anvilBaseXp(),
                            TierDef.indexOf(next)));
        }
        if (plugin.config().rerollEnabled()) {
            plugin.messages().send(player,
                    plugin.config().rerollNoDowngrade() ? "info-reroll-safe" : "info-reroll",
                    "xp", TierMath.rerollXpCost(plugin.config().rerollBaseXp(),
                            TierDef.indexOf(tierId)),
                    "catalyst", Messages.trusted(TierInfoFormat.languageTag(
                            Material.matchMaterial(plugin.config().rerollCatalyst()))));
        }
        return true;
    }

    /** Тир-предмет или неопознанная заготовка (для выбора руки в /tiers info). */
    private boolean isTierItem(ItemStack item) {
        return item != null && item.getType() != Material.AIR
                && (plugin.items().isUnidentified(item) || plugin.items().tierOf(item) != null);
    }

    /**
     * /tiers top [тир] — серверный рейтинг находок (1.4.97): топ-10 игроков по
     * всем тирам или по одному. Имена берём у сервера; если профиль недоступен
     * (офлайн-игрок без кэша), показываем короткий UUID.
     */
    private boolean handleTop(Player player, String tierId) {
        if (tierId != null && !TierDef.isTier(tierId)) {
            plugin.messages().send(player, "admin-bad-tier",
                    "tier", tierId,
                    "tiers", plugin.config().tiers().stream()
                            .map(TierDef.Snapshot::id).collect(Collectors.joining(", ")));
            return true;
        }
        String scope = tierId == null
                ? plugin.messages().plain("top-scope-all")
                : plugin.messages().raw("tier." + tierId + ".name");
        plugin.messages().send(player, "top-header", "scope", scope);
        List<TierStatsKeeper.TopEntry> entries = plugin.stats().top(tierId, 10);
        if (entries.isEmpty()) {
            plugin.messages().send(player, "top-empty");
            return true;
        }
        int place = 1;
        for (TierStatsKeeper.TopEntry entry : entries) {
            plugin.messages().send(player, "top-row",
                    "place", place++,
                    "player", leaderboardName(entry.player()),
                    "count", entry.count());
        }
        return true;
    }

    /**
     * `/tiers collection top [сколько]` — самые полные книги сервера (1.4.151).
     *
     * <p>Рейтинг строится по числу собранных позиций, при равенстве — по
     * редкостям и свойствам. Титул показывается рядом с ником, если он есть.</p>
     */
    private boolean handleCollectionTop(Player player, String rawLimit) {
        int limit = 10;
        if (rawLimit != null && !rawLimit.isBlank()) {
            try {
                limit = Math.max(1, Math.min(25, Integer.parseInt(rawLimit)));
            } catch (NumberFormatException ignored) {
                limit = 10;
            }
        }
        plugin.messages().send(player, "collection-top-header", "limit", limit);
        List<TierCollectionKeeper.Row> rows = plugin.collection().keeper().top(limit);
        if (rows.isEmpty()) {
            plugin.messages().send(player, "collection-top-empty");
            return true;
        }
        int place = 1;
        for (TierCollectionKeeper.Row row : rows) {
            String title = plugin.collection().titleFor(row.player());
            plugin.messages().send(player, "collection-top-row",
                    "place", place++,
                    "player", leaderboardName(row.player()),
                    "title", Messages.trusted(title == null ? "" : " " + title),
                    "found", row.total(),
                    "total", TierCollectionBook.CATALOG_TOTAL,
                    "percent", TierCollectionBook.percentOf(row.total()));
        }
        plugin.messages().send(player, "collection-top-footer",
                "players", plugin.collection().keeper().players(),
                "total", TierCollectionBook.CATALOG_TOTAL);
        return true;
    }

    /** Ник для рейтинга: серверный профиль, иначе первые 8 символов UUID. */
    private static String leaderboardName(UUID player) {
        String name = Bukkit.getOfflinePlayer(player).getName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        return player.toString().substring(0, 8);
    }

    /**
     * `/tiersadmin export` — записать `stats-export.csv` в папку аддона (1.4.99).
     * UTF-8 с BOM: так Excel открывает кириллицу в никах без «кракозябр».
     */
    private boolean exportStats(CommandSender sender) {
        File file = new File(plugin.getDataFolder(), StatsCsv.fileName());
        Map<UUID, Map<String, Integer>> snapshot = plugin.stats().snapshot();
        Map<UUID, String> names = new HashMap<>();
        for (UUID player : snapshot.keySet()) {
            String name = Bukkit.getOfflinePlayer(player).getName();
            if (name != null && !name.isBlank()) {
                names.put(player, name);
            }
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new java.io.IOException("папка плагина недоступна");
            }
            Files.writeString(file.toPath(), "\uFEFF" + StatsCsv.render(snapshot, names),
                    StandardCharsets.UTF_8);
        } catch (Exception exception) {
            plugin.messages().send(sender, "admin-export-failed",
                    "error", String.valueOf(exception.getMessage()));
            return true;
        }
        plugin.messages().send(sender, "admin-exported",
                "file", file.getName(), "rows", snapshot.size());
        return true;
    }

    /** UUID из аргумента (null — это ник или опечатка). */
    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** Читаемое название источника происхождения тира (ключи messages.yml). */
    private String sourceLabel(String source) {
        return plugin.messages().plain(TierInfoFormat.sourceKey(source));
    }

    /** /tiers stats [reset] — личные находки за всё время (1.4.81). */
    private boolean handleStats(Player player, boolean reset) {
        if (reset) {
            plugin.stats().reset(player.getUniqueId());
            plugin.messages().send(player, "stats-reset");
            return true;
        }
        return showStats(player, player.getUniqueId(), "stats-header", "stats-empty", null);
    }

    /** Печать таблицы находок; {@code playerName != null} — админский вариант с ником. */
    private boolean showStats(CommandSender sender, UUID playerId, String headerKey,
                              String emptyKey, String playerName) {
        String nameArg = playerName == null ? "" : playerName;
        plugin.messages().send(sender, headerKey, "player", nameArg);
        int shown = 0;
        for (TierDef.Snapshot tier : plugin.config().tiers()) {
            int count = plugin.stats().totalOf(playerId, tier.id());
            if (count <= 0) continue;
            shown++;
            String color = plugin.messages().raw("tier." + tier.id() + ".color");
            String name = plugin.messages().raw("tier." + tier.id() + ".name");
            plugin.messages().send(sender, "stats-row",
                    "color", color, "name", name, "count", count);
        }
        if (shown == 0) {
            plugin.messages().send(sender, emptyKey, "player", nameArg);
        }
        // 1.4.150: рядом с находками — прогресс коллекции кузни.
        TierCollectionService collection = plugin.collection();
        if (collection != null && collection.enabled()) {
            TierCollectionBook.Snapshot snapshot = collection.snapshot(playerId);
            String title = collection.titleFor(playerId);
            plugin.messages().send(sender, title == null
                            ? "stats-collection" : "stats-collection-title",
                    "found", snapshot.total(), "total", TierCollectionBook.CATALOG_TOTAL,
                    "percent", snapshot.percent(),
                    "title", title == null ? "" : Messages.trusted(title));
        }
        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdktierforge.admin")) {
            plugin.messages().send(sender, "admin-no-perm");
            return true;
        }
        if (args.length >= 1 && "reload".equalsIgnoreCase(args[0])) {
            plugin.reloadTierConfig();
            plugin.messages().send(sender, "admin-reloaded");
            return true;
        }
        // 1.4.99: выгрузка статистики в CSV для таблиц и Discord.
        if (args.length >= 1 && "export".equalsIgnoreCase(args[0])) {
            return exportStats(sender);
        }
        if (args.length >= 2 && ("stats".equalsIgnoreCase(args[0])
                || "resetstats".equalsIgnoreCase(args[0]))) {
            // 1.4.85: принимаем и UUID из stats.yml — иначе офлайн-статистику
            // (без онлайна игрока) посмотреть было нечем.
            Player online = Bukkit.getPlayerExact(args[1]);
            UUID targetId = online != null ? online.getUniqueId() : parseUuid(args[1]);
            if (targetId == null) {
                plugin.messages().send(sender, "admin-offline", "player", args[1]);
                return true;
            }
            String name = online != null ? online.getName() : args[1];
            if ("resetstats".equalsIgnoreCase(args[0])) {
                plugin.stats().reset(targetId);
                plugin.messages().send(sender, "admin-stats-reset", "player", name);
                return true;
            }
            return showStats(sender, targetId, "admin-stats-header",
                    "admin-stats-empty", name);
        }
        if (args.length >= 4 && "give".equalsIgnoreCase(args[0])) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                plugin.messages().send(sender, "admin-offline", "player", args[1]);
                return true;
            }
            Material material = Material.matchMaterial(args[2].toUpperCase(Locale.ROOT));
            ItemKind kind = material == null ? null : ItemKind.classify(material.name());
            if (material == null || kind == null) {
                plugin.messages().send(sender, "admin-bad-material", "material", args[2]);
                return true;
            }
            String tierId = args[3].toLowerCase(Locale.ROOT);
            if (!TierDef.isTier(tierId)) {
                plugin.messages().send(sender, "admin-bad-tier",
                        "tier", args[3],
                        "tiers", plugin.config().tiers().stream()
                                .map(TierDef.Snapshot::id).collect(Collectors.joining(", ")));
                return true;
            }
            int amount = 1;
            if (args.length >= 5) {
                try {
                    amount = Math.max(1, Math.min(64, Integer.parseInt(args[4])));
                } catch (NumberFormatException ignored) {
                    plugin.messages().send(sender, "admin-bad-amount", "amount", args[4]);
                    return true;
                }
            }
            TierDef.Snapshot tier = plugin.config().find(tierId);
            if (tier == null) {
                plugin.messages().send(sender, "admin-bad-tier", "tier", args[3],
                        "tiers", plugin.config().tiers().stream()
                                .map(TierDef.Snapshot::id).collect(Collectors.joining(", ")));
                return true;
            }
            // 1.4.96: необязательные уровни ковки и кличка — иначе админу
            // приходилось собирать предмет на наковальне вручную.
            UpgradeSpec.Result upgrades = UpgradeSpec.parse(args.length >= 6 ? args[5] : "-");
            if (upgrades.hasRejected()) {
                plugin.messages().send(sender, "admin-bad-upgrades",
                        "list", String.join(", ", upgrades.rejected()));
                return true;
            }
            String forgeName = args.length >= 7 ? args[6].trim() : "";
            giveItems(target, material, amount, item -> {
                plugin.items().apply(item, tier);
                for (Map.Entry<ForgeUpgrade.Def, Integer> entry
                        : upgrades.upgrades().entrySet()) {
                    plugin.items().bumpUpgrade(item, entry.getKey(), entry.getValue());
                }
                if (!forgeName.isEmpty()) {
                    plugin.items().setForgeName(item, forgeName);
                }
            });
            plugin.messages().send(sender, "admin-given",
                    "material", material.name(), "tier", tier.id(),
                    "player", target.getName(), "amount", amount);
            if (!upgrades.isEmpty()) {
                plugin.messages().send(sender, "admin-given-forged",
                        "player", target.getName(),
                        "list", UpgradeSpec.describe(upgrades.upgrades()));
            }
            return true;
        }
        if (args.length >= 3 && "give-unidentified".equalsIgnoreCase(args[0])) {
            if (!plugin.config().identifyUnidentified()) {
                plugin.messages().send(sender, "admin-unidentified-off");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                plugin.messages().send(sender, "admin-offline", "player", args[1]);
                return true;
            }
            Material material = Material.matchMaterial(args[2].toUpperCase(Locale.ROOT));
            ItemKind kind = material == null ? null : ItemKind.classify(material.name());
            if (material == null || kind == null) {
                plugin.messages().send(sender, "admin-bad-material", "material", args[2]);
                return true;
            }
            int amount = 1;
            if (args.length >= 4) {
                try {
                    amount = Math.max(1, Math.min(64, Integer.parseInt(args[3])));
                } catch (NumberFormatException ignored) {
                    plugin.messages().send(sender, "admin-bad-amount", "amount", args[3]);
                    return true;
                }
            }
            giveItems(target, material, amount, item -> plugin.items().markUnidentified(item, "give",
                    plugin.messages().get("unidentified-name"),
                    List.of(plugin.messages().get("unidentified-lore"))));
            plugin.messages().send(sender, "admin-given-unidentified",
                    "material", material.name(), "player", target.getName(), "amount", amount);
            return true;
        }
        plugin.messages().sendList(sender, "admin-usage");
        return true;
    }

    /** Выдаёт {@code amount} предметов стеками, модифицируя каждый стек {@code modifier}. */
    private void giveItems(Player target, Material material, int amount,
                           java.util.function.Consumer<ItemStack> modifier) {
        int left = amount;
        while (left > 0) {
            int stack = Math.min(material.getMaxStackSize(), left);
            ItemStack item = new ItemStack(material, stack);
            modifier.accept(item);
            left -= stack;
            target.getInventory().addItem(item).values().forEach(leftover ->
                    target.getWorld().dropItemNaturally(target.getLocation(), leftover));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                     @NotNull String label, String @NotNull [] args) {
        List<String> matches = new ArrayList<>();
        if (args.length == 0) {
            return matches;
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        if ("tiers".equalsIgnoreCase(command.getName())) {
            if (args.length == 1) {
                List.of("info", "stats", "top", "effects", "collection").stream()
                        .filter(x -> x.startsWith(prefix)).forEach(matches::add);
            } else if (args.length == 2 && "collection".equalsIgnoreCase(args[0])) {
                List.of("top").stream().filter(x -> x.startsWith(prefix)).forEach(matches::add);
            } else if (args.length == 3 && "collection".equalsIgnoreCase(args[0])
                    && "top".equalsIgnoreCase(args[1])) {
                List.of("5", "10", "25").stream().filter(x -> x.startsWith(prefix))
                        .forEach(matches::add);
            } else if (args.length == 2 && "effects".equalsIgnoreCase(args[0])) {
                List.of("on", "off").stream()
                        .filter(x -> x.startsWith(prefix)).forEach(matches::add);
            } else if (args.length == 2 && "stats".equalsIgnoreCase(args[0])) {
                List.of("reset").stream().filter(x -> x.startsWith(prefix)).forEach(matches::add);
            } else if (args.length == 2 && "top".equalsIgnoreCase(args[0])) {
                plugin.config().tiers().stream().map(TierDef.Snapshot::id)
                        .filter(id -> id.startsWith(prefix)).forEach(matches::add);
            }
            return matches;
        }
        if ("tiersadmin".equalsIgnoreCase(command.getName())) {
            boolean unidentified = "give-unidentified".equalsIgnoreCase(args[0]);
            if (args.length == 1) {
                List.of("give", "give-unidentified", "reload", "stats", "resetstats", "export").stream()
                        .filter(x -> x.startsWith(prefix)).forEach(matches::add);
            } else if (args.length == 2 && ("give".equalsIgnoreCase(args[0]) || unidentified
                    || "stats".equalsIgnoreCase(args[0])
                    || "resetstats".equalsIgnoreCase(args[0]))) {
                Bukkit.getOnlinePlayers().stream().map(Player::getName)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .forEach(matches::add);
            } else if (args.length == 3 && ("give".equalsIgnoreCase(args[0]) || unidentified)) {
                // подсказываем несколько популярных материалов
                List.of("diamond_sword", "netherite_sword", "diamond_helmet", "diamond_chestplate",
                        "diamond_leggings", "diamond_boots", "netherite_pickaxe", "diamond_pickaxe",
                        "diamond_axe", "bow", "crossbow")
                        .stream().filter(x -> x.startsWith(prefix)).forEach(matches::add);
            } else if (args.length == 4 && "give".equalsIgnoreCase(args[0])) {
                plugin.config().tiers().stream().map(TierDef.Snapshot::id)
                        .filter(id -> id.startsWith(prefix)).forEach(matches::add);
            } else if (args.length == 6 && "give".equalsIgnoreCase(args[0])) {
                // 1.4.96: подсказка формата уровней ковки
                List.of("edge:3", "crit:2", "bulwark:2", "prospector:2", "-")
                        .stream().filter(x -> x.startsWith(prefix)).forEach(matches::add);
            } else if ((args.length == 5 && "give".equalsIgnoreCase(args[0]))
                    || (args.length == 4 && unidentified)) {
                List.of("1", "4", "16", "64").stream().filter(x -> x.startsWith(prefix)).forEach(matches::add);
            }
        }
        return matches;
    }
}
