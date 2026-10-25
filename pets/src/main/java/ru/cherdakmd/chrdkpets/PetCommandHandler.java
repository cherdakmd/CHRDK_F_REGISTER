package ru.cherdakmd.chrdkpets;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Команды {@code /pet} и {@code /petsadmin}.
 *
 * <p>Игрок видит только своих питомцев: ближайший ищется по UUID владельца, а не
 * по «первому попавшемуся волку». Администратор может перечитать конфиг,
 * выдать уровень и посмотреть сводку.</p>
 */
public final class PetCommandHandler implements CommandExecutor, TabCompleter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final PetsPlugin plugin;

    public PetCommandHandler(PetsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("petsadmin")) {
            return admin(sender, args);
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(player, "pets-disabled");
            return true;
        }
        if (!player.hasPermission("chrdkpets.use")) {
            plugin.messages().send(player, "no-permission");
            return true;
        }
        if (args.length == 0) {
            return card(player);
        }
        if (args[0].equalsIgnoreCase("help")) {
            return help(player);
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(player);
            case "top" -> top(player);
            case "name" -> rename(player, args);
            default -> {
                plugin.messages().send(player, "usage");
                yield true;
            }
        };
    }

    /* ----------------------------------------------------------------- игрок */

    private boolean help(Player player) {
        plugin.messages().send(player, "help-header");
        plugin.messages().send(player, "help-card");
        plugin.messages().send(player, "help-list");
        plugin.messages().send(player, "help-top");
        plugin.messages().send(player, "help-name");
        plugin.messages().send(player, "help-footer");
        return true;
    }

    private boolean card(Player player) {
        LivingEntity pet = nearest(player);
        if (pet == null) {
            plugin.messages().send(player, "no-pet");
            return true;
        }
        PetStore.PetData data = plugin.service().data(pet);
        if (data == null) {
            plugin.messages().send(player, "no-pet");
            return true;
        }
        int level = plugin.service().level(data);
        PetCurve.LevelProgress progress = PetCurve.progress(data.xp(), plugin.config().curve());
        PetBuffs.Stats stats = PetBuffs.stats(
                plugin.config().species(data.species()).buffs(), level);

        plugin.messages().send(player, "card-header");
        plugin.messages().send(player, "card-name",
                "name", plugin.service().displayName(data),
                "species", plugin.messages().mini(speciesKey(data.species())));
        plugin.messages().send(player, "card-level",
                "level", String.valueOf(level),
                "xp", String.valueOf(data.xp()));
        if (level < plugin.config().curve().maxLevel()) {
            plugin.messages().send(player, "card-progress",
                    "bar", PetCurve.bar(progress.percent(), 10),
                    "into", String.valueOf(progress.into()),
                    "need", String.valueOf(progress.need()));
        } else {
            plugin.messages().send(player, "card-max");
        }
        if (level <= 1) {
            plugin.messages().send(player, "card-bonus-none");
        } else {
            plugin.messages().send(player, "card-bonus-health",
                    "health", format(stats.health()),
                    "speed", format(stats.speedScalar() * 100.0));
            if (data.species().canAttack() && stats.damage() > 0.0) {
                plugin.messages().send(player, "card-bonus-damage",
                        "damage", format(stats.damage()));
            }
        }
        if (stats.ownerSpeedScalar() > 0.0) {
            plugin.messages().send(player, "card-aura-speed",
                    "speed", format(stats.ownerSpeedScalar() * 100.0));
        } else if (stats.ownerLuck() > 0.0) {
            plugin.messages().send(player, "card-aura-luck",
                    "luck", format(stats.ownerLuck()));
        } else {
            plugin.messages().send(player, "card-aura-none");
        }
        plugin.messages().send(player, "card-footer");
        return true;
    }

    private boolean list(Player player) {
        List<PetStore.PetData> pets = plugin.store().petsOf(player.getUniqueId());
        if (pets.isEmpty()) {
            plugin.messages().send(player, "list-empty");
            return true;
        }
        plugin.messages().send(player, "list-header",
                "count", String.valueOf(pets.size()));
        for (PetStore.PetData data : pets) {
            int level = plugin.service().level(data);
            if (data.dead()) {
                plugin.messages().send(player, "list-line-dead",
                        "name", plugin.service().displayName(data),
                        "species", plugin.messages().mini(speciesKey(data.species())),
                        "level", String.valueOf(level),
                        "date", DATE.format(Instant.ofEpochMilli(
                                data.diedAt() > 0L ? data.diedAt() : data.created())
                                .atZone(ZoneId.systemDefault()).toLocalDate()));
            } else {
                plugin.messages().send(player, "list-line",
                        "name", plugin.service().displayName(data),
                        "species", plugin.messages().mini(speciesKey(data.species())),
                        "level", String.valueOf(level),
                        "xp", String.valueOf(data.xp()));
            }
        }
        plugin.messages().send(player, "list-footer",
                "count", String.valueOf(pets.size()));
        return true;
    }

    private boolean top(Player player) {
        List<PetStore.PetData> pets = plugin.store().top(plugin.config().topSize());
        if (pets.isEmpty()) {
            plugin.messages().send(player, "top-empty");
            return true;
        }
        plugin.messages().send(player, "top-header",
                "count", String.valueOf(pets.size()));
        int place = 1;
        for (PetStore.PetData data : pets) {
            plugin.messages().send(player, "top-line",
                    "place", String.valueOf(place++),
                    "name", plugin.service().displayName(data),
                    "species", plugin.messages().mini(speciesKey(data.species())),
                    "level", String.valueOf(plugin.service().level(data)),
                    "owner", data.ownerName().isBlank() ? "—" : data.ownerName());
        }
        return true;
    }

    private boolean rename(Player player, String[] args) {
        if (!player.hasPermission("chrdkpets.name")) {
            plugin.messages().send(player, "no-permission");
            return true;
        }
        if (args.length < 2) {
            plugin.messages().send(player, "usage");
            return true;
        }
        LivingEntity pet = nearest(player);
        if (pet == null) {
            plugin.messages().send(player, "no-pet");
            return true;
        }
        String raw = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        plugin.service().rename(player, pet, raw);
        return true;
    }

    private LivingEntity nearest(Player player) {
        return plugin.service().nearestOwned(player, plugin.config().ownerRadius());
    }

    /* -------------------------------------------------------------- админ */

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdkpets.admin")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            plugin.messages().send(sender, "admin-usage");
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> reload(sender);
            case "setlevel" -> setLevel(sender, args);
            case "info" -> info(sender, args);
            default -> {
                plugin.messages().send(sender, "admin-usage");
                yield true;
            }
        };
    }

    private boolean reload(CommandSender sender) {
        int adopted = plugin.reloadAll();
        plugin.messages().send(sender, "admin-reloaded",
                "count", String.valueOf(plugin.store().size()),
                "adopted", String.valueOf(adopted));
        return true;
    }

    private boolean setLevel(CommandSender sender, String[] args) {
        if (args.length < 3) {
            plugin.messages().send(sender, "admin-usage");
            return true;
        }
        int level;
        try {
            level = Integer.parseInt(args[2]);
        } catch (NumberFormatException exception) {
            plugin.messages().send(sender, "admin-bad-number", "value", args[2]);
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            plugin.messages().send(sender, "admin-unknown-player", "player", args[1]);
            return true;
        }
        int max = plugin.config().curve().maxLevel();
        if (level < 1 || level > max) {
            plugin.messages().send(sender, "admin-level-range",
                    "min", "1", "max", String.valueOf(max));
            return true;
        }
        List<PetStore.PetData> pets = plugin.store().alivePetsOf(target.getUniqueId());
        if (pets.isEmpty()) {
            plugin.messages().send(sender, "admin-setlevel-none", "player", target.getName());
            return true;
        }
        for (PetStore.PetData data : pets) {
            plugin.service().setLevel(data, level);
        }
        plugin.messages().send(sender, "admin-setlevel",
                "player", target.getName(),
                "level", String.valueOf(level),
                "count", String.valueOf(pets.size()));
        return true;
    }

    private boolean info(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "admin-usage");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            plugin.messages().send(sender, "admin-unknown-player", "player", args[1]);
            return true;
        }
        List<PetStore.PetData> pets = plugin.store().petsOf(target.getUniqueId());
        int alive = 0;
        int dead = 0;
        int best = 0;
        long xp = 0L;
        for (PetStore.PetData data : pets) {
            if (data.dead()) {
                dead++;
            } else {
                alive++;
            }
            best = Math.max(best, plugin.service().level(data));
            xp += data.xp();
        }
        plugin.messages().send(sender, "admin-info",
                "player", target.getName(),
                "alive", String.valueOf(alive),
                "dead", String.valueOf(dead),
                "level", String.valueOf(best),
                "xp", String.valueOf(xp));
        return true;
    }

    private static String speciesKey(PetSpecies species) {
        return species == PetSpecies.CAT ? "species-cat" : "species-wolf";
    }

    /** Число без лишних нулей: 18.0 → 18, 3.60 → 3.6, 2.25 → 2.25. */
    private static String format(double value) {
        double rounded = Math.round(value * 100.0) / 100.0;
        if (rounded == Math.rint(rounded)) {
            return String.valueOf((long) rounded);
        }
        return String.format(Locale.ROOT, "%.2f", rounded).replaceAll("0+$", "");
    }

    /* ------------------------------------------------------- автодополнение */

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("petsadmin")) {
            if (!sender.hasPermission("chrdkpets.admin")) {
                return out;
            }
            if (args.length == 1) {
                return prefix(List.of("reload", "setlevel", "info"), args[0]);
            }
            if (args.length == 2) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    out.add(player.getName());
                }
                return prefix(out, args[1]);
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("setlevel")) {
                for (int level = 1; level <= plugin.config().curve().maxLevel(); level++) {
                    out.add(String.valueOf(level));
                }
                return prefix(out, args[2]);
            }
            return out;
        }
        if (args.length == 1) {
            return prefix(List.of("help", "list", "top", "name"), args[0]);
        }
        return out;
    }

    private static List<String> prefix(List<String> values, String raw) {
        if (raw == null || raw.isEmpty()) {
            return values;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(value);
            }
        }
        return out;
    }
}
