package ru.cherdakmd.chrdkregister;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /labels} — что игрок показывает в своём нике (1.4.183).
 *
 * <p>Таб-лист к этому моменту оброс надписями: название донатного ранга, знак
 * ступени, звание кладоискателя, метка класса, гильдии и стримера. Кому-то это
 * нравится, кому-то мешает — и раньше выключить это было нельзя вообще.
 * Теперь три переключателя: {@code rank} (ранг и его знак), {@code badges}
 * (метки аддонов) и {@code header} (шапка и подвал списка, их видит только сам
 * игрок). {@code all}/{@code on}/{@code off} — всё сразу, {@code reset} —
 * вернуть как было.</p>
 *
 * <p>Переключение применяется сразу: таб-лист перерисовывается всем, потому что
 * имя в списке общее — его видят и другие. Привилегии, опыт и ранг при этом
 * остаются: это оформление, а не право.</p>
 */
public final class LabelsCommandHandler implements CommandExecutor, TabCompleter {

    private static final List<String> OPTIONS =
            List.of("rank", "badges", "header", "all", "on", "off", "reset", "status");

    private final RegisterPlugin plugin;
    private final LabelPreferences preferences;
    private Messages messages;

    public LabelsCommandHandler(RegisterPlugin plugin, LabelPreferences preferences) {
        this.plugin = plugin;
        this.preferences = preferences;
        this.messages = plugin.messages();
    }

    /** Обновляет тексты после {@code /pass reload}. */
    public void refreshMessages() {
        this.messages = plugin.messages();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.players-only");
            return true;
        }
        if (!player.hasPermission("chrdkregister.labels")) {
            messages.send(sender, "error.no-permission");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("status")
                || args[0].equalsIgnoreCase("menu")) {
            sendStatus(player);
            return true;
        }
        String option = args[0].toLowerCase(Locale.ROOT);
        switch (option) {
            case "rank", "ранг", "ранги" -> toggle(player, LabelPreferences.Category.RANK);
            case "badges", "звания", "метки", "badge" ->
                    toggle(player, LabelPreferences.Category.BADGES);
            case "header", "шапка", "таб" -> toggle(player, LabelPreferences.Category.HEADER);
            case "all", "всё", "все" -> setAll(player, !preferences.anyHidden(player.getName()));
            case "on", "вкл", "включить" -> setAll(player, true);
            case "off", "выкл", "выключить" -> setAll(player, false);
            case "reset", "сброс" -> {
                preferences.reset(player.getName());
                messages.send(player, "labels.reset");
                plugin.refreshTab();
            }
            default -> messages.send(player, "labels.usage", "label", label);
        }
        return true;
    }

    private void toggle(Player player, LabelPreferences.Category category) {
        boolean visible = preferences.toggle(player.getName(), category);
        // Ключи перечислены явно: так MessagesTest видит их в коде и не считает
        // «мёртвыми», а опечатка в имени категории не превращается в пустой чат.
        switch (category) {
            case RANK -> messages.send(player, visible ? "labels.rank-on" : "labels.rank-off");
            case BADGES -> messages.send(player, visible ? "labels.badges-on" : "labels.badges-off");
            case HEADER -> messages.send(player, visible ? "labels.header-on" : "labels.header-off");
        }
        plugin.refreshTab();
    }

    private void setAll(Player player, boolean visible) {
        preferences.setAll(player.getName(), visible);
        messages.send(player, visible ? "labels.all-on" : "labels.all-off");
        plugin.refreshTab();
    }

    /** Текущее состояние всех переключателей одной страницей. */
    private void sendStatus(Player player) {
        messages.send(player, "labels.header");
        for (LabelPreferences.Category category : LabelPreferences.Category.values()) {
            messages.send(player, "labels.status-line",
                    "name", nameOf(category),
                    "value", valueOf(player, category));
        }
        messages.send(player, "labels.hint");
    }

    /** Человеческое название категории в её оформлении. */
    private Messages.Trusted nameOf(LabelPreferences.Category category) {
        return switch (category) {
            case RANK -> messages.mini("labels.name-rank");
            case BADGES -> messages.mini("labels.name-badges");
            case HEADER -> messages.mini("labels.name-header");
        };
    }

    /** «видно» / «скрыто» в оформлении из {@code messages.yml}. */
    private Messages.Trusted valueOf(Player player, LabelPreferences.Category category) {
        return preferences.visible(player.getName(), category)
                ? messages.mini("labels.value-on")
                : messages.mini("labels.value-off");
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : OPTIONS) {
            if (option.startsWith(prefix)) {
                out.add(option);
            }
        }
        return out;
    }
}
