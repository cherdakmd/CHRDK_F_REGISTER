package ru.cherdakmd.chrdkregister;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;

/** Ссылка на официальный магазин: без выдачи товаров, оплаты или изменения аккаунта. */
public final class DonateCommandHandler implements CommandExecutor, TabCompleter {
    public static final String SHOP_URL = "https://chrdk.trademc.org/";
    public static final String PERMISSION = "chrdkregister.donate";
    private volatile Messages messages;

    public DonateCommandHandler(Messages messages) {
        this.messages = Objects.requireNonNull(messages);
    }

    /** Новый messages.yml после /pass reload, без пересоздания команды. */
    public void setMessages(Messages messages) {
        if (messages != null) this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages texts = messages;
        if (!sender.hasPermission(PERMISSION)) {
            texts.send(sender, "donate.no-permission");
            return true;
        }
        if (args.length != 0) {
            texts.send(sender, "donate.usage");
            return true;
        }
        if (sender instanceof Player) {
            texts.send(sender, "donate.link", "url", SHOP_URL);
            texts.send(sender, "donate.copy", "url", SHOP_URL);
        } else {
            texts.send(sender, "donate.console", "url", SHOP_URL);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
