package ru.cherdakmd.chrdkartifacts;

import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Меняем только точно распознанные строки: чужой лор/оформление не пересобираем. */
final class ArtifactLore {
    private ArtifactLore() { }

    static List<Component> replaceKnown(List<Component> lore, Map<Component, Component> replacements) {
        if (lore == null) return null;
        // Эквивалентные деревья после сериализации Minecraft приводим к общей форме.
        Map<Component, Component> normalized = new LinkedHashMap<>();
        replacements.forEach((before, after) -> normalized.put(before.compact(), after));
        return lore.stream().map(line -> normalized.getOrDefault(line.compact(), line)).toList();
    }
}
