package ru.cherdakmd.chrdkclasses;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Команды {@code /class} и {@code /classadmin} (1.4.164).
 *
 * <p>Здесь только разбор аргументов и тексты: можно ли выбрать класс, хватает
 * ли очков и уровней навыка, решают {@link TalentService} и хранилище. Поэтому
 * команда и меню ведут себя одинаково — включая кулдауны и сброс дерева.</p>
 */
public final class ClassesCommandHandler implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("help", "menu", "choose", "info", "talents",
            "learn", "respec", "points", "top", "ability", "passport", "abilities", "builds");
    private static final List<String> ADMIN_SUBS = List.of("reload", "info", "set", "reset",
            "respec");

    private final ClassesPlugin plugin;

    public ClassesCommandHandler(ClassesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("classadmin")) {
            return admin(sender, args);
        }
        if (name.equals("ability")) {
            // Отдельная команда для боя: /class ability длиннее, а умение нужно
            // на горячей клавише.
            if (!sender.hasPermission("chrdkclasses.ability")) {
                plugin.messages().send(sender, "no-permission");
                return true;
            }
            if (!plugin.config().enabled()) {
                plugin.messages().send(sender, "disabled");
                return true;
            }
            if (!(sender instanceof Player player)) {
                plugin.messages().send(sender, "player-only");
                return true;
            }
            useAbility(player, abilitySlot(args));
            return true;
        }
        if (!sender.hasPermission("chrdkclasses.use")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(sender, "disabled");
            return true;
        }
        if (sender instanceof Player player && !plugin.ready(player)) {
            plugin.messages().send(sender,"development-pending"); return true;
        }
        if (args.length == 0) {
            if (sender instanceof Player player) {
                plugin.gui().open(player);
            } else {
                help(sender);
            }
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "abilities" -> { if(sender instanceof Player p) plugin.developmentGui().abilities(p); }
            case "builds" -> { if(sender instanceof Player p) plugin.developmentGui().builds(p); }
            case "help" -> help(sender);
            case "menu", "passport" -> menu(sender);
            case "choose" -> choose(sender, args);
            case "info" -> info(sender, args);
            case "talents" -> talents(sender);
            case "learn" -> learn(sender, args);
            case "respec" -> respec(sender);
            case "points" -> points(sender);
            case "top" -> top(sender);
            case "ability" -> {
                Player player = player(sender);
                if (player != null) {
                    useAbility(player, abilitySlot(args.length > 1 ? new String[]{args[1]} : new String[0]));
                }
            }
            default -> {
                plugin.messages().send(sender, "unknown-subcommand", "sub", args[0]);
                help(sender);
            }
        }
        return true;
    }

    /* --------------------------------- игроку --------------------------------- */

    private void help(CommandSender sender) {
        plugin.messages().send(sender, "help-header");
        for (String key : List.of("help-menu", "help-choose", "help-info", "help-talents",
                "help-learn", "help-respec", "help-points", "help-ability", "help-top")) {
            plugin.messages().send(sender, key);
        }
        plugin.messages().send(sender, "help-footer",
                "per", String.valueOf(plugin.config().levelsPerLevel()));
    }

    private void menu(CommandSender sender) {
        Player player = player(sender);
        if (player != null) {
            plugin.gui().open(player);
        }
    }

    private void choose(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "choose-usage");
            return;
        }
        ClassResult result = plugin.service().choose(player.getUniqueId(), args[1],
                System.currentTimeMillis());
        sendResult(player, result);
        if (result.ok()) {
            plugin.requestSave();
            plugin.gameplay().clear(player.getUniqueId());
            plugin.effects().apply(player);
            if (plugin.config().actionbar()) {
                player.sendActionBar(plugin.messages().get("choose-actionbar", "class",
                        Messages.trusted(plugin.messages().raw(
                                "class." + args[1].toLowerCase(Locale.ROOT) + ".name"))));
            }
        }
    }

    private void info(CommandSender sender, String[] args) {
        String query = args.length > 1 ? args[1] : "";
        if (query.isEmpty()) {
            showOwn(sender);
            return;
        }
        PlayerClass target = ClassCatalog.playerClass(query);
        if (target == null) {
            plugin.messages().send(sender, "class-unknown", "class", query);
            return;
        }
        plugin.messages().send(sender, "info-header", "class",
                Messages.trusted(plugin.messages().raw("class." + target.id() + ".name")));
        plugin.messages().send(sender, "class." + target.id() + ".desc");
        plugin.messages().send(sender, "info-passive-header");
        for (Map.Entry<EffectKind, Double> entry : ClassCatalog.passive(target.id()).entrySet()) {
            plugin.messages().send(sender, "effect." + entry.getKey().id(),
                    "amount", ClassMath.amount(entry.getKey(), entry.getValue()));
        }
        for (String branch : ClassCatalog.branches(target.id())) {
            plugin.messages().send(sender, "info-branch-header", "branch",
                    Messages.trusted(plugin.messages().raw("branch." + branch)));
            for (Talent talent : ClassCatalog.branch(target.id(), branch)) {
                plugin.messages().send(sender, "info-talent-line",
                        "talent", Messages.trusted(plugin.messages().raw(
                                "talent." + talent.id() + ".name")),
                        "cost", String.valueOf(talent.cost()),
                        "effect", Messages.trusted(plugin.messages().raw(
                                "effect." + talent.kind().id(),
                                "amount", ClassMath.amount(talent.kind(), talent.value()))));
            }
        }
        plugin.messages().send(sender, "info-footer");
    }

    private void showOwn(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return;
        }
        UUID id = player.getUniqueId();
        TalentService service = plugin.service();
        if(service.development()!=null) {plugin.developmentGui().progress(id).forEach(sender::sendMessage);return;}
        PlayerClass playerClass = service.classOf(id);
        if (playerClass == null) {
            plugin.messages().send(sender, "info-none");
            plugin.messages().send(sender, "info-none-hint");
            return;
        }
        plugin.messages().send(sender, "info-header", "class",
                Messages.trusted(plugin.messages().raw("class." + playerClass.id() + ".name")));
        for (var line : ClassPassportText.progress(plugin.messages(), service.progressSnapshot(id, System.currentTimeMillis()),
                service.spentPoints(id))) {
            sender.sendMessage(line);
        }
        if (service.store().talentCount(id) == 0) {
            plugin.messages().send(sender, "info-talents-empty");
        } else {
            plugin.messages().send(sender, "info-talents-header",
                    "count", String.valueOf(service.store().talentCount(id)),
                    "max", String.valueOf(ClassCatalog.TALENTS_PER_CLASS));
            for (String talentId : service.store().talents(id)) {
                Talent talent = ClassCatalog.talent(talentId);
                if (talent == null) {
                    continue;
                }
                plugin.messages().send(sender, "info-talent-own",
                        "talent", Messages.trusted(plugin.messages().raw(
                                "talent." + talent.id() + ".name")),
                        "effect", Messages.trusted(plugin.messages().raw(
                                "effect." + talent.kind().id(),
                                "amount", ClassMath.amount(talent.kind(), talent.value()))));
            }
        }
        long left = service.changeCooldownLeft(id, System.currentTimeMillis());
        if (left > 0) {
            plugin.messages().send(sender, "info-cooldown", "time", ClassMath.clock(left));
        }
    }

    private void talents(CommandSender sender) {
        Player player = player(sender);
        if (player != null) {
            plugin.gui().openTalents(player);
        }
    }

    private void learn(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "learn-usage");
            return;
        }
        ClassResult result = plugin.service().learn(player.getUniqueId(), args[1]);
        sendResult(player, result);
        if (result.ok()) {
            plugin.requestSave();
            plugin.effects().apply(player);
        }
    }

    /**
     * Применяет умение класса и показывает исход.
     *
     * <p>У команды один необязательный аргумент — номер умения: без него
     * срабатывает первое, {@code /ability 2} — второе. Это нужно, чтобы оба
     * умения можно было повесить на разные горячие клавиши: в меню у них две
     * отдельные кнопки, а в бою меню не открывают.</p>
     *
     * <p>Умение не тратит очки и не меняет сохранённые таланты: единственное
     * состояние — момент применения в {@code classes.json}, который пишет
     * {@link AbilityService}. Поэтому здесь нет отдельной проверки прав: её
     * делают команда и кнопка меню.</p>
     */
    private void useAbility(Player player, int slot) {
        ClassResult result = plugin.abilities().use(player, slot);
        plugin.messages().send(player, result.key(),
                ClassText.localise(plugin.messages(), result).toArray());
        if (!result.ok() || !plugin.config().actionbar()) {
            return;
        }
        PlayerClass playerClass = plugin.service().classOf(player.getUniqueId());
        ClassAbility ability = plugin.service().development().equipped(player.getUniqueId(),slot);
        if (ability == null) {
            return;
        }
        player.sendActionBar(plugin.messages().get("ability-actionbar", "ability",
                Messages.trusted(plugin.messages().raw(
                        "ability." + ability.key() + ".name"))));
    }

    /**
     * Номер умения из аргументов: {@code 2} — второе, всё остальное — первое.
     *
     * <p>Молчаливое приведение, а не ошибка: {@code /ability fire} должен
     * просто сработать первым умением, иначе игрок в бою получит лекцию о
     * синтаксисе вместо удара.</p>
     */
    static int abilitySlot(String[] args) {
        if (args == null || args.length == 0) {
            return ClassAbility.FIRST;
        }
        String raw = args[0] == null ? "" : args[0].trim();
        return raw.equals("2") || raw.equalsIgnoreCase("second") || raw.equalsIgnoreCase("vtoroe")
                ? ClassAbility.SECOND : ClassAbility.FIRST;
    }

    private void respec(CommandSender sender) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        plugin.gui().openRespec(player);
    }

    private void points(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return;
        }
        UUID id = player.getUniqueId();
        TalentService service = plugin.service();
        if (service.classOf(id) == null) {
            plugin.messages().send(sender, "info-none");
            return;
        }
        if(service.development()!=null) {plugin.developmentGui().progress(id).forEach(sender::sendMessage);return;}
        var snapshot = service.progressSnapshot(id, System.currentTimeMillis());
        for (var line : ClassPassportText.progress(plugin.messages(), snapshot, service.spentPoints(id))) {
            sender.sendMessage(line);
        }
    }

    private void top(CommandSender sender) {
        List<Map.Entry<PlayerClass, Integer>> rows = new ArrayList<>();
        for (PlayerClass value : ClassCatalog.classes()) {
            rows.add(Map.entry(value, plugin.store().countOf(value.id())));
        }
        rows.sort(Comparator.<Map.Entry<PlayerClass, Integer>>comparingInt(Map.Entry::getValue)
                .reversed()
                .thenComparing(entry -> entry.getKey().id()));
        plugin.messages().send(sender, "top-header", "count", String.valueOf(plugin.store().size()));
        int place = 1;
        for (Map.Entry<PlayerClass, Integer> row : rows) {
            plugin.messages().send(sender, "top-line",
                    "place", String.valueOf(place++),
                    "class", Messages.trusted(plugin.messages().raw(
                            "class." + row.getKey().id() + ".name")),
                    "count", String.valueOf(row.getValue()));
        }
    }

    /* --------------------------------- админу --------------------------------- */

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdkclasses.admin")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            plugin.messages().send(sender, "admin-usage");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                plugin.reloadClasses();
                plugin.messages().send(sender, "admin-reloaded",
                        "classes", String.valueOf(ClassCatalog.classes().size()),
                        "talents", String.valueOf(
                                ClassCatalog.classes().size() * ClassCatalog.TALENTS_PER_CLASS));
            }
            case "info" -> adminInfo(sender, args);
            case "set" -> adminSet(sender, args);
            case "reset" -> adminReset(sender, args);
            case "respec" -> adminRespec(sender, args);
            default -> plugin.messages().send(sender, "admin-usage");
        }
        return true;
    }

    private void adminInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "admin-usage");
            return;
        }
        UUID target = target(sender, args[1]);
        if (target == null) {
            return;
        }
        TalentService service = plugin.service();
        PlayerClass playerClass = service.classOf(target);
        String name = displayName(target, args[1]);
        if (playerClass == null) {
            plugin.messages().send(sender, "admin-info-none", "player", name);
            return;
        }
        plugin.messages().send(sender, "admin-info-header", "player", name);
        plugin.messages().send(sender, "admin-info-line",
                "class", Messages.trusted(plugin.messages().raw(
                        "class." + playerClass.id() + ".name")),
                "level", String.valueOf(service.level(target)),
                "points", String.valueOf(service.availablePoints(target)),
                "talents", String.valueOf(plugin.store().talentCount(target)));
    }

    private void adminSet(CommandSender sender, String[] args) {
        if (args.length < 3) {
            plugin.messages().send(sender, "admin-usage");
            return;
        }
        UUID target = target(sender, args[1]);
        if (target == null) {
            return;
        }
        ClassResult result = plugin.service().setByAdmin(target, args[2],
                System.currentTimeMillis());
        if (!result.ok()) {
            sendResult(sender, result);
            return;
        }
        // Ид берём из ответа сервиса: он уже приведён к каноническому виду.
        String classId = result.args().size() > 1 ? result.args().get(1) : args[2];
        plugin.requestSave();
        plugin.messages().send(sender, "admin-set",
                "player", displayName(target, args[1]),
                "class", Messages.trusted(plugin.messages().raw("class." + classId + ".name")));
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            plugin.effects().apply(online);
            plugin.messages().send(online, "admin-set-notice", "class",
                    Messages.trusted(plugin.messages().raw("class." + classId + ".name")));
        }
    }

    private void adminReset(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "admin-usage");
            return;
        }
        UUID target = target(sender, args[1]);
        if (target == null) {
            return;
        }
        ClassResult result = plugin.service().resetByAdmin(target);
        if (!result.ok()) {
            plugin.messages().send(sender, result.key());
            return;
        }
        plugin.requestSave();
        plugin.messages().send(sender, "admin-reset-ok", "player", displayName(target, args[1]));
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            plugin.effects().clear(online);
            plugin.effects().apply(online);
        }
    }

    private void adminRespec(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "admin-usage");
            return;
        }
        UUID target = target(sender, args[1]);
        if (target == null) {
            return;
        }
        if (!plugin.service().development().prepare(target,System.currentTimeMillis())) {
            plugin.messages().send(sender,"development-pending");return;
        }
        ClassBuild build=plugin.store().build(target);
        if(build==null||build.ranks().isEmpty()){plugin.messages().send(sender,"respec-empty");return;}
        int cleared=build.ranks().size();
        plugin.store().installBuild(target,new ClassBuild(build.classId(),Map.of(),build.abilities()),System.currentTimeMillis(),true);
        plugin.gameplay().clear(target);
        plugin.requestSave();
        plugin.messages().send(sender, "admin-respec",
                "player", displayName(target, args[1]),
                "talents", String.valueOf(cleared));
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            plugin.effects().apply(online);
        }
    }

    /* -------------------------------- помощники -------------------------------- */

    private Player player(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        plugin.messages().send(sender, "player-only");
        return null;
    }

    /** Ид игрока по нику: сначала онлайн, потом сохранённые данные. */
    private UUID target(CommandSender sender, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            plugin.store().rename(online.getUniqueId(), online.getName());
            return online.getUniqueId();
        }
        UUID stored = plugin.store().byName(name);
        if (stored != null) {
            return stored;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (offline.hasPlayedBefore() || offline.isOnline()) {
            return offline.getUniqueId();
        }
        plugin.messages().send(sender, "admin-target-none", "player", name);
        return null;
    }

    private String displayName(UUID id, String fallback) {
        String stored = plugin.store().name(id);
        return stored == null || stored.isBlank() ? fallback : stored;
    }

    /**
     * Показывает результат операции, заменяя иды на имена из {@code messages.yml}.
     *
     * <p>Сервис не знает о текстах и возвращает чистые иды — так его можно
     * проверять без сервера. Перевод — забота команды, поэтому он здесь, а
     * неизвестный ид (например, опечатка в {@code /class choose}) остаётся как
     * есть: игрок должен увидеть ровно то, что ввёл.</p>
     */
    private void sendResult(CommandSender sender, ClassResult result) {
        plugin.messages().send(sender, result.key(),
                ClassText.localise(plugin.messages(), result).toArray());
    }

    /* -------------------------------- автодополнение --------------------------- */

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias,
                                      String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("ability")) {
            // Единственный аргумент — номер умения; подсказываем оба.
            return args.length <= 1 ? filter(List.of("1", "2"), args.length == 0 ? "" : args[0])
                    : List.of();
        }
        boolean admin = name.equals("classadmin");
        if (admin ? !sender.hasPermission("chrdkclasses.admin")
                : !sender.hasPermission("chrdkclasses.use")) {
            return List.of();
        }
        if (args.length <= 1) {
            return filter(admin ? ADMIN_SUBS : SUBS, args.length == 0 ? "" : args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (sub) {
                case "choose", "info" -> filter(classIds(), args[1]);
                case "learn" -> filter(learnable(sender), args[1]);
                case "ability" -> filter(List.of("1", "2"), args[1]);
                case "set", "reset", "respec" -> filter(playerNames(), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3 && admin && sub.equals("set")) {
            return filter(classIds(), args[2]);
        }
        return List.of();
    }

    private List<String> classIds() {
        List<String> ids = new ArrayList<>();
        for (PlayerClass value : ClassCatalog.classes()) {
            ids.add(value.id());
        }
        return ids;
    }

    private List<String> learnable(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        PlayerClass playerClass = plugin.service().classOf(player.getUniqueId());
        if (playerClass == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (Talent talent : ClassTrees.talents(playerClass.id())) {
            if (plugin.store().build(player.getUniqueId())!=null && plugin.store().build(player.getUniqueId()).ranks().getOrDefault(talent.id(),0)<talent.maxRank()) {
                ids.add(talent.id());
            }
        }
        return ids;
    }

    private static List<String> playerNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }
}
