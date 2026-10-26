package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * Сколько личных пресетов оформления доступно на каждой ступени (1.4.187).
 *
 * <p>Пресет — сохранённый вид ника: что скрыто, в каком порядке метки и какой
 * цвет. Чем выше ступень, тем больше слотов: у игрока без ранга их два
 * («минимальный» и «полный»), у Покровителя — шесть. Значение по индексу
 * уровня, как у бонусов ранга; конфиг может дать свой список, но числа
 * ограничены разумным потолком.</p>
 *
 * <p>Класс чистый, без Bukkit-сервера: разбор и расчёт покрыты тестами.</p>
 */
public record LabelLimits(List<Integer> presetSlots) {

    /** Потолок слотов: больше шести кнопок в меню не помещается. */
    public static final int MAX_SLOTS = 6;

    /** Значения по умолчанию: 2 слота без ранга, дальше по одному на ступень. */
    private static final List<Integer> DEFAULTS = List.of(2, 2, 3, 4, 5, 6);

    public static final LabelLimits DEFAULT = new LabelLimits(DEFAULTS);

    public LabelLimits {
        List<Integer> safe = new ArrayList<>();
        List<Integer> source = presetSlots == null || presetSlots.isEmpty()
                ? DEFAULTS : presetSlots;
        for (int index = 0; index < RankLadder.MAX_LEVEL + 1; index++) {
            int value = index < source.size() ? source.get(index) : source.get(source.size() - 1);
            safe.add(Math.max(1, Math.min(MAX_SLOTS, value)));
        }
        presetSlots = List.copyOf(safe);
    }

    public static LabelLimits fromConfig(FileConfiguration config) {
        if (config == null || !config.isList("labels.presets.slots")) {
            return DEFAULT;
        }
        return new LabelLimits(config.getIntegerList("labels.presets.slots"));
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
