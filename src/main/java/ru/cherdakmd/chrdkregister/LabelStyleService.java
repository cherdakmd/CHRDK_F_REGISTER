package ru.cherdakmd.chrdkregister;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Единый вид ника в чате и TAB: имя первым, затем выбранные метки (1.4.190).
 * Каждое место содержит конкретный источник; автоматической подмены нет.
 * Старый API аддонов проецируется на отдельную метку без переноса их префиксов
 * или обёрток на имя. Цвета меток и персональные настройки сохраняются.
 */
public final class LabelStyleService {

    /** Отрисовка одной метки: id провайдера, ник, готовая строка → строка. */
    @FunctionalInterface
    public interface TagRenderer {
        String apply(String id, String playerName, String decoratedName);
    }

    /** Откуда брать ступень ранга и порядок регистрации меток. */
    public interface RankLookup {
        /** Оформление ступени игрока; {@code null} — ранга нет. */
        Rank view(String playerName);

        /** Исторический порядок регистрации; назначения мест больше от него не зависят. */
        List<String> registrationOrder();
    }

    /** Что нужно от ступени для ника. */
    public record Rank(int level, String badge, String colorFormat, String name,
                       boolean showName) {
    }

    private static final String ANCHOR = "CHRDK_LABEL_NAME_ANCHOR_7BBA";
    private static final String SEPARATOR = " <dark_gray>·</dark_gray> ";
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Одна надпись; знак и название ранга вместе занимают одно место. */
    public record Decoration(LabelPreferences.Label source, String markup) { }

    /** Снимок для сборки и меню: назначения мест сохраняются при пропаже источника. */
    public record Selection(List<Decoration> shown, List<Decoration> overflow, int limit, List<Decoration> available) {
        public Selection(List<Decoration> shown, List<Decoration> overflow, int limit) {
            this(shown, overflow, limit, java.util.stream.Stream.concat(shown.stream(), overflow.stream()).toList());
        }

        public Selection {
            available = List.copyOf(available);
            shown = List.copyOf(shown);
            overflow = List.copyOf(overflow);
        }

        public boolean obtained(LabelPreferences.Label label) {
            return available.stream().anyMatch(entry -> entry.source() == label);
        }

        public boolean deferred(LabelPreferences.Label label) {
            return overflow.stream().anyMatch(entry -> entry.source() == label);
        }

        public boolean displayed(LabelPreferences.Label label) {
            return shown.stream().anyMatch(entry -> entry.source() == label);
        }
    }

    private volatile LabelLimits limits = LabelLimits.DEFAULT;
    private final TagRenderer tags;
    private final LabelPreferences preferences;
    private final RankLookup ranks;

    public LabelStyleService(TagRenderer tags, LabelPreferences preferences, RankLookup ranks) {
        this.tags = tags;
        this.preferences = preferences;
        this.ranks = ranks;
    }

    /* ------------------------------- ник целиком ------------------------------- */

    /**
     * Ник со всем оформлением, которое выбрал игрок.
     *
     * @param playerName  ник без разметки
     * @param base        ник после тега каталога (может уже содержать разметку)
     * @param customColor выбранный игроком цвет-разметка или {@code null}
     */
    public String decorate(String playerName, String base, String customColor) {
        Rank rank = ranks.view(playerName);
        return assemble(colorize(base, customColor, rank), selection(playerName, rank, customColor));
    }

    /** Клан в TAB — статус PvP, а не платное косметическое место. Чат не меняется. */
    public String decorateForTab(String playerName, String base, String customColor) {
        Rank rank=ranks.view(playerName);
        Selection selected=selection(playerName,rank,customColor);
        List<Decoration> shown=selected.shown().stream()
                .filter(entry -> entry.source()!=LabelPreferences.Label.GUILDS).toList();
        String name=assemble(colorize(base,customColor,rank),new Selection(shown,selected.overflow(),selected.limit()));
        String clan=project(tags.apply("guilds-tab",playerName,ANCHOR));
        return clan.isBlank()?name:name+SEPARATOR+"<reset>"+clan+"<reset>";
    }

    public void applyLimits(LabelLimits limits) {
        this.limits = limits == null ? LabelLimits.DEFAULT : limits;
    }

    public Selection selection(String playerName) {
        return selection(playerName, ranks.view(playerName), null);
    }

    private Selection selection(String playerName, Rank rank, String customColor) {
        String badge = rank == null || rank.badge() == null ? "" : rank.badge();
        String name = rank == null || !rank.showName() || rank.name() == null
                ? "" : rankName(rank, customColor);
        List<Decoration> available = select(playerName, defaultOrder(), badge, name,
                Set.of(), LabelLimits.MAX_LABELS).shown();
        int limit = limits.labelsFor(rank == null ? 0 : rank.level());
        List<Decoration> shown = new ArrayList<>();
        List<Decoration> locked = new ArrayList<>();
        List<String> assignments = assignments(playerName);
        for (int i = 0; i < assignments.size(); i++) {
            LabelPreferences.Label label = LabelPreferences.Label.byNameTagId(assignments.get(i));
            if (label == null || !preferences.visible(playerName, label)) continue;
            for (Decoration decoration : available) {
                if (decoration.source() == label) {
                    (i < limit ? shown : locked).add(decoration);
                    break;
                }
            }
        }
        return new Selection(shown, locked, limit, available);
    }

    /** Все восемь назначений, включая пустые и временно заблокированные. */
    public List<String> assignments(String playerName) {
        List<String> saved = preferences.slots(playerName);
        return saved != null ? saved
                : LabelPreferences.normalizeSlots(order(playerName).stream().map(LabelPreferences.Label::id).toList());
    }

    public enum AssignmentResult { APPLIED, LOCKED, UNAVAILABLE }

    /** Повторная проверка лимита и наличия — также для клика из устаревшего окна. */
    public AssignmentResult assign(String playerName, int index, LabelPreferences.Label label) {
        Selection current = selection(playerName);
        if (index < 0 || index >= current.limit()) return AssignmentResult.LOCKED;
        if (label != null && (label.group() != LabelPreferences.Group.NAME || !current.obtained(label))) {
            return AssignmentResult.UNAVAILABLE;
        }
        List<String> next = new ArrayList<>(assignments(playerName));
        if (label != null) {
            // Одна надпись — одно место: перенос, а не дублирование.
            for (int i = 0; i < next.size(); i++) if (next.get(i).equals(label.id())) next.set(i, "");
        }
        next.set(index, label == null ? "" : label.id());
        preferences.setSlots(playerName, next, label);
        return AssignmentResult.APPLIED;
    }

    /** Чистая сборка для тестов, без персонального лимита. */
    String compose(String playerName, String base, List<LabelPreferences.Label> order,
                   String badge, String rankName, Set<LabelPreferences.Label> hidden) {
        return assemble(base, select(playerName, order, badge, rankName, hidden, LabelLimits.MAX_LABELS));
    }

    String compose(String playerName, String base, List<LabelPreferences.Label> order,
                   String badge, String rankName) {
        return compose(playerName, base, order, badge, rankName, Set.of());
    }

    private static String assemble(String base, Selection selection) {
        StringBuilder result = new StringBuilder(base == null ? "" : base);
        for (Decoration entry : selection.shown()) {
            // Отдельный компонент не позволяет цвету/жирности метки красить ник или соседей.
            result.append(SEPARATOR).append("<reset>").append(entry.markup()).append("<reset>");
        }
        return result.toString();
    }

    Selection select(String playerName, List<LabelPreferences.Label> order,
                     String badge, String rankName, Set<LabelPreferences.Label> hidden, int limit) {
        List<Decoration> entries = new ArrayList<>();
        List<LabelPreferences.Label> effective = order == null || order.isEmpty()
                ? LabelPreferences.Label.nameTags() : order;
        Set<LabelPreferences.Label> seen = new LinkedHashSet<>();
        for (LabelPreferences.Label label : effective) {
            if (label == null || label.group() != LabelPreferences.Group.NAME || !seen.add(label)
                    || (hidden != null && hidden.contains(label))) {
                continue;
            }
            String markup;
            if (label == LabelPreferences.Label.RANK) {
                markup = project(ANCHOR + " " + (badge == null ? "" : badge) + " "
                        + (rankName == null ? "" : rankName));
            } else {
                markup = project(tags == null ? null : tags.apply(label.id(), playerName, ANCHOR));
            }
            if (!markup.isBlank()) entries.add(new Decoration(label, markup));
        }
        int safeLimit = Math.max(0, Math.min(LabelLimits.MAX_LABELS, limit));
        int split = Math.min(safeLimit, entries.size());
        return new Selection(entries.subList(0, split), entries.subList(split, entries.size()), safeLimit);
    }

    /**
     * Совместимость с prefix/suffix API: аддон получает якорь вместо готового
     * ника. Удаляем якорь из дерева компонентов и обрезаем только внешние
     * пробелы/разделители. Вложенные цвета, градиенты и форматирование остаются.
     * Провайдер, потерявший/продублировавший имя, не может испортить общую строку.
     */
    static String project(String decorated) {
        if (decorated == null) return "";
        Component component = Messages.format(decorated);
        String text = PLAIN.serialize(component);
        int anchor = text.indexOf(ANCHOR);
        if (anchor < 0 || text.indexOf(ANCHOR, anchor + ANCHOR.length()) >= 0) return "";
        // Градиент дробит даже якорь на отдельные компоненты: replaceText
        // не ищет поперёк соседних узлов, поэтому вырезаем диапазон дерева.
        component = Component.empty()
                .append(NicknameComponents.slice(component, 0, anchor))
                .append(NicknameComponents.slice(component, anchor + ANCHOR.length(), text.length()));
        text = PLAIN.serialize(component);
        int start = 0;
        int end = text.length();
        while (start < end && boundary(text.charAt(start))) start++;
        while (end > start && boundary(text.charAt(end - 1))) end--;
        if (start == end) return "";
        return MINI.serialize(NicknameComponents.slice(component, start, end).compact());
    }

    private static boolean boundary(char c) {
        return Character.isWhitespace(c) || c == '·' || c == '|' || c == '•';
    }

    /* -------------------------------- цвет ника -------------------------------- */

    /** Имя в выбранном цвете; без цвета — как решил ранг. */
    static String colorize(String base, String customColor, Rank rank) {
        String name = base == null ? "" : base;
        if (customColor != null && !customColor.isBlank()) {
            return customColor.replace("%name%", name);
        }
        if (rank != null && rank.colorFormat() != null && !rank.colorFormat().isBlank()) {
            return rank.colorFormat().replace("%name%", name);
        }
        return name;
    }

    /** Название ступени в её цвете; свой цвет игрока перекрашивает и его. */
    static String rankName(Rank rank, String customColor) {
        if (rank == null) {
            return "";
        }
        if (customColor != null && !customColor.isBlank()) {
            return customColor.replace("%name%", rank.name());
        }
        return rank.colorFormat() == null || rank.colorFormat().isBlank()
                ? rank.name()
                : rank.colorFormat().replace("%name%", rank.name());
    }

    /**
     * Разметка выбранного цвета, если он доступен игроку.
     *
     * <p>Недоступный цвет (ранг кончился, товар истёк) молча не применяется:
     * ник возвращается к цвету ступени, а не остаётся «чужим».</p>
     */
    public String colorFormat(String playerName, LabelPalette palette, int rankLevel,
                              Set<String> purchased) {
        String id = preferences.color(playerName);
        if (id.isEmpty() || palette == null || !palette.enabled()) {
            return "";
        }
        LabelPalette.Color color = palette.byId(id);
        if (!palette.allowed(color, rankLevel, purchased)) {
            return "";
        }
        return color.format();
    }

    /* --------------------------------- порядок --------------------------------- */

    /** Совместимый список источников для старых файлов и команд перемещения. */
    public List<LabelPreferences.Label> order(String playerName) {
        Set<LabelPreferences.Label> kept = new LinkedHashSet<>();
        List<String> explicit = preferences.slots(playerName);
        List<LabelPreferences.Label> source = explicit == null ? preferences.order(playerName)
                : explicit.stream().map(LabelPreferences.Label::byNameTagId).filter(java.util.Objects::nonNull).toList();
        for (LabelPreferences.Label label : source) {
            if (label != null && label.group() == LabelPreferences.Group.NAME) kept.add(label);
        }
        kept.addAll(defaultOrder());
        return List.copyOf(kept);
    }

    /** Стабильный приоритет, не зависящий от очередности запуска аддонов. */
    public List<LabelPreferences.Label> defaultOrder() {
        return List.of(LabelPreferences.Label.RANK, LabelPreferences.Label.CLASSES,
                LabelPreferences.Label.GUILDS,
                LabelPreferences.Label.TIERFORGE,
                LabelPreferences.Label.QUESTS, LabelPreferences.Label.ACHIEVEMENTS,
                LabelPreferences.Label.VK);
    }

    /**
     * Меняет метку местами с соседкой.
     *
     * @param direction {@code -1} — левее, {@code +1} — правее
     * @return {@code true} — порядок изменился
     */
    public boolean move(String playerName, LabelPreferences.Label label, int direction) {
        if (label == null || direction == 0) {
            return false;
        }
        List<String> current = new ArrayList<>(assignments(playerName));
        int index = current.indexOf(label.id());
        int target = index + (direction < 0 ? -1 : 1);
        int limit = selection(playerName).limit();
        if (index < 0 || index >= limit || target < 0 || target >= limit) return false;
        java.util.Collections.swap(current, index, target);
        preferences.setSlots(playerName, current);
        return true;
    }

    /** Сбросить порядок к виду по умолчанию. */
    public void resetOrder(String playerName) {
        preferences.setOrder(playerName, List.of());
    }
}
