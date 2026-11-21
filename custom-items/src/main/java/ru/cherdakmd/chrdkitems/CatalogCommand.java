package ru.cherdakmd.chrdkitems;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import java.util.Map;

final class CatalogCommand {
    private final Map<String, ItemCatalog.Definition> catalog;
    CatalogCommand(Map<String, ItemCatalog.Definition> catalog) { this.catalog = catalog; }

    boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdkcustomitems.use")) {
            sender.sendMessage(Component.text("Нет права на просмотр каталога."));
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("info")) {
            var item = catalog.get(args[1]);
            if (item == null) { sender.sendMessage(Component.text("Предмет в каталоге не найден.")); return true; }
            sender.sendMessage(Component.text(item.name() + " [" + item.id() + "]"));
            sender.sendMessage(Component.text(CatalogView.categoryName(item.category()) + " · основа: " + item.material().name()));
            item.description().forEach(line -> sender.sendMessage(Component.text(line)));
            sender.sendMessage(Component.text("Прототип: необычные свойства, крафт и использование расходников ещё не реализованы."));
            return true;
        }
        final CatalogView.Query query;
        try { query = CatalogView.parse(args); }
        catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("Использование: /customitems [all|weapon|armor|tool|consumable] [страница] или /customitems info <id>"));
            return true;
        }
        var page = CatalogView.search(catalog, query);
        if (page.number() > page.pages()) {
            sender.sendMessage(Component.text("Такой страницы нет. Доступно страниц: " + page.pages()));
            return true;
        }
        sender.sendMessage(Component.text("Каталог прототипов · " + page.number() + "/" + page.pages() + " · всего " + page.total()));
        if (page.items().isEmpty()) sender.sendMessage(Component.text("Каталог этой категории пуст."));
        for (var item : page.items()) sender.sendMessage(Component.text(
                item.name() + " [" + item.id() + "] — " + CatalogView.categoryName(item.category())));
        sender.sendMessage(Component.text("Подробнее: /customitems info <id>. Только просмотр, выдачи и крафта нет."));
        return true;
    }
}
