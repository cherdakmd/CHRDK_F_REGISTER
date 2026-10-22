package ru.cherdakmd.chrdkpets;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /pet} — карточка, список, топ и кличка; {@code /petsadmin} — управление.
 *
 * <p>Карточка и переименование работают с ближайшим живым питомцем игрока в
 * радиусе {@code general.interact-radius}: целиться в сущность не нужно, а
 * случайно переименовать чужого питомца нельзя — поиск идёт только по
 * владельцу.</p>
 */
public final class PetCommandHandler implements CommandExecutor, TabCompleter {

    private final PetsPlugin plugin;

    public PetCommandHandler(PetsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("petsadmin")) {
            return handleAdmin(sender, args);
        }
        return handlePlayer(sender, args);
    }

    /* ---------------------------------------------------------------- игроки */

    private boolean handlePlayer(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdkpets.use")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(player, "pets-disabled");
            return true;
        }
        if (args.length == 0) {
            return card(player);
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> help(player);
            case "list" -> list(player);
            case "top" -> top(player);
            case "name" -> rename(player, args);
            default -> {
                plugin.messages().send(player, "usage");
                yield true;
            }
        };
    }

    private boolean card(Player player) {
        LivingEntity pet = plugin.service().nearestOwned(player, plugin.config().interactRadius());
        PetStore.PetData data = pet == null ? null : plugin.service().data(pet);
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
                "species", plugin.messages().plain("species-" + data.species().id()));
        plugin.messages().send(player, "card-level",
                "level", String.valueOf(level),
                "progress", progressText(progress));
        if (progress.need() > 0) {
            plugin.messages().send(player, "card-bar",
                    "bar", PetCurve.bar(progress.percent(), 10),
                    "percent", String.valueOf(progress.percent()));
        } else {
            plugin.messages().send(player, "card-max");
        }
        plugin.messages().send(player, "card-health",
                "health", number(stats.health()),
                "speed", number(stats.speedScalar() * 100));
        if (data.species().canAttack() && stats.damage() > 0) {
            plugin.messages().send(player, "card-damage", "damage", number(stats.damage()));
        }
        if (stats.ownerSpeedScalar() > 0) {
            plugin.messages().send(player, "card-aura-speed",
                    "speed", number(stats.ownerSpeedScalar() * 100));
        } else if (stats.ownerLuck() > 0) {
            plugin.messages().send(player, "card-aura-luck",
                    "luck", number(stats.ownerLuck()));
        } else {
            plugin.messages().send(player, "card-aura-none");
        }
        return true;
    }

    private boolean list(Player player) {
        List<PetStore.PetData> pets = plugin.store().petsOf(player.getUniqueId());
        if (pets.isEmpty()) {
            plugin.messages().send(player, "list-empty");
            return true;
        }
        int alive = plugin.store().alivePetsOf(player.getUniqueId()).size();
        plugin.messages().send(player, "list-header",
                "total", String.valueOf(pets.size()),
                "alive", String.valueOf(alive));
        for (PetStore.PetData data : pets) {
            int level = plugin.service().level(data);
            if (data.dead()) {
                plugin.messages().send(player, "list-line-dead",
                        "name", plugin.service().displayName(data),
                        "species", plugin.messages().plain("species-" + data.species().id()),
                        "level", String.valueOf(level));
                continue;
            }
            PetCurve.LevelProgress progress = PetCurve.progress(data.xp(), plugin.config().curve());
            plugin.messages().send(player, "list-line",
                    "name", plugin.service().displayName(data),
                    "species", plugin.messages().plain("species-" + data.species().id()),
                    "level", String.valueOf(level),
                    "progress", progressText(progress));
        }
        plugin.messages().send(player, "list-footer");
        return true;
    }

    private boolean top(Player player) {
        List<PetStore.PetData> top = plugin.store().top(plugin.config().topSize());
        if (top.isEmpty()) {
            plugin.messages().send(player, "top-empty");
            return true;
        }
        plugin.messages().send(player, "top-header");
        int place = 1;
        for (PetStore.PetData data : top) {
            plugin.messages().send(player, "top-line",
                    "place", String.valueOf(place++),
                    "name", plugin.service().displayName(data),
                    "owner", data.ownerName().isBlank() ? "—" : data.ownerName(),
                    "species", plugin.messages().plain("species-" + data.species().id()),
                    "level", String.valueOf(plugin.service().level(data)));
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
        LivingEntity pet = plugin.service().nearestOwned(player, plugin.config().interactRadius());
        PetStore.PetData data = pet == null ? null : plugin.service().data(pet);
        if (data == null) {
            plugin.messages().send(player, "no-pet");
            return true;
        }
        String raw = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        String clean = PetNames.sanitize(raw, plugin.config().maxNameLength());
        if (!PetNames.valid(clean, plugin.config().minNameLength(), plugin.config().maxNameLength())) {
            plugin.messages().send(player, "name-invalid",
                    "min", String.valueOf(plugin.config().minNameLength()),
                    "max", String.valueOf(plugin.config().maxNameLength()));
            return true;
        }
        if (clean.equals(data.name())) {
            plugin.messages().send(player, "name-same");
            return true;
        }
        plugin.service().rename(pet, clean);
        plugin.messages().send(player, "name-changed", "name", clean);
        return true;
    }

    private boolean help(Player player) {
        plugin.messages().send(player, "help-header");
        plugin.messages().send(player, "help-card");
        plugin.messages().send(player, "help-list");
        plugin.messages().send(player, "help-name");
        plugin.messages().send(player, "help-top");
        plugin.messages().send(player, "help-footer");
        return true;
    }

    /* ----------------------------------------------------------------- админ */

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdkpets.admin")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
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
        plugin.reloadPetsConfig();
        plugin.messages().send(sender, "admin-reloaded",
                "pets", String.valueOf(plugin.store().size()),
                "alive", String.valueOf(plugin.store().aliveCount()));
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
            plugin.messages().send(sender, "admin-bad-level");
            return true;
        }
        int target = Math.max(1, Math.min(plugin.config().curve().maxLevel(), level));
        UUID ownerId = resolveOwner(args[1]);
        if (ownerId == null) {
            plugin.messages().send(sender, "admin-unknown-player", "player", args[1]);
            return true;
        }
        List<PetStore.PetData> pets = plugin.store().alivePetsOf(ownerId);
        if (pets.isEmpty()) {
            plugin.messages().send(sender, "admin-no-pets", "player", args[1]);
            return true;
        }
        for (PetStore.PetData data : pets) {
            plugin.service().setLevel(data, target);
        }
        plugin.getLogger().info("Питомцы: " + sender.getName() + " выставил уровень " + target
                + " игроку " + args[1] + " (" + pets.size() + " шт.).");
        plugin.messages().send(sender, "admin-setlevel",
                "player", args[1],
                "level", String.valueOf(target),
                "count", String.valueOf(pets.size()));
        return true;
    }

    private boolean info(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "admin-usage");
            return true;
        }
        UUID ownerId = resolveOwner(args[1]);
        if (ownerId == null) {
            plugin.messages().send(sender, "admin-unknown-player", "player", args[1]);
            return true;
        }
        List<PetStore.PetData> pets = plugin.store().petsOf(ownerId);
        if (pets.isEmpty()) {
            plugin.messages().send(sender, "admin-no-pets", "player", args[1]);
            return true;
        }
        int alive = 0;
        int dead = 0;
        int best = 1;
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
                "player", args[1],
                "alive", String.valueOf(alive),
                "dead", String.valueOf(dead),
                "best", String.valueOf(best),
                "xp", String.valueOf(xp));
        return true;
    }

    /** UUID владельца по нику: сначала онлайн, потом последний известный в данных. */
    private UUID resolveOwner(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        for (PetStore.PetData data : plugin.store().all()) {
            if (data.ownerName().equalsIgnoreCase(name)) {
                return data.ownerId();
            }
        }
        return null;
    }

    /* ------------------------------------------------------------- оформление */

    private String progressText(PetCurve.LevelProgress progress) {
        if (progress.need() <= 0) {
            return plugin.messages().plain("max-label");
        }
        return progress.into() + "/" + progress.need();
    }

    /** Число без хвостовых нулей: 18.0 → «18», 3.60 → «3.6», 2.25 → «2.25». */
    private static String number(double value) {
        String text = String.format(Locale.ROOT, "%.2f", value);
        text = text.replaceAll("0+$", "");
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return text.isEmpty() ? "0" : text;
    }

    /* ------------------------------------------------------------ автодополнение */

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            List<String> base = command.getName().equalsIgnoreCase("petsadmin")
                    ? List.of("reload", "setlevel", "info", "help")
                    : List.of("help", "list", "top", "name");
            for (String option : base) {
                if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(option);
                }
            }
            return out;
        }
        if (command.getName().equalsIgnoreCase("petsadmin") && args.length == 2) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT)
                        .startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(player.getName());
                }
            }
        }
        if (command.getName().equalsIgnoreCase("petsadmin") && args.length == 3
                && args[0].equalsIgnoreCase("setlevel")) {
            for (int level = 1; level <= plugin.config().curve().maxLevel(); level++) {
                String option = String.valueOf(level);
                if (option.startsWith(args[2])) {
                    out.add(option);
                }
            }
        }
        return out;
    }
}
