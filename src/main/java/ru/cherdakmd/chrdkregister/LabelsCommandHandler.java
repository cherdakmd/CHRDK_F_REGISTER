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
 * {@code /labels} — что игрок показывает в своём нике и табе (1.4.183, дробно с
 * 1.4.186).
 *
 * <p>Без аргумента открывается меню «Оформление ника»: каждая надпись — своя
 * плитка (ранг, титул кузни, звание кладоискателя, класс, гильдия, стример,
 * квесты, достижения, шапка таба). Текстовые ключи остались для тех, кто любит
 * команды и для логов: {@code /labels rank}, {@code /labels streams} — одна
 * надпись; {@code /labels badges} — все аддонские разом; {@code all},
 * {@code on}, {@code off}, {@code reset} — всё сразу; {@code status} —
 * состояние страницей в чат.</p>
 *
 * <p>Переключение применяется сразу: таб-лист перерисовывается всем, потому что
 * имя в списке общее — его видят и другие. Привилегии, опыт и ранг при этом
 * остаются: это оформление, а не право.</p>
 */
public final class LabelsCommandHandler implements CommandExecutor, TabCompleter {

    /** Текстовые ключи в порядке подсказки: сначала одиночные, потом группы. */
    private static final List<String> OPTIONS = List.of(
            "rank", "tierforge", "treasures", "classes", "guilds", "streams",
            "quests", "achievements", "header",
            "badges", "all", "on", "off", "reset", "status", "menu",
            "color", "order", "preset");

    private final RegisterPlugin plugin;
    private final LabelPreferences preferences;
    private final LabelsMenuService menu;
    private Messages messages;

    public LabelsCommandHandler(RegisterPlugin plugin, LabelPreferences preferences,
                                LabelsMenuService menu) {
        this.plugin = plugin;
        this.preferences = preferences;
        this.menu = menu;
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
        if (args.length == 0 || args[0].equalsIgnoreCase("menu")) {
            menu.open(player);
            return true;
        }
        String option = args[0].toLowerCase(Locale.ROOT);
        if (option.equals("status")) {
            sendStatus(player);
            return true;
        }
        if (option.equals("color") || option.equals("цвет")) {
            handleColor(player, args);
            return true;
        }
        if (option.equals("order") || option.equals("порядок")) {
            handleOrder(player, args);
            return true;
        }
        if (option.equals("preset") || option.equals("пресет")) {
            handlePreset(player, args, label);
            return true;
        }
        switch (option) {
            // «Всё» — переключатель: что-то скрыто → показать всё, иначе скрыть всё.
            case "all", "всё", "все" -> setAll(player, preferences.anyHidden(player.getName()));
            case "on", "вкл", "включить" -> setAll(player, true);
            case "off", "выкл", "выключить" -> setAll(player, false);
            case "reset", "сброс" -> {
                preferences.reset(player.getName());
                messages.send(player, "labels.reset");
                plugin.refreshTab();
            }
            case "badges", "метки", "звания" -> toggleBadges(player);
            default -> {
                LabelPreferences.Label single = singleOf(option);
                if (single == null) {
                    messages.send(player, "labels.usage", "label", label);
                } else {
                    toggle(player, single);
                }
            }
        }
        return true;
    }

    /** Русские псевдонимы одиночных надписей — чтобы не вспоминать английский ид. */
    static LabelPreferences.Label singleOf(String raw) {
        String key = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "ранг", "ранги" -> LabelPreferences.Label.RANK;
            case "кузня", "титул", "кузни" -> LabelPreferences.Label.TIERFORGE;
            case "клады", "кладоискатель" -> LabelPreferences.Label.TREASURES;
            case "класс", "классы" -> LabelPreferences.Label.CLASSES;
            case "гильдия", "гильдии" -> LabelPreferences.Label.GUILDS;
            case "стример", "эфир" -> LabelPreferences.Label.STREAMS;
            case "квесты", "квест" -> LabelPreferences.Label.QUESTS;
            case "достижения" -> LabelPreferences.Label.ACHIEVEMENTS;
            case "шапка", "таб" -> LabelPreferences.Label.HEADER;
            default -> LabelPreferences.Label.byId(key);
        };
    }

    /* ------------------------- цвет, порядок, пресеты ------------------------- */

    /** {@code /labels color} — экран палитры, {@code /labels color aqua} — выбрать. */
    private void handleColor(Player player, String[] args) {
        if (args.length < 2) {
            LabelsStyleMenuService menu = plugin.labelsStyleMenu();
            if (menu != null) {
                menu.openColors(player);
            }
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        if (id.equals("off") || id.equals("auto") || id.equals("сброс")) {
            preferences.setColor(player.getName(), "");
            messages.send(player, "labels.color-cleared");
            plugin.refreshTab();
            return;
        }
        LabelPalette palette = plugin.palette();
        LabelPalette.Color color = palette.enabled() ? palette.byId(id) : null;
        if (color == null) {
            messages.send(player, "labels.color-unknown", "id", id);
            return;
        }
        RegistrationStore store = plugin.store();
        int level = store == null ? 0
                : store.rankLevel(player.getName(), System.currentTimeMillis());
        if (!palette.allowed(color, level, plugin.purchasedProducts(player.getName()))) {
            messages.send(player,
                    LabelPalette.lockReason(color, plugin.purchasedProducts(player.getName()))
                            == LabelPalette.Lock.CATALOG
                            ? "labels.color-locked-catalog" : "labels.color-locked-rank",
                    "rank", String.valueOf(color.rank()),
                    "product", color.product());
            return;
        }
        preferences.setColor(player.getName(), color.id());
        messages.send(player, "labels.color-set",
                "name", Messages.trusted(color.name()));
        plugin.refreshTab();
    }

    /** {@code /labels order} — экран порядка, {@code /labels order guilds left} — сдвиг. */
    private void handleOrder(Player player, String[] args) {
        LabelsStyleMenuService menu = plugin.labelsStyleMenu();
        if (args.length < 2) {
            if (menu != null) {
                menu.openOrder(player);
            }
            return;
        }
        LabelPreferences.Label label = singleOf(args[1]);
        if (label == null || label.group() == LabelPreferences.Group.TAB) {
            messages.send(player, "labels.order-unknown", "id", args[1]);
            return;
        }
        String direction = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "left";
        int step = switch (direction) {
            case "right", "rightward", "правее", "вправо" -> 1;
            default -> -1;
        };
        LabelStyleService styles = plugin.chatService() == null
                ? null : plugin.chatService().labelStyles();
        if (styles == null || !styles.move(player.getName(), label, step)) {
            messages.send(player, "labels.order-edge");
            return;
        }
        messages.send(player, "labels.order-moved",
                "name", LabelsMenuService.nameOf(messages, label),
                "position", String.valueOf(styles.order(player.getName()).indexOf(label) + 1));
        plugin.refreshTab();
    }

    /** {@code /labels preset} — экран пресетов, дальше save/load/delete и номер. */
    private void handlePreset(Player player, String[] args, String label) {
        LabelsStyleMenuService menu = plugin.labelsStyleMenu();
        if (args.length < 2) {
            if (menu != null) {
                menu.openPresets(player);
            }
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list") || action.equals("список")) {
            sendPresetList(player);
            return;
        }
        int number = args.length > 2 ? parseNumber(args[2]) : 0;
        if (number <= 0) {
            messages.send(player, "labels.preset-usage", "label", label);
            return;
        }
        RegistrationStore store = plugin.store();
        int level = store == null ? 0
                : store.rankLevel(player.getName(), System.currentTimeMillis());
        int slots = plugin.labelLimits().slotsFor(level);
        if (number > slots) {
            messages.send(player, "labels.preset-no-slot",
                    "number", String.valueOf(number), "slots", String.valueOf(slots));
            return;
        }
        String slot = "p" + number;
        switch (action) {
            case "save", "сохранить" -> {
                preferences.savePreset(player.getName(), slot);
                messages.send(player, "labels.preset-saved", "number", String.valueOf(number));
            }
            case "delete", "удалить" -> {
                if (preferences.deletePreset(player.getName(), slot)) {
                    messages.send(player, "labels.preset-deleted", "number", String.valueOf(number));
                } else {
                    messages.send(player, "labels.preset-missing", "number", String.valueOf(number));
                }
            }
            default -> {
                LabelPreferences.Snapshot snapshot = preferences.presets(player.getName()).get(slot);
                if (snapshot == null) {
                    messages.send(player, "labels.preset-missing", "number", String.valueOf(number));
                    return;
                }
                preferences.apply(player.getName(), snapshot);
                messages.send(player, "labels.preset-applied", "number", String.valueOf(number));
                plugin.refreshTab();
            }
        }
    }

    /** Страница со списком сохранённых видов. */
    private void sendPresetList(Player player) {
        messages.send(player, "labels.preset-list-header");
        var presets = preferences.presets(player.getName());
        if (presets.isEmpty()) {
            messages.send(player, "labels.preset-list-empty");
        } else {
            presets.forEach((slot, snapshot) -> messages.send(player, "labels.preset-list-line",
                    "slot", slot,
                    "hidden", String.valueOf(snapshot.hiddenCount()),
                    "color", snapshot.color().isEmpty()
                            ? messages.mini("labels.preset-no-color")
                            : Messages.trusted(snapshot.color())));
        }
        messages.send(player, "labels.preset-list-hint");
    }

    private static int parseNumber(String raw) {
        try {
            return Integer.parseInt(raw == null ? "" : raw.trim());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private void toggle(Player player, LabelPreferences.Label label) {
        boolean visible = preferences.toggle(player.getName(), label);
        announceToggle(player, label, visible);
        plugin.refreshTab();
    }

    /**
     * Сообщает о смене состояния одной надписи. Тем же текстом отвечает меню,
     * поэтому ключи живут в одном месте.
     */
    public void announceToggle(Player player, LabelPreferences.Label label, boolean visible) {
        messages.send(player, visible ? "labels.toggled-on" : "labels.toggled-off",
                "name", LabelsMenuService.nameOf(messages, label));
    }

    private void toggleBadges(Player player) {
        boolean visible = preferences.hiddenLabels(player.getName())
                .stream().anyMatch(value -> LabelPreferences.badgeLabels().contains(value));
        preferences.setGroup(player.getName(), LabelPreferences.badgeLabels(), visible);
        messages.send(player, visible ? "labels.group-on" : "labels.group-off");
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
        LabelStyleService.Selection selection = LabelsMenuService.selectionOf(plugin, player.getName());
        if (selection != null) player.sendMessage(LabelsMenuService.summary(messages, selection));
        for (LabelPreferences.Label label : LabelPreferences.Label.values()) {
            messages.send(player, "labels.status-line",
                    "name", LabelsMenuService.nameOf(messages, label),
                    "value", LabelsMenuService.stateOf(messages, preferences, player.getName(), label, selection));
        }
        messages.send(player, "labels.hint");
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
