package ru.cherdakmd.chrdkartifacts;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code /artifacts} — каталог с пагинацией, карточка артефакта, коллекция и
 * справочник свойств; {@code /artifactsadmin} — give/reload.
 *
 * <p>1.4.130: каталог больше не печатает 65 строк одним залпом (аудит A-18),
 * у каждого артефакта появилась карточка {@code /artifacts <ид>} (A-19), а
 * {@code /artifacts collection} открывает GUI коллекции. Динамические цвета
 * собираются MiniMessage напрямую: плейсхолдеры Messages экранируют теги,
 * а имена артефактов уже содержат разметку из messages.yml.</p>
 */
public final class ArtifactCommandHandler implements CommandExecutor, TabCompleter {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    /** Строк каталога на страницу: 65 артефактов → 6 страниц. */
    static final int PAGE_SIZE = 12;

    private final ArtifactsPlugin plugin;

    public ArtifactCommandHandler(ArtifactsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("artifacts")) {
            return handleArtifacts(sender, args);
        }
        if (command.getName().equalsIgnoreCase("artifactsadmin")) {
            return handleAdmin(sender, args);
        }
        return false;
    }

    /* ------------------------------- /artifacts ------------------------------- */

    private boolean handleArtifacts(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (!player.hasPermission("chrdkartifacts.use")) {
            plugin.messages().send(player, "no-permission");
            return true;
        }
        if (plugin.coreApi() == null || !plugin.coreApi().isAuthenticated(player)) {
            plugin.messages().send(player, "need-auth");
            return true;
        }
        if (args.length == 0) {
            return handleList(player, 1);
        }
        String arg = args[0];
        if (arg.equalsIgnoreCase("traits")) {
            return handleTraits(player);
        }
        if (arg.equalsIgnoreCase("collection")) {
            return handleCollection(player);
        }
        ArtifactDef def = ArtifactRegistry.byId(arg);
        if (def != null) {
            return handleCard(player, def);
        }
        Integer page = parseInt(arg);
        if (page != null) {
            return handleList(player, page);
        }
        plugin.messages().send(player, "list-bad-argument");
        return true;
    }

    /** Каталог: страница из {@value #PAGE_SIZE} строк, свежие артефакты сверху порядка реестра. */
    private boolean handleList(Player player, int page) {
        List<ArtifactDef> defs = ordered();
        int pages = Math.max(1, (defs.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.max(1, Math.min(page, pages));
        Set<String> carried = plugin.items().carriedIds(player);
        plugin.messages().send(player, "list-header",
                "page", String.valueOf(current),
                "pages", String.valueOf(pages),
                "found", String.valueOf(plugin.collection().count(player)),
                "total", String.valueOf(plugin.collection().total()));
        int from = (current - 1) * PAGE_SIZE;
        for (int index = from; index < Math.min(from + PAGE_SIZE, defs.size()); index++) {
            ArtifactDef def = defs.get(index);
            sendLine(player, def, carried.contains(def.id()));
        }
        plugin.messages().send(player, "list-footer",
                "page", String.valueOf(current),
                "pages", String.valueOf(pages),
                "found", String.valueOf(plugin.collection().count(player)),
                "total", String.valueOf(plugin.collection().total()));
        return true;
    }

    private void sendLine(Player player, ArtifactDef def, boolean carried) {
        String name = plugin.items().nameOf(def);
        String colorHex = switch (def.side()) {
            case GOOD -> def.mode() == ArtifactDef.Mode.ACTIVE ? "#FFD700" : "#FFEE88";
            case CURSED -> def.mode() == ArtifactDef.Mode.ACTIVE ? "#8B0000" : "#CC4444";
        };
        String mark = carried ? " <green>✔</green>" : "";
        String modeTag = def.mode() == ArtifactDef.Mode.ACTIVE ? "⌛" : "∞";
        player.sendMessage(MINI.deserialize(
                "<color:" + colorHex + ">" + modeTag + " " + name + "</color>"
                        + " <gray><dark_gray>(</dark_gray>" + def.id() + "<dark_gray>)</dark_gray></gray>"
                        + mark));
    }

    private List<ArtifactDef> ordered() {
        List<ArtifactDef> defs = new ArrayList<>(ArtifactRegistry.bySide(ArtifactDef.Side.GOOD));
        defs.addAll(ArtifactRegistry.bySide(ArtifactDef.Side.CURSED));
        return defs;
    }

    /** Карточка артефакта: сторона, режим, цена, источник и статус коллекции. */
    private boolean handleCard(Player player, ArtifactDef def) {
        player.sendMessage(MINI.deserialize("<gold>══════ " + plugin.items().nameOf(def)
                + " ══════</gold>"));
        plugin.messages().send(player, switch (def.side()) {
            case GOOD -> def.mode() == ArtifactDef.Mode.ACTIVE
                    ? "card-good-active" : "card-good-passive";
            case CURSED -> def.mode() == ArtifactDef.Mode.ACTIVE
                    ? "card-cursed-active" : "card-cursed-passive";
        });
        String desc = ArtifactText.loreOrEmpty(
                plugin.messages().raw("art." + def.id() + ".lore"));
        if (!desc.isEmpty()) {
            plugin.messages().send(player, "card-desc", "text", Messages.trusted(desc));
        }
        if (def.mode() == ArtifactDef.Mode.ACTIVE) {
            plugin.messages().send(player, "card-active",
                    "cooldown", String.valueOf(plugin.config().cooldownSeconds(def)),
                    "xp", String.valueOf(plugin.config().xpCostLevels(def)));
        }
        plugin.messages().send(player, "card-source");
        if (!plugin.config().isEnabled(def.id())) {
            plugin.messages().send(player, "card-disabled");
        }
        plugin.messages().send(player, plugin.collection().isDiscovered(player, def.id())
                ? "card-collection-found" : "card-collection-unknown");
        plugin.messages().send(player, "card-footer");
        return true;
    }

    /** Коллекция: скан инвентаря на случай предметов, полученных до 1.4.130, и GUI. */
    private boolean handleCollection(Player player) {
        if (!plugin.config().collection().enabled()) {
            plugin.messages().send(player, "collection-disabled");
            return true;
        }
        plugin.collection().scan(player);
        plugin.collectionGui().open(player, 0);
        return true;
    }

    /**
     * Каталог свойств артефактов (1.4.129): 15 полезных ▲ и 15 вредных ▼.
     * Показывает имя, краткое описание и действующие лимиты из конфига.
     */
    private boolean handleTraits(Player player) {
        plugin.messages().send(player, "traits-header");
        plugin.messages().send(player, "traits-header-good");
        for (ArtifactTraits.Def trait : ArtifactTraits.good()) {
            plugin.messages().send(player, "traits-line-good",
                    "name", plugin.messages().mini("trait." + trait.id() + ".name"),
                    "desc", plugin.messages().mini("trait." + trait.id() + ".desc"));
        }
        plugin.messages().send(player, "traits-header-bad");
        for (ArtifactTraits.Def trait : ArtifactTraits.bad()) {
            plugin.messages().send(player, "traits-line-bad",
                    "name", plugin.messages().mini("trait." + trait.id() + ".name"),
                    "desc", plugin.messages().mini("trait." + trait.id() + ".desc"));
        }
        ArtifactsConfig.Traits config = plugin.config().traits();
        plugin.messages().send(player, "traits-footer",
                "max", String.valueOf(config.maxPerItem()),
                "chance", String.valueOf(config.chancePercent()),
                "level", String.valueOf(config.maxLevel()));
        return true;
    }

    /* ----------------------------- /artifactsadmin ---------------------------- */

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdkartifacts.admin")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (plugin.awards().admin(sender,args)) return true;
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadArtifactsConfig();
            plugin.messages().send(sender, "admin-reloaded");
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("give")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                plugin.messages().send(sender, "admin-player-not-found");
                return true;
            }
            ArtifactDef def = args.length >= 3 ? ArtifactRegistry.byId(args[2]) : null;
            if (def == null) {
                plugin.messages().send(sender, "admin-unknown-artifact");
                return true;
            }
            int amount = 1;
            if (args.length >= 4) {
                try {
                    amount = Math.max(1, Math.min(64, Integer.parseInt(args[3])));
                } catch (NumberFormatException ignored) {
                    // оставляем 1
                }
            }
            ItemStack stack = plugin.items().build(def, amount);
            // Полный инвентарь не должен «съедать» выдачу: излишек падает под ноги.
            Map<Integer, ItemStack> leftover = target.getInventory().addItem(stack);
            int dropped = 0;
            for (ItemStack left : leftover.values()) {
                dropped += left.getAmount();
                target.getWorld().dropItemNaturally(target.getLocation(), left);
            }
            // Выдача администратора тоже открывает артефакт в коллекции.
            plugin.collection().record(target, def.id());
            plugin.messages().send(sender, "admin-given",
                    "player", target.getName(),
                    "name", Messages.trusted(plugin.items().nameOf(def)),
                    "amount", String.valueOf(amount));
            if (dropped > 0) {
                plugin.messages().send(sender, "admin-given-dropped",
                        "player", target.getName(),
                        "amount", String.valueOf(dropped));
            }
            plugin.messages().send(target, "admin-received",
                    "name", Messages.trusted(plugin.items().nameOf(def)));
            return true;
        }
        plugin.messages().send(sender, "admin-usage");
        return true;
    }

    /* ------------------------------- tab-complete ----------------------------- */

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("artifacts")) {
            if (args.length == 1) {
                List<String> options = new ArrayList<>(List.of("collection", "traits"));
                int pages = Math.max(1, (ArtifactRegistry.size() + PAGE_SIZE - 1) / PAGE_SIZE);
                for (int page = 2; page <= pages; page++) {
                    options.add(String.valueOf(page));
                }
                for (ArtifactDef def : ArtifactRegistry.all()) {
                    options.add(def.id());
                }
                return filter(options, args[0]);
            }
            return List.of();
        }
        if (!command.getName().equalsIgnoreCase("artifactsadmin")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(List.of("give", "reload"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                    .limit(20).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            List<String> ids = new ArrayList<>();
            for (ArtifactDef def : ArtifactRegistry.all()) {
                ids.add(def.id());
            }
            return filter(ids, args[2]);
        }
        return List.of();
    }

    private static Integer parseInt(String value) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static List<String> filter(List<String> options, String prefix) {
        String low = prefix.toLowerCase();
        return options.stream().filter(o -> o.toLowerCase().startsWith(low)).toList();
    }
}
