package ru.cherdakmd.chrdktierforge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Разбор компактной записи уровней улучшений для админской выдачи (1.4.96).
 *
 * <p>Формат: {@code edge:3,crit:2} — id улучшения и уровень. Используется в
 * {@code /tiersadmin give <ник> <материал> <тир> [кол-во] [улучшения] [кличка]},
 * чтобы выдать предмет с готовой ковкой, не собирая её вручную на наковальне.
 *
 * <p>Правила: регистр не важен, уровни зажимаются в {@code 1..maxLevel}, повтор
 * улучшения берёт максимум, {@code -} или пустая строка — «без улучшений».
 * Неизвестные id и нечисловые уровни попадают в {@code rejected} — команда
 * покажет их списком и ничего не выдаст, чтобы опечатка не создала «немой» предмет.
 *
 * <p>Чистая логика — покрыто {@link UpgradeSpecTest}.
 */
final class UpgradeSpec {

    /** Итог разбора: что применить и что не поняли. */
    record Result(Map<ForgeUpgrade.Def, Integer> upgrades, List<String> rejected) {
        boolean isEmpty() {
            return upgrades.isEmpty();
        }

        boolean hasRejected() {
            return !rejected.isEmpty();
        }
    }

    private UpgradeSpec() {
    }

    static Result parse(String spec) {
        Map<ForgeUpgrade.Def, Integer> upgrades = new LinkedHashMap<>();
        List<String> rejected = new ArrayList<>();
        if (spec == null || spec.isBlank() || "-".equals(spec.trim())) {
            return new Result(upgrades, rejected);
        }
        for (String raw : spec.split(",")) {
            String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            int colon = token.indexOf(':');
            if (colon <= 0 || colon == token.length() - 1) {
                rejected.add(token);
                continue;
            }
            String id = token.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String levelText = token.substring(colon + 1).trim();
            ForgeUpgrade.Def def = ForgeUpgrade.byId(id);
            if (def == null) {
                rejected.add(token);
                continue;
            }
            int level;
            try {
                level = Integer.parseInt(levelText);
            } catch (NumberFormatException exception) {
                rejected.add(token);
                continue;
            }
            int clamped = Math.max(1, Math.min(def.maxLevel(), level));
            upgrades.merge(def, clamped, Math::max);
        }
        return new Result(upgrades, rejected);
    }

    /** Человекочитаемый список уровней для подтверждения: «Кромка III, Крит II». */
    static String describe(Map<ForgeUpgrade.Def, Integer> upgrades) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<ForgeUpgrade.Def, Integer> entry : upgrades.entrySet()) {
            parts.add(entry.getKey().displayName() + " " + ForgeUpgrade.roman(entry.getValue()));
        }
        return String.join(", ", parts);
    }
}
