package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/** Независимые лимиты пресетов и отображаемых надписей по ступеням 0–V. */
public record LabelLimits(List<Integer> presetSlots, List<Integer> displayedLabels) {

    /** Потолок слотов: больше шести кнопок в меню не помещается. */
    public static final int MAX_SLOTS = 6;

    /** Значения по умолчанию: 2 слота без ранга, дальше по одному на ступень. */
    private static final List<Integer> DEFAULTS = List.of(2, 2, 3, 4, 5, 6);

    public static final int MAX_LABELS = 8;
    private static final List<Integer> DEFAULT_LABELS = List.of(2, 2, 2, 3, 4, 5);

    public LabelLimits(List<Integer> presetSlots) {
        this(presetSlots, DEFAULT_LABELS);
    }

    public static final LabelLimits DEFAULT = new LabelLimits(DEFAULTS);

    public LabelLimits {
        presetSlots = normalize(presetSlots, DEFAULTS, MAX_SLOTS);
        displayedLabels = normalize(displayedLabels, DEFAULT_LABELS, MAX_LABELS);
    }

    private static List<Integer> normalize(List<Integer> source, List<Integer> defaults, int max) {
        List<Integer> values = source == null || source.isEmpty() ? defaults : source;
        List<Integer> safe = new ArrayList<>();
        for (int index = 0; index <= RankLadder.MAX_LEVEL; index++) {
            Integer value = values.get(Math.min(index, values.size() - 1));
            safe.add(Math.max(1, Math.min(max, value == null ? defaults.get(index) : value)));
        }
        return List.copyOf(safe);
    }

    public static LabelLimits fromConfig(FileConfiguration config) {
        return config == null ? DEFAULT : new LabelLimits(
                config.getIntegerList("labels.presets.slots"),
                config.getIntegerList("labels.display.slots"));
    }

    /** Шапка TAB не считается; знак и название ранга вместе — одна надпись. */
    public int labelsFor(int level) {
        return displayedLabels.get(Math.max(0, Math.min(displayedLabels.size() - 1, level)));
    }

    /** Сколько слотов у этой ступени; уровень вне лестницы считается крайним. */
    public int slotsFor(int level) {
        int index = Math.max(0, Math.min(presetSlots.size() - 1, level));
        return presetSlots.get(index);
    }

    /** Можно ли сохранить пресет в слот с учётом уже занятых. */
    public boolean canSave(int level, int usedSlots) {
        return usedSlots < slotsFor(level);
    }
}
