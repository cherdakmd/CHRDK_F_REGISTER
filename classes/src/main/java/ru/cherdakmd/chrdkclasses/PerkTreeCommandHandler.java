package ru.cherdakmd.chrdkclasses;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkskills.tree.PerkTreeRegistry;
import ru.cherdakmd.chrdkskills.tree.PerkTreeState;
import ru.cherdakmd.chrdkskills.tree.TreeDomain;

import java.util.Locale;

/**
 * Команда {@code /perktree}. Только разбор аргументов и тексты: решения
 * принимает {@link PerkTreeService}, тот же, что и у кликов в
 * {@link PerkTreeGui}. Сброс двухшаговый — {@code reset} выдаёт токен,
 * {@code confirm <токен>} применяет его.
 */
final class PerkTreeCommandHandler implements CommandExecutor {

    private final ClassesPlugin plugin;
    private final PerkTreeService trees;
    private final PerkTreeGui gui;

    PerkTreeCommandHandler(ClassesPlugin plugin, PerkTreeService trees, PerkTreeGui gui) {
        this.plugin = plugin;
        this.trees = trees;
        this.gui = gui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (!player.hasPermission("chrdkclasses.use")) {
            plugin.messages().send(player, "no-permission");
            return true;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(player, "disabled");
            return true;
        }
        String sub = args.length == 0 ? "open" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "open" -> {
                TreeDomain domain = args.length > 1 ? domain(args[1]) : primary(player);
                if (domain == null || PerkTreeRegistry.of(domain).isEmpty()) {
                    plugin.messages().send(player, "perktree-unknown-domain");
                    return true;
                }
                gui.open(player, domain);
            }
            case "info" -> {
                TreeDomain domain = args.length > 1 ? domain(args[1]) : primary(player);
                if (domain == null || PerkTreeRegistry.of(domain).isEmpty()) {
                    plugin.messages().send(player, "perktree-unknown-domain");
                    return true;
                }
                PerkTreeState state = trees.state(player.getUniqueId(), domain,
                        plugin.service().level(player.getUniqueId()));
                if (state == null) {
                    plugin.messages().send(player, "perktree-no-tree");
                    return true;
                }
                plugin.messages().send(player, "perktree-info",
                        "domain", domain.id(), "level", String.valueOf(state.level()),
                        "powder", String.valueOf(state.powder()),
                        "tokens", String.valueOf(state.tokensAvailable()),
                        "ability", state.selectedAbility() == null ? "—" : state.selectedAbility());
            }
            case "unlock", "select" -> {
                if (args.length < 3) {
                    plugin.messages().send(player, "perktree-usage");
                    return true;
                }
                TreeDomain domain = domain(args[1]);
                if (domain == null || PerkTreeRegistry.of(domain).isEmpty()) {
                    plugin.messages().send(player, "perktree-unknown-domain");
                    return true;
                }
                int classLevel = plugin.service().level(player.getUniqueId());
                PerkTreeService.Result result = sub.equals("select")
                        ? trees.select(player.getUniqueId(), domain, args[2], classLevel)
                        : trees.unlock(player.getUniqueId(), domain, args[2], classLevel);
                if (result.ok()) {
                    plugin.messages().send(player,
                            sub.equals("select") ? "perktree-selected" : "perktree-unlocked",
                            "node", args[2]);
                } else {
                    plugin.messages().send(player, "perktree-locked", "reason", result.reason());
                }
            }
            case "reset" -> {
                TreeDomain domain = args.length > 1 ? domain(args[1]) : primary(player);
                if (domain == null || PerkTreeRegistry.of(domain).isEmpty()) {
                    plugin.messages().send(player, "perktree-unknown-domain");
                    return true;
                }
                PerkTreeService.Result request = trees.requestReset(player.getUniqueId(), domain,
                        plugin.service().level(player.getUniqueId()), System.currentTimeMillis());
                if (request.ok()) {
                    plugin.messages().send(player, "perktree-reset-pending",
                            "token", request.reason());
                } else {
                    plugin.messages().send(player, "perktree-reset-failed");
                }
            }
            case "confirm" -> {
                if (args.length < 2) {
                    plugin.messages().send(player, "perktree-usage");
                    return true;
                }
                PerkTreeService.Result result = trees.confirmReset(player.getUniqueId(),
                        args[1], System.currentTimeMillis());
                plugin.messages().send(player,
                        result.ok() ? "perktree-reset-done" : "perktree-reset-failed");
            }
            default -> plugin.messages().send(player, "perktree-usage");
        }
        return true;
    }

    private TreeDomain primary(Player player) {
        PlayerClass role = plugin.service().classOf(player.getUniqueId());
        return role == null ? null : ClassPerkTreeBridge.primaryDomain(role);
    }

    private static TreeDomain domain(String raw) {
        for (TreeDomain value : TreeDomain.values()) {
            if (value.id().equalsIgnoreCase(raw)) {
                return value;
            }
        }
        return null;
    }
}
