package ru.cherdakmd.chrdkregister;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code /wiki [страница]} — кликабельная ссылка на вики игрока.
 *
 * <p>Сайт нельзя открыть из игры напрямую, поэтому команда печатает адрес
 * тегом {@code click:open_url}: клик открывает браузер, вторая строка даёт
 * копирование адреса — на случай, если браузер по умолчанию не настроен.</p>
 *
 * <p>Адрес берётся из конфига при каждом вызове, а не запоминается при
 * старте: после {@code /pass reload} владелец может сменить домен без
 * перезапуска сервера. Консоль получает простой текст без кликов.</p>
 */
public final class WikiCommandHandler implements CommandExecutor, TabCompleter {

    private final Supplier<WikiSettings> settings;
    private volatile Messages messages;

    public WikiCommandHandler(Supplier<WikiSettings> settings, Messages messages) {
        this.settings = settings;
        this.messages = messages;
    }

    /** Новые тексты после {@code /pass reload}: команду не пересоздаём. */
    public void setMessages(Messages messages) {
        if (messages != null) {
            this.messages = messages;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        WikiSettings wiki = settings.get();
        if (wiki == null || !wiki.usable()) {
            messages.send(sender, "wiki.disabled");
            return true;
        }
        if (args.length > 1) {
            messages.send(sender, "wiki.usage", "label", label);
            return true;
        }
        String slug = args.length == 1 ? args[0].trim().toLowerCase(Locale.ROOT) : "";
        if (!slug.isEmpty() && (!WikiSettings.validSlug(slug) || RetiredAddons.source(slug))) {
            messages.send(sender, "wiki.unknown", "page", slug,
                    "pages", String.join(", ", wiki.pages()));
            return true;
        }
        String url = wiki.pageUrl(slug);
        if (!(sender instanceof Player)) {
            // В консоли клик некуда нажать: печатаем голый адрес одной строкой.
            messages.send(sender, "wiki.console", "url", url);
            return true;
        }
        if (slug.isEmpty()) {
            messages.send(sender, "wiki.link", "url", url);
        } else {
            messages.send(sender, "wiki.page", "page", slug, "url", url);
        }
        messages.send(sender, "wiki.copy", "url", url);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        WikiSettings wiki = settings.get();
        if (wiki == null || !wiki.usable()) {
            return List.of();
        }
        String prefix = args[0].trim().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String page : wiki.pages()) {
            if (page.startsWith(prefix)) {
                out.add(page);
            }
        }
        return out;
    }
}
