package ru.cherdakmd.chrdktierforge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Обновление известного блока без удаления строк только за похожий префикс/маркер. */
final class TierLore {
    private TierLore() { }

    static List<Component> refresh(List<Component> before, Set<String> tierNames,
                                   Component tierLine, Map<String, Component> replacements) {
        if (before == null) return null;
        var plain = PlainTextComponentSerializer.plainText();
        boolean recognized = before.stream().map(plain::serialize)
                .anyMatch(text -> tierNames.stream().anyMatch(name -> isTier(text, name)));
        if (!recognized) return before;
        List<Component> result = new ArrayList<>();
        Set<String> used = new HashSet<>();
        boolean tierAdded = false;
        for (Component line : before) {
            String text = plain.serialize(line);
            if (tierNames.stream().anyMatch(name -> isTier(text, name))) {
                if (!tierAdded) result.add(tierLine);
                tierAdded = true;
            } else if (replacements.containsKey(text)) {
                Component replacement = replacements.get(text);
                if (replacement != null && used.add(text)) result.add(replacement);
            } else {
                result.add(line);
            }
        }
        replacements.forEach((text, line) -> {
            if (line != null && used.add(text)) result.add(line);
        });
        return result;
    }

    private static boolean isTier(String text, String name) {
        return text.equals(name) || text.startsWith(name + " · ");
    }
}
