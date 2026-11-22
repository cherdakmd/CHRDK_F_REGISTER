package ru.cherdakmd.chrdkgenerator;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Голограф над ядром генератора (как у складов): тир, заполненность бункера
 * и состояние камеры. Текст — из ключа {@code hologram-text}; сущность
 * помечена PDC-тегом и переиспользуется между перезапусками.
 */
final class GeneratorHolograms {

    private final GeneratorPlugin plugin;
    private final NamespacedKey tagKey;

    GeneratorHolograms(GeneratorPlugin plugin) {
        this.plugin = plugin;
        this.tagKey = new NamespacedKey(plugin, "hologram");
    }

    /** Обновляет текст голографа генератора (спавнит сущность при необходимости). */
    void update(UUID owner, int slot) {
        GeneratorStore.Entry entry = plugin.store().entry(owner, slot);
        if (entry == null || !entry.installed()) {
            return;
        }
        World world = Bukkit.getWorld(entry.world());
        if (world == null || !world.isChunkLoaded(entry.x() >> 4, entry.z() >> 4)) {
            // Чанк выгружен: переиспользуемая сущность остаётся до загрузки.
            return;
        }
        String text = text(entry, plugin.messages().plain(plugin.stateOf(owner, slot)));
        TextDisplay display = find(world, entry);
        if (display == null) {
            Location anchor = anchor(entry);
            String tag = tag(entry);
            display = world.spawn(anchor, TextDisplay.class, entity -> {
                entity.setBillboard(Display.Billboard.CENTER);
                entity.setPersistent(true);
                entity.getPersistentDataContainer().set(tagKey, PersistentDataType.STRING, tag);
            });
        }
        if (!text.equals(display.getText())) {
            display.setText(text);
        }
    }

    /** Убирает голограф снятого генератора. */
    void remove(GeneratorStore.Entry entry) {
        if (entry == null) {
            return;
        }
        World world = Bukkit.getWorld(entry.world());
        if (world == null || !world.isChunkLoaded(entry.x() >> 4, entry.z() >> 4)) {
            return;
        }
        for (Entity entity : world.getNearbyEntities(anchor(entry), 0.5, 1.5, 0.5)) {
            if (entity instanceof TextDisplay display && tag(entry).equals(pdcTag(display))) {
                display.remove();
            }
        }
    }

    private TextDisplay find(World world, GeneratorStore.Entry entry) {
        for (Entity entity : world.getNearbyEntities(anchor(entry), 0.5, 1.5, 0.5)) {
            if (entity instanceof TextDisplay display && tag(entry).equals(pdcTag(display))) {
                return display;
            }
        }
        return null;
    }

    private String pdcTag(TextDisplay display) {
        return display.getPersistentDataContainer().get(tagKey, PersistentDataType.STRING);
    }

    private static Location anchor(GeneratorStore.Entry entry) {
        return new Location(Bukkit.getWorld(entry.world()),
                entry.x() + 0.5, entry.y() + 1.15, entry.z() + 0.5);
    }

    private static String tag(GeneratorStore.Entry entry) {
        return entry.owner() + ":" + entry.slot();
    }

    /** Текст голографа: строки ключа {@code hologram-text}, склеенные переносами. */
    private String text(GeneratorStore.Entry entry, String stateText) {
        entry = plugin.combinedBunkerEntry(entry);
        List<Component> lines = plugin.messages().getList("hologram-text",
                "tier", String.valueOf(entry.tier()),
                "tier_name", plugin.config().tier(entry.tier()).name(),
                "bunker", String.valueOf(entry.bunker()),
                "capacity", String.valueOf(GeneratorStats.capacityUnits(entry, plugin.config())),
                "fill", String.valueOf(GeneratorStats.fillPercent(entry, plugin.config())),
                "state", stateText);
        return lines.stream()
                .map(line -> LegacyComponentSerializer.legacySection().serialize(line))
                .collect(Collectors.joining("\n"));
    }
}
