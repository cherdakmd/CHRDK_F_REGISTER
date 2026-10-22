package ru.cherdakmd.chrdkpets;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /pet} — карточка, список, топ, кличка, забота, трюки, режим, вызов,
 * статистика, достижения, дневник, косметика и поиск; {@code /petsadmin} —
 * управление.
 *
 * <p>Все действия игрока работают с ближайшим живым питомцем в радиусе
 * {@code general.interact-radius}: целиться в сущность не нужно, а случайно
 * тронуть чужого питомца нельзя — поиск идёт только по владельцу. Команды без
 * аргументов показывают подсказку или текущее состояние, а не ошибку.</p>
 */
public final class PetCommandHandler implements CommandExecutor, TabCompleter {

    private static final DateTimeFormatter DIARY_TIME =
            DateTimeFormatter.ofPattern("dd.MM HH:mm");

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
            case "top" -> top(player, args);
            case "name" -> rename(player, args);
            case "feed" -> feed(player);
            case "pat", "stroke" -> pat(player);
            case "heal" -> heal(player);
            case "trick", "tricks" -> trick(player, args);
            case "mode" -> mode(player, args);
            case "call" -> call(player);
            case "stats" -> stats(player);
            case "achievements" -> achievements(player);
            case "diary" -> diary(player);
            case "collar" -> collar(player, args);
            case "glow" -> glow(player, args);
            case "locate" -> locate(player);
            default -> {
                plugin.messages().send(player, "usage");
                yield true;
            }
        };
    }

    private LivingEntity nearest(Player player) {
        return plugin.service().nearestOwned(player, plugin.config().interactRadius());
    }

    private PetStore.PetData nearestData(Player player) {
        LivingEntity pet = nearest(player);
        return pet == null ? null : plugin.service().data(pet);
    }

    private boolean card(Player player) {
        LivingEntity pet = nearest(player);
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
        plugin.messages().send(player, "card-title",
                "title", plugin.messages().plain(PetTitles.of(level).messageKey()));
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
        PetCare.State care = plugin.service().care(data);
        if (care != null) {
            plugin.messages().send(player, "card-care",
                    "mood", String.valueOf(care.mood()),
                    "moodbar", PetCurve.bar(care.mood(), 5),
                    "satiety", String.valueOf(care.satiety()),
                    "satietybar", PetCurve.bar(care.satiety(), 5));
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
        plugin.messages().send(player, "card-mode",
                "mode", plugin.messages().plain("mode-" + data.mode().id()),
                "minutes", String.valueOf(data.minutesTogether()));
        plugin.messages().send(player, "card-look",
                "collar", collarName(data.collar()),
                "glow", plugin.messages().plain(data.glow() ? "state-on" : "state-off"));
        plugin.messages().send(player, "card-progress",
                "tricks", String.valueOf(data.tricks().size()),
                "trickmax", String.valueOf(PetTricks.values().length),
                "achievements", String.valueOf(data.achievements().size()),
                "achievementmax", String.valueOf(PetAchievement.values().length));
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
                    "title", plugin.messages().plain(PetTitles.of(level).messageKey()),
                    "species", plugin.messages().plain("species-" + data.species().id()),
                    "level", String.valueOf(level),
                    "progress", progressText(progress));
        }
        plugin.messages().send(player, "list-footer");
        return true;
    }

    private boolean top(Player player, String[] args) {
        PetSpecies filter = args.length > 1 ? PetSpecies.byId(args[1]) : null;
        List<PetStore.PetData> top = plugin.store().top(plugin.config().topSize(), filter);
        if (args.length > 1 && filter == null) {
            plugin.messages().send(player, "top-unknown-species", "species", args[1]);
            return true;
        }
        if (top.isEmpty()) {
            plugin.messages().send(player, "top-empty");
            return true;
        }
        if (filter == null) {
            plugin.messages().send(player, "top-header");
        } else {
            plugin.messages().send(player, "top-header-filter",
                    "species", plugin.messages().plain("species-" + filter.id()));
        }
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
        LivingEntity pet = nearest(player);
        PetStore.PetData data = pet == null ? null : plugin.service().data(pet);
        if (data == null) {
            plugin.messages().send(player, "no-pet");
            return true;
        }
        int cooldown = plugin.config().movement().renameCooldownSeconds();
        long left = cooldown * 1000L - (System.currentTimeMillis() - data.lastRenamed());
        if (left > 0L) {
            plugin.messages().send(player, "name-cooldown",
                    "seconds", String.valueOf((left + 999L) / 1000L));
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

    private boolean feed(Player player) {
        plugin.service().feed(player, nearest(player));
        return true;
    }

    private boolean pat(Player player) {
        plugin.service().pat(player, nearest(player));
        return true;
    }

    private boolean heal(Player player) {
        plugin.service().heal(player, nearest(player));
        return true;
    }

    private boolean trick(Player player, String[] args) {
        LivingEntity pet = nearest(player);
        if (args.length == 1) {
            PetStore.PetData data = pet == null ? null : plugin.service().data(pet);
            if (data == null) {
                plugin.messages().send(player, "no-pet");
                return true;
            }
            PetCare.State care = plugin.service().care(data);
            int mood = care == null ? 100 : care.mood();
            plugin.messages().send(player, "trick-list-header",
                    "learned", String.valueOf(data.tricks().size()),
                    "total", String.valueOf(PetTricks.values().length));
            for (PetTricks value : PetTricks.values()) {
                String state = data.hasTrick(value.id()) ? "trick-state-known"
                        : value.availableAt(mood) ? "trick-state-open" : "trick-state-locked";
                plugin.messages().send(player, "trick-list-line",
                        "state", plugin.messages().plain(state),
                        "trick", plugin.messages().plain("trick-" + value.id()),
                        "mood", String.valueOf(value.minMood()));
            }
            plugin.messages().send(player, "trick-list-footer");
            return true;
        }
        if (args[1].equalsIgnoreCase("learn")) {
            if (args.length < 3) {
                plugin.messages().send(player, "trick-usage");
                return true;
            }
            plugin.service().learnTrick(player, pet, args[2]);
            return true;
        }
        plugin.service().performTrick(player, pet, args[1]);
        return true;
    }

    private boolean mode(Player player, String[] args) {
        if (args.length < 2) {
            PetStore.PetData data = nearestData(player);
            if (data == null) {
                plugin.messages().send(player, "no-pet");
                return true;
            }
            plugin.messages().send(player, "mode-current",
                    "mode", plugin.messages().plain("mode-" + data.mode().id()));
            return true;
        }
        PetMode mode = PetMode.byId(args[1]);
        if (!mode.id().equalsIgnoreCase(args[1])) {
            plugin.messages().send(player, "mode-usage");
            return true;
        }
        plugin.service().setMode(player, nearest(player), mode);
        return true;
    }

    private boolean call(Player player) {
        plugin.service().call(player);
        return true;
    }

    private boolean stats(Player player) {
        PetStore.PetData data = nearestData(player);
        if (data == null) {
            plugin.messages().send(player, "no-pet");
            return true;
        }
        int level = plugin.service().level(data);
        PetCurve.LevelProgress progress = PetCurve.progress(data.xp(), plugin.config().curve());
        PetCare.State care = plugin.service().care(data);
        plugin.messages().send(player, "stats-header",
                "name", plugin.service().displayName(data),
                "title", plugin.messages().plain(PetTitles.of(level).messageKey()));
        plugin.messages().send(player, "stats-progress",
                "level", String.valueOf(level),
                "best", String.valueOf(data.bestLevel()),
                "progress", progressText(progress));
        plugin.messages().send(player, "stats-counters",
                "kills", String.valueOf(data.kills()),
                "fish", String.valueOf(data.fish()),
                "minutes", String.valueOf(data.minutesTogether()),
                "deaths", String.valueOf(data.deaths()));
        long days = Math.max(0L, (System.currentTimeMillis() - data.created()) / 86_400_000L);
        plugin.messages().send(player, "stats-life",
                "days", String.valueOf(days),
                "tricks", String.valueOf(data.tricks().size()),
                "achievements", String.valueOf(data.achievements().size()));
        if (care != null) {
            plugin.messages().send(player, "stats-care",
                    "mood", String.valueOf(care.mood()),
                    "satiety", String.valueOf(care.satiety()));
        }
        return true;
    }

    private boolean achievements(Player player) {
        PetStore.PetData data = nearestData(player);
        if (data == null) {
            plugin.messages().send(player, "no-pet");
            return true;
        }
        plugin.messages().send(player, "achievements-header",
                "done", String.valueOf(data.achievements().size()),
                "total", String.valueOf(PetAchievement.values().length));
        for (PetAchievement achievement : PetAchievement.values()) {
            plugin.messages().send(player, "achievement-line",
                    "state", plugin.messages().plain(data.hasAchievement(achievement.id())
                            ? "achievement-state-done" : "achievement-state-open"),
                    "achievement", plugin.messages().plain("achievement-" + achievement.id()));
        }
        plugin.messages().send(player, "achievements-footer");
        return true;
    }

    private boolean diary(Player player) {
        PetStore.PetData data = nearestData(player);
        if (data == null) {
            plugin.messages().send(player, "no-pet");
            return true;
        }
        List<String> entries = data.diary();
        if (entries.isEmpty()) {
            plugin.messages().send(player, "diary-empty");
            return true;
        }
        plugin.messages().send(player, "diary-header",
                "name", plugin.service().displayName(data));
        for (String raw : entries) {
            PetDiary.Entry entry = PetDiary.decode(raw);
            if (entry == null) {
                continue;
            }
            plugin.messages().send(player, "diary-line",
                    "date", time(entry.at()),
                    "event", diaryEvent(entry));
        }
        return true;
    }

    private boolean collar(Player player, String[] args) {
        if (!plugin.config().cosmetics().collarEnabled()) {
            plugin.messages().send(player, "collar-disabled");
            return true;
        }
        if (args.length < 2) {
            List<String> names = new ArrayList<>();
            for (String id : PetCollar.ids()) {
                names.add(plugin.messages().plain("color-" + id));
            }
            plugin.messages().send(player, "collar-list", "colors", String.join(", ", names));
            return true;
        }
        PetStore.PetData data = nearestData(player);
        if (data == null) {
            plugin.messages().send(player, "no-pet");
            return true;
        }
        String id = args[1].toLowerCase(Locale.ROOT).replace('-', '_');
        if (PetCollar.of(id) == null) {
            plugin.messages().send(player, "collar-invalid");
            return true;
        }
        LivingEntity pet = nearest(player);
        plugin.store().setCollar(data.id(), id);
        PetCollar.apply(pet, PetCollar.of(id));
        plugin.messages().send(player, "collar-set",
                "name", plugin.service().displayName(data),
                "color", plugin.messages().plain("color-" + id));
        return true;
    }

    private boolean glow(Player player, String[] args) {
        if (!plugin.config().cosmetics().glowEnabled()) {
            plugin.messages().send(player, "glow-disabled");
            return true;
        }
        PetStore.PetData data = nearestData(player);
        if (data == null) {
            plugin.messages().send(player, "no-pet");
            return true;
        }
        boolean next;
        if (args.length > 1 && (args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
            next = args[1].equalsIgnoreCase("on");
        } else {
            next = !data.glow();
        }
        plugin.store().setGlow(data.id(), next);
        LivingEntity pet = nearest(player);
        if (pet != null) {
            pet.setGlowing(next);
        }
        plugin.messages().send(player, next ? "glow-on" : "glow-off",
                "name", plugin.service().displayName(data));
        return true;
    }

    private boolean locate(Player player) {
        List<PetStore.PetData> pets = plugin.store().alivePetsOf(player.getUniqueId());
        if (pets.isEmpty()) {
            plugin.messages().send(player, "locate-empty");
            return true;
        }
        plugin.messages().send(player, "locate-header");
        for (PetStore.PetData data : pets) {
            Entity entity = Bukkit.getServer().getEntity(data.id());
            if (!(entity instanceof LivingEntity living) || living.isDead() || !living.isValid()) {
                plugin.messages().send(player, "locate-unloaded",
                        "name", plugin.service().displayName(data));
                continue;
            }
            String distance = living.getWorld().equals(player.getWorld())
                    ? String.valueOf(Math.round(living.getLocation().distance(player.getLocation())))
                    : "—";
            plugin.messages().send(player, "locate-line",
                    "name", plugin.service().displayName(data),
                    "world", living.getWorld().getName(),
                    "x", String.valueOf(living.getLocation().getBlockX()),
                    "y", String.valueOf(living.getLocation().getBlockY()),
                    "z", String.valueOf(living.getLocation().getBlockZ()),
                    "distance", distance);
        }
        return true;
    }

    private boolean help(Player player) {
        plugin.messages().send(player, "help-header");
        plugin.messages().send(player, "help-card");
        plugin.messages().send(player, "help-care");
        plugin.messages().send(player, "help-trick");
        plugin.messages().send(player, "help-mode");
        plugin.messages().send(player, "help-call");
        plugin.messages().send(player, "help-info");
        plugin.messages().send(player, "help-cosmetics");
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
            case "mood" -> setMood(sender, args);
            case "info" -> info(sender, args);
            case "stats" -> stats(sender);
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

    private boolean setMood(CommandSender sender, String[] args) {
        if (args.length < 3) {
            plugin.messages().send(sender, "admin-usage");
            return true;
        }
        int mood;
        try {
            mood = Integer.parseInt(args[2]);
        } catch (NumberFormatException exception) {
            plugin.messages().send(sender, "admin-bad-mood");
            return true;
        }
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
        int target = Math.max(0, Math.min(100, mood));
        for (PetStore.PetData data : pets) {
            plugin.service().setMood(data, target);
        }
        plugin.messages().send(sender, "admin-mood-set",
                "player", args[1],
                "mood", String.valueOf(target),
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

    private boolean stats(CommandSender sender) {
        int total = plugin.store().size();
        int alive = plugin.store().aliveCount();
        int best = 1;
        for (PetStore.PetData data : plugin.store().all()) {
            best = Math.max(best, plugin.service().level(data));
        }
        plugin.messages().send(sender, "admin-stats",
                "total", String.valueOf(total),
                "alive", String.valueOf(alive),
                "dead", String.valueOf(total - alive),
                "best", String.valueOf(best),
                "xp", String.valueOf(plugin.store().totalXp()),
                "tricks", String.valueOf(plugin.store().trickCount()));
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

    private String collarName(String collar) {
        if (collar == null || collar.isBlank()) {
            return plugin.messages().plain("collar-default");
        }
        return plugin.messages().plain("color-" + collar);
    }

    private String diaryEvent(PetDiary.Entry entry) {
        String arg = entry.arg();
        String resolved = switch (entry.code()) {
            case "trick" -> plugin.messages().plain("trick-" + arg);
            case "mode" -> plugin.messages().plain("mode-" + arg);
            case "achievement" -> plugin.messages().plain("achievement-" + arg);
            default -> arg;
        };
        return plugin.messages().plain("diary-" + entry.code(), "arg", resolved);
    }

    private static String time(long at) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault())
                .format(DIARY_TIME);
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
        if (command.getName().equalsIgnoreCase("petsadmin")) {
            if (args.length == 1) {
                return filter(List.of("reload", "setlevel", "mood", "info", "stats", "help"), args[0]);
            }
            if (args.length == 2 && !args[0].equalsIgnoreCase("stats")
                    && !args[0].equalsIgnoreCase("reload")) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().toLowerCase(Locale.ROOT)
                            .startsWith(args[1].toLowerCase(Locale.ROOT))) {
                        out.add(player.getName());
                    }
                }
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("setlevel")) {
                for (int level = 1; level <= plugin.config().curve().maxLevel(); level++) {
                    String option = String.valueOf(level);
                    if (option.startsWith(args[2])) {
                        out.add(option);
                    }
                }
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("mood")) {
                for (int mood = 0; mood <= 100; mood += 10) {
                    String option = String.valueOf(mood);
                    if (option.startsWith(args[2])) {
                        out.add(option);
                    }
                }
            }
            return out;
        }
        if (args.length == 1) {
            return filter(List.of("help", "list", "top", "name", "feed", "pat", "heal", "trick",
                    "mode", "call", "stats", "achievements", "diary", "collar", "glow", "locate"),
                    args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            if (sub.equals("top")) {
                return filter(List.of("wolf", "cat"), args[1]);
            }
            if (sub.equals("mode")) {
                return filter(List.of("follow", "stay"), args[1]);
            }
            if (sub.equals("trick") || sub.equals("tricks")) {
                Set<String> options = new LinkedHashSet<>();
                options.add("learn");
                for (PetTricks trick : PetTricks.values()) {
                    options.add(trick.id());
                }
                return filter(List.copyOf(options), args[1]);
            }
            if (sub.equals("collar")) {
                return PetCollar.matching(args[1]);
            }
            if (sub.equals("glow")) {
                return filter(List.of("on", "off"), args[1]);
            }
        }
        if (args.length == 3 && (sub.equals("trick") || sub.equals("tricks"))
                && args[1].equalsIgnoreCase("learn")) {
            List<String> options = new ArrayList<>();
            for (PetTricks trick : PetTricks.values()) {
                options.add(trick.id());
            }
            return filter(options, args[2]);
        }
        return out;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String normalized = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(normalized)) {
                out.add(option);
            }
        }
        return out;
    }
}
