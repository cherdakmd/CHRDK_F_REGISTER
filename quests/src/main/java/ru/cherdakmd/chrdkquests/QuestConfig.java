package ru.cherdakmd.chrdkquests;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Разбор {@code config.yml} квестов (1.4.161).
 *
 * <p>Всё, что нельзя разобрать, пропускается с предупреждением в лог: опечатка
 * в материале не должна ронять плагин и не должна тихо превращать задание в
 * невыполнимое. Задания с пустыми целями или без награды не попадают в мир —
 * иначе игрок видел бы в меню то, что невозможно закрыть.</p>
 */
public final class QuestConfig {

    /** Сколько заданий можно сдать за сутки: потолок, чтобы квесты не стали фармом. */
    public static final int MAX_CLAIMS_PER_DAY = 100;
    /** Потолок числа фракций. */
    public static final int MAX_FACTIONS = 8;
    /** Потолок числа персонажей. */
    public static final int MAX_NPCS = 16;
    /** Потолок числа заданий во всём конфиге. */
    public static final int MAX_QUESTS = 256;

    private final boolean enabled;
    private final String locale;
    private final boolean actionbar;
    private final boolean factionPrefix;
    private final int prefixFromRank;
    private final int claimsPerDay;
    private final Map<String, QuestFaction> factions;
    private final Map<String, QuestNpc> npcs;
    private final Map<String, Quest> quests;

    private QuestConfig(boolean enabled, String locale, boolean actionbar, boolean factionPrefix,
                        int prefixFromRank, int claimsPerDay, Map<String, QuestFaction> factions,
                        Map<String, QuestNpc> npcs, Map<String, Quest> quests) {
        this.enabled = enabled;
        this.locale = locale;
        this.actionbar = actionbar;
        this.factionPrefix = factionPrefix;
        this.prefixFromRank = prefixFromRank;
        this.claimsPerDay = claimsPerDay;
        this.factions = Collections.unmodifiableMap(new LinkedHashMap<>(factions));
        this.npcs = Collections.unmodifiableMap(new LinkedHashMap<>(npcs));
        this.quests = Collections.unmodifiableMap(new LinkedHashMap<>(quests));
    }

    /** Пустой конфиг: плагин работает, но заданий нет. */
    public static QuestConfig defaults() {
        return new QuestConfig(true, "ru", true, true, 1, 10, Map.of(), Map.of(), Map.of());
    }

    /** Лестница рангов по умолчанию — та же, что в ресурсном config.yml. */
    public static TreeMap<Integer, String> defaultRanks() {
        TreeMap<Integer, String> ladder = new TreeMap<>(QuestFaction.DEFAULT_RANKS);
        return ladder;
    }

    /** Разбирает конфиг; {@code null} допустим — тогда действуют умолчания. */
    public static QuestConfig parse(FileConfiguration config, Logger logger) {
        FileConfiguration source = config == null ? new YamlConfiguration() : config;

        boolean enabled = source.getBoolean("general.enabled", true);
        String locale = source.getString("general.locale", "ru");
        locale = locale == null || locale.isBlank() ? "ru" : locale.trim().toLowerCase(Locale.ROOT);
        boolean actionbar = source.getBoolean("general.actionbar", true);
        boolean factionPrefix = source.getBoolean("general.faction-prefix", true);
        int prefixFromRank = clamp(source, "general.prefix-from-rank", 1, 1, 10, logger);
        int claimsPerDay = clamp(source, "general.claims-per-day", 10,
                1, MAX_CLAIMS_PER_DAY, logger);

        Map<String, QuestFaction> factions = parseFactions(source, logger);
        Map<String, QuestNpc> npcs = new LinkedHashMap<>();
        Map<String, Quest> quests = new LinkedHashMap<>();
        parseNpcs(source, factions, npcs, quests, logger);
        if (enabled && quests.isEmpty() && logger != null) {
            logger.warning("[CHRDK_Quests] В config.yml нет ни одного рабочего задания — "
                    + "меню будет пустым. Проверьте секцию npcs.");
        }
        return new QuestConfig(enabled, locale, actionbar, factionPrefix, prefixFromRank,
                claimsPerDay, factions, npcs, quests);
    }

    /* -------------------------------- фракции -------------------------------- */

    private static Map<String, QuestFaction> parseFactions(FileConfiguration config, Logger logger) {
        Map<String, QuestFaction> out = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("factions");
        if (section == null) {
            return out;
        }
        for (String key : section.getKeys(false)) {
            if (out.size() >= MAX_FACTIONS) {
                if (logger != null) {
                    logger.warning("[CHRDK_Quests] Фракций больше " + MAX_FACTIONS
                            + " — лишние пропущены.");
                }
                break;
            }
            String id = key.trim().toLowerCase(Locale.ROOT);
            Material icon = QuestMaterials.material(section.getString(key + ".icon", "SHIELD"));
            if (icon == null) {
                if (logger != null) {
                    logger.warning("[CHRDK_Quests] factions." + key
                            + ": неизвестная иконка — взята SHIELD.");
                }
                icon = Material.SHIELD;
            }
            TreeMap<Integer, String> ranks = parseRanks(section.getConfigurationSection(key + ".ranks"),
                    logger, "factions." + key + ".ranks");
            QuestFaction faction = new QuestFaction(id,
                    section.getString(key + ".name", key),
                    section.getString(key + ".description", ""),
                    icon,
                    section.getString(key + ".prefix", ""),
                    ranks);
            if (faction.id().isEmpty()) {
                continue;
            }
            out.put(faction.id(), faction);
        }
        return out;
    }

    private static TreeMap<Integer, String> parseRanks(ConfigurationSection section, Logger logger,
                                                       String path) {
        TreeMap<Integer, String> ranks = new TreeMap<>();
        if (section == null) {
            return defaultRanks();
        }
        for (String key : section.getKeys(false)) {
            int threshold;
            try {
                threshold = Integer.parseInt(key.trim());
            } catch (NumberFormatException error) {
                if (logger != null) {
                    logger.warning("[CHRDK_Quests] " + path + ": «" + key
                            + "» не порог репутации — строка пропущена.");
                }
                continue;
            }
            String rankId = section.getString(key);
            if (rankId == null || rankId.isBlank()) {
                continue;
            }
            if (ranks.size() >= QuestFaction.MAX_RANKS) {
                break;
            }
            ranks.put(Math.clamp(threshold, 0, QuestFaction.MAX_REPUTATION),
                    rankId.trim().toLowerCase(Locale.ROOT));
        }
        return ranks.isEmpty() ? defaultRanks() : ranks;
    }

    /* -------------------------------- персонажи ------------------------------- */

    private static void parseNpcs(FileConfiguration config, Map<String, QuestFaction> factions,
                                  Map<String, QuestNpc> npcs, Map<String, Quest> quests,
                                  Logger logger) {
        ConfigurationSection section = config.getConfigurationSection("npcs");
        if (section == null) {
            return;
        }
        Set<String> usedIds = new LinkedHashSet<>();
        for (String key : section.getKeys(false)) {
            if (npcs.size() >= MAX_NPCS) {
                if (logger != null) {
                    logger.warning("[CHRDK_Quests] Персонажей больше " + MAX_NPCS
                            + " — лишние пропущены.");
                }
                break;
            }
            String id = key.trim().toLowerCase(Locale.ROOT);
            Material icon = QuestMaterials.material(section.getString(key + ".icon", "PAPER"));
            if (icon == null) {
                if (logger != null) {
                    logger.warning("[CHRDK_Quests] npcs." + key
                            + ": неизвестная иконка — взята PAPER.");
                }
                icon = Material.PAPER;
            }
            String factionId = section.getString(key + ".faction", "");
            factionId = factionId == null ? "" : factionId.trim().toLowerCase(Locale.ROOT);
            if (!factionId.isEmpty() && !factions.containsKey(factionId)) {
                if (logger != null) {
                    logger.warning("[CHRDK_Quests] npcs." + key + ": неизвестная фракция «"
                            + factionId + "» — персонаж стал нейтральным.");
                }
                factionId = "";
            }
            List<Quest> chain = new ArrayList<>();
            for (Map<?, ?> row : section.getMapList(key + ".quests")) {
                if (quests.size() >= MAX_QUESTS) {
                    break;
                }
                Quest quest = parseQuest(row, id, logger, "npcs." + key + ".quests");
                if (quest == null) {
                    continue;
                }
                if (!usedIds.add(quest.id())) {
                    if (logger != null) {
                        logger.warning("[CHRDK_Quests] Задание «" + quest.id()
                                + "» встречается больше одного раза — повтор пропущен.");
                    }
                    continue;
                }
                chain.add(quest);
                quests.put(quest.id(), quest);
            }
            if (chain.isEmpty()) {
                if (logger != null) {
                    logger.warning("[CHRDK_Quests] npcs." + key
                            + ": нет рабочих заданий — персонаж пропущен.");
                }
                continue;
            }
            QuestNpc npc = new QuestNpc(id, section.getString(key + ".name", key),
                    section.getString(key + ".description", ""), icon, factionId, chain);
            npcs.put(npc.id(), npc);
        }
    }

    private static Quest parseQuest(Map<?, ?> row, String npcId, Logger logger, String path) {
        if (row == null) {
            return null;
        }
        String id = string(row.get("id"));
        if (id.isBlank()) {
            if (logger != null) {
                logger.warning("[CHRDK_Quests] " + path + ": задание без id — пропущено.");
            }
            return null;
        }
        QuestType type = QuestType.parse(string(row.get("type")));
        if (type == null) {
            if (logger != null) {
                logger.warning("[CHRDK_Quests] " + path + "." + id + ": неизвестный вид «"
                        + string(row.get("type")) + "» — пропущено (нужны KILL, MINE, GATHER).");
            }
            return null;
        }
        List<String> targetNames = strings(row.get("targets"));
        List<EntityType> entities = new ArrayList<>();
        List<Material> materials = new ArrayList<>();
        for (String name : targetNames) {
            if (type == QuestType.KILL) {
                EntityType entity = QuestMaterials.entity(name);
                if (entity == null) {
                    warnTarget(logger, path, id, name);
                    continue;
                }
                entities.add(entity);
            } else {
                Material material = QuestMaterials.material(name);
                if (!QuestMaterials.isItem(material)) {
                    warnTarget(logger, path, id, name);
                    continue;
                }
                materials.add(material);
            }
        }
        List<QuestReward.Item> items = new ArrayList<>();
        Object rawItems = row.get("items");
        if (rawItems instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Material material = QuestMaterials.material(String.valueOf(entry.getKey()));
                if (!QuestMaterials.isItem(material)) {
                    if (logger != null) {
                        logger.warning("[CHRDK_Quests] " + path + "." + id + ".items: неизвестный "
                                + "материал «" + entry.getKey() + "» — пропущен.");
                    }
                    continue;
                }
                items.add(new QuestReward.Item(material, (int) number(entry.getValue(), 1)));
            }
        }
        QuestReward reward = new QuestReward((int) number(row.get("xp"), 0),
                (int) number(row.get("reputation"), 0), items);
        Quest quest = new Quest(id, npcId, string(row.get("name")), string(row.get("description")),
                type, entities, materials, (int) number(row.get("amount"), 1), reward,
                (int) number(row.get("min-rank"), 0));
        if (!quest.usable()) {
            if (logger != null) {
                logger.warning("[CHRDK_Quests] " + path + "." + id
                        + ": нет целей или награды — задание пропущено.");
            }
            return null;
        }
        return quest;
    }

    private static void warnTarget(Logger logger, String path, String questId, String name) {
        if (logger != null) {
            logger.warning("[CHRDK_Quests] " + path + "." + questId
                    + ": неизвестная цель «" + name + "» — пропущена.");
        }
    }

    /* -------------------------------- помощники ------------------------------- */

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private static List<String> strings(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object element : list) {
                if (element != null && !element.toString().isBlank()) {
                    out.add(element.toString());
                }
            }
            return out;
        }
        if (raw instanceof String text && !text.isBlank()) {
            return List.of(text);
        }
        return List.of();
    }

    private static long number(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static int clamp(FileConfiguration source, String path, int fallback,
                             int min, int max, Logger logger) {
        long raw = source.getLong(path, fallback);
        int value = (int) Math.clamp(raw, min, max);
        if (value != raw && logger != null) {
            logger.warning("[CHRDK_Quests] " + path + ": " + raw
                    + " вне границ " + min + ".." + max + " — взято " + value + ".");
        }
        return value;
    }

    /* --------------------------------- доступ --------------------------------- */

    public boolean enabled() {
        return enabled;
    }

    public String locale() {
        return locale;
    }

    public boolean actionbar() {
        return actionbar;
    }

    public boolean factionPrefix() {
        return factionPrefix;
    }

    /** С какого номера ранга показывать префикс фракции в чате. */
    public int prefixFromRank() {
        return prefixFromRank;
    }

    public int claimsPerDay() {
        return claimsPerDay;
    }

    public Map<String, QuestFaction> factions() {
        return factions;
    }

    public List<QuestFaction> factionList() {
        return List.copyOf(factions.values());
    }

    public QuestFaction faction(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return factions.get(id.trim().toLowerCase(Locale.ROOT));
    }

    public List<QuestNpc> npcList() {
        return List.copyOf(npcs.values());
    }

    public QuestNpc npc(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return npcs.get(id.trim().toLowerCase(Locale.ROOT));
    }

    /** Все задания в порядке конфига. */
    public List<Quest> allQuests() {
        return List.copyOf(quests.values());
    }

    public Quest quest(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return quests.get(id.trim().toLowerCase(Locale.ROOT));
    }

    public int totalQuests() {
        return quests.size();
    }

    /** Иды персонажей — для автодополнения. */
    public List<String> npcIds() {
        return List.copyOf(npcs.keySet());
    }

    /** Иды фракций — для автодополнения. */
    public List<String> factionIds() {
        return List.copyOf(factions.keySet());
    }
}
