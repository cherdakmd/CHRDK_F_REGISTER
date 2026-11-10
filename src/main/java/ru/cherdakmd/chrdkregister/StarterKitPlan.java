package ru.cherdakmd.chrdkregister;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Чистое описание одного случайного стартового комплекта: какие материалы,
 * какие ид артефактов и сколько алмазов. Ни одного вызова Bukkit — весь
 * выбор проверяется JUnit без сервера.
 *
 * <p>«Смешанные» артефакты: половина берётся из каталога CHRDK_Artifacts
 * (через {@code ChrdkArtifactsApi}), половина — ванильные ценные предметы.
 * Если аддона артефактов нет, ванильная часть закрывает весь набор.</p>
 */
public record StarterKitPlan(
        List<String> armor,
        List<String> tools,
        List<String> artifactIds,
        List<String> vanityItems,
        long bankDiamonds,
        int inventoryDiamonds) {

    public static final List<String> ARMOR_TIERS =
            List.of("LEATHER", "CHAINMAIL", "IRON", "GOLDEN", "DIAMOND");
    public static final List<String> ARMOR_SLOTS =
            List.of("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS");
    public static final List<String> TOOL_TIERS =
            List.of("WOODEN", "STONE", "IRON", "GOLDEN", "DIAMOND");
    public static final List<String> TOOL_KINDS =
            List.of("PICKAXE", "AXE", "SHOVEL", "SWORD");
    public static final List<String> VANILLA_TREASURES =
            List.of("EMERALD", "ENDER_PEARL", "GOLDEN_APPLE", "NAME_TAG",
                    "SPYGLASS", "HEART_OF_THE_SEA", "AMETHYST_SHARD",
                    "ECHO_SHARD", "NAUTILUS_SHELL");

    /** Сколько стаков предметов уйдёт в инвентарь (включая алмазы). */
    public int itemStacks() {
        return armor.size() + tools.size() + artifactIds.size() + vanityItems.size()
                + (inventoryDiamonds > 0 ? 1 : 0);
    }

    public static StarterKitPlan roll(StarterKitSettings settings, Random random,
                                      List<String> availableArtifacts) {
        StarterKitSettings safe = settings == null ? StarterKitSettings.defaults() : settings;
        Random dice = random == null ? new Random() : random;
        List<String> ids = availableArtifacts == null ? List.of() : List.copyOf(availableArtifacts);

        List<String> slots = new ArrayList<>(ARMOR_SLOTS);
        Collections.shuffle(slots, dice);
        List<String> armor = new ArrayList<>();
        for (int i = 0; i < Math.min(safe.armorPieces(), slots.size()); i++) {
            armor.add(pick(ARMOR_TIERS, dice) + "_" + slots.get(i));
        }

        List<String> kinds = new ArrayList<>(TOOL_KINDS);
        Collections.shuffle(kinds, dice);
        List<String> tools = new ArrayList<>();
        for (int i = 0; i < Math.min(safe.tools(), kinds.size()); i++) {
            tools.add(pick(TOOL_TIERS, dice) + "_" + kinds.get(i));
        }

        // Ровно половина — настоящие артефакты, остальное — ванильные находки.
        int real = Math.min(safe.artifacts() / 2, ids.size());
        int vanilla = Math.min(safe.artifacts() - real, VANILLA_TREASURES.size());
        List<String> pool = new ArrayList<>(ids);
        Collections.shuffle(pool, dice);
        List<String> artifacts = new ArrayList<>(pool.subList(0, real));
        List<String> treasures = new ArrayList<>(VANILLA_TREASURES);
        Collections.shuffle(treasures, dice);
        List<String> vanity = new ArrayList<>(treasures.subList(0, vanilla));

        return new StarterKitPlan(List.copyOf(armor), List.copyOf(tools),
                List.copyOf(artifacts), List.copyOf(vanity),
                safe.bankDiamonds(), safe.inventoryDiamonds());
    }

    private static String pick(List<String> source, Random dice) {
        return source.get(dice.nextInt(source.size()));
    }
}
