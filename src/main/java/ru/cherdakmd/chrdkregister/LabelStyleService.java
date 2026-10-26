package ru.cherdakmd.chrdkregister;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Сборка ника по личным настройкам игрока (1.4.187): свой цвет, свой порядок
 * меток, ранг и тег каталога.
 *
 * <p>До этой версии порядок задавал порядок регистрации аддонов, а цвет — ранг
 * или купленный тег. Теперь игрок может поменять и то и другое, поэтому вся
 * сборка живёт в одном месте: сначала ник красится (свой цвет из палитры
 * главнее цвета ступени, но не главнее купленного тега каталога), потом вокруг
 * него раскладываются метки в выбранном порядке, а метка ранга идёт там, куда
 * её поставил игрок.</p>
 *
 * <p>Порядок — слева направо, как его видит игрок. Каждая метка приписывается
 * <em>перед</em> уже собранной строкой, поэтому обход идёт с конца списка: так
 * «первая» метка оказывается левее всех.</p>
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

        /** Порядок регистрации меток аддонов — из него берётся вид по умолчанию. */
        List<String> registrationOrder();
    }

    /** Что нужно от ступени для ника. */
    public record Rank(int level, String badge, String colorFormat, String name,
                       boolean showName) {
    }

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
        List<LabelPreferences.Label> order = order(playerName);
        boolean rankVisible = preferences.visible(playerName, LabelPreferences.Label.RANK);

        String colored = colorize(base, customColor, rank);
        String suffix = "";
        if (rank != null && rankVisible && rank.showName() && !rank.name().isBlank()) {
            suffix = " <dark_gray>·</dark_gray> " + rankName(rank, customColor);
        }
        String badge = rank == null || !rankVisible ? "" : rank.badge();
        return compose(playerName, colored, order, badge, suffix,
                Set.copyOf(preferences.hiddenLabels(playerName)));
    }

    /**
     * Чистая сборка строки — без Bukkit и без настроек. Покрыта тестами.
     *
     * @param order  метки слева направо (включая {@code RANK})
     * @param badge  знак ступени; пусто — не рисовать
     * @param suffix хвост с названием ступени; пусто — не рисовать
     * @param hidden скрытые надписи: их метки пропускаются
     */
    String compose(String playerName, String base, List<LabelPreferences.Label> order,
                   String badge, String suffix, Set<LabelPreferences.Label> hidden) {
        String out = base == null ? "" : base;
        List<LabelPreferences.Label> effective =
                order == null || order.isEmpty() ? LabelPreferences.Label.nameTags() : order;
        for (int index = effective.size() - 1; index >= 0; index--) {
            LabelPreferences.Label label = effective.get(index);
            if (label == null || (hidden != null && hidden.contains(label))) {
                continue;
            }
            if (label == LabelPreferences.Label.RANK) {
                String safeBadge = badge == null ? "" : badge.trim();
                if (!safeBadge.isEmpty()) {
                    out = safeBadge + " " + out;
                }
                continue;
            }
            String next = tags == null ? null : tags.apply(label.id(), playerName, out);
            if (next != null) {
                out = next;
            }
        }
        return out + (suffix == null ? "" : suffix);
    }

    /** Удобная перегрузка без скрытых надписей — для тестов и простых вызовов. */
    String compose(String playerName, String base, List<LabelPreferences.Label> order,
                   String badge, String suffix) {
        return compose(playerName, base, order, badge, suffix, Set.of());
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

    /**
     * Порядок меток слева направо: сохранённый игроком, а без него — прежний
     * («как регистрировались», перевёрнутый порядок плюс ранг последним, ближе
     * к нику).
     */
    public List<LabelPreferences.Label> order(String playerName) {
        List<LabelPreferences.Label> saved = preferences.order(playerName);
        if (saved.isEmpty()) {
            return defaultOrder();
        }
        Set<LabelPreferences.Label> kept = new LinkedHashSet<>();
        for (LabelPreferences.Label label : saved) {
            if (label != null && label.group() == LabelPreferences.Group.NAME) {
                kept.add(label);
            }
        }
        List<LabelPreferences.Label> out = new ArrayList<>(kept);
        // Недостающая метка (аддон новее настройки) встаёт перед рангом: ранг
        // остаётся ближе к нику, а новая метка не выпадает из ника молча.
        // Порядок недостающих — как в виде по умолчанию.
        List<LabelPreferences.Label> missing = new ArrayList<>();
        for (LabelPreferences.Label label : defaultOrder()) {
            if (!kept.contains(label)) {
                missing.add(label);
            }
        }
        int rankAt = out.indexOf(LabelPreferences.Label.RANK);
        if (rankAt < 0) {
            out.addAll(missing);
        } else {
            out.addAll(rankAt, missing);
        }
        return List.copyOf(out);
    }

    /** Порядок по умолчанию: как было до 1.4.187. */
    public List<LabelPreferences.Label> defaultOrder() {
        List<String> ids = ranks.registrationOrder();
        List<LabelPreferences.Label> out = new ArrayList<>();
        if (ids != null) {
            for (int index = ids.size() - 1; index >= 0; index--) {
                LabelPreferences.Label label =
                        LabelPreferences.Label.byNameTagId(ids.get(index));
                if (label != null && !out.contains(label)) {
                    out.add(label);
                }
            }
        }
        for (LabelPreferences.Label label : LabelPreferences.Label.nameTags()) {
            if (!out.contains(label)) {
                out.add(label);
            }
        }
        out.remove(LabelPreferences.Label.RANK);
        out.add(LabelPreferences.Label.RANK);
        return List.copyOf(out);
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
        List<LabelPreferences.Label> current = new ArrayList<>(order(playerName));
        int index = current.indexOf(label);
        int target = index + (direction < 0 ? -1 : 1);
        if (index < 0 || target < 0 || target >= current.size()) {
            return false;
        }
        current.remove(index);
        current.add(target, label);
        preferences.setOrder(playerName, current);
        return true;
    }

    /** Сбросить порядок к виду по умолчанию. */
    public void resetOrder(String playerName) {
        preferences.setOrder(playerName, List.of());
    }
}
