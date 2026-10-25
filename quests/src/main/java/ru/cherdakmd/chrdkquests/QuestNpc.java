package ru.cherdakmd.chrdkquests;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Персонаж с цепочкой заданий (1.4.161).
 *
 * <p>Цепочка — это порядок: первое задание открыто сразу, следующее — только
 * после сдачи предыдущего. Так NPC рассказывает историю по шагам, а не
 * выкладывает весь список разом.</p>
 *
 * @param id          ид персонажа ({@code elder})
 * @param name        имя в чате и меню
 * @param description короткое пояснение
 * @param icon        иконка в меню
 * @param factionId   фракция, без которой задания недоступны; пусто — нейтрал
 * @param quests      задания в порядке цепочки
 */
public record QuestNpc(String id, String name, String description, Material icon,
                       String factionId, List<Quest> quests) {

    /** Потолок длины цепочки. */
    public static final int MAX_QUESTS = 20;

    public QuestNpc {
        id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        name = name == null || name.isBlank() ? id : name.trim();
        description = description == null ? "" : description.trim();
        icon = icon == null || icon == Material.AIR ? Material.PAPER : icon;
        factionId = factionId == null ? "" : factionId.trim().toLowerCase(Locale.ROOT);
        quests = copy(id, quests);
    }

    private static List<Quest> copy(String npcId, List<Quest> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<Quest> out = new ArrayList<>();
        for (Quest quest : source) {
            if (quest == null || !quest.usable()) {
                continue;
            }
            if (out.size() >= MAX_QUESTS) {
                break;
            }
            // Задание помнит своего персонажа: он нужен для проверки цепочки и
            // для награды, даже если объект пришёл из другого места.
            out.add(quest.belongsTo(npcId) ? quest : new Quest(quest.id(), npcId,
                    quest.name(), quest.description(), quest.type(), quest.entities(),
                    quest.materials(), quest.amount(), quest.reward(), quest.minRank()));
        }
        return List.copyOf(out);
    }

    /** Нейтральный персонаж: его задания не требуют фракции. */
    public boolean neutral() {
        return factionId.isEmpty();
    }

    /** Задание по иду или {@code null}. */
    public Quest quest(String questId) {
        if (questId == null || questId.isBlank()) {
            return null;
        }
        String key = questId.trim().toLowerCase(Locale.ROOT);
        for (Quest quest : quests) {
            if (quest.id().equals(key)) {
                return quest;
            }
        }
        return null;
    }

    /** Номер задания в цепочке (0 — первое) или {@code -1}. */
    public int indexOf(String questId) {
        if (questId == null) {
            return -1;
        }
        String key = questId.trim().toLowerCase(Locale.ROOT);
        for (int index = 0; index < quests.size(); index++) {
            if (quests.get(index).id().equals(key)) {
                return index;
            }
        }
        return -1;
    }
}
