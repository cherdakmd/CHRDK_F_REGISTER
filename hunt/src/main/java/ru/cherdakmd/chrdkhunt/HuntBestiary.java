package ru.cherdakmd.chrdkhunt;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Бестиарий охоты: журнал видов по семействам (1.4.159).
 *
 * <p>Вид — это моб из секции {@code mobs:} с рабочим правилом добычи. Семейство
 * делит виды на разделы: закрыть раздел можно, только убив каждого его моба
 * хотя бы раз. Награда выдаётся один раз и запоминается в данных игрока, поэтому
 * добавленный позже вид не даёт забрать её повторно.</p>
 *
 * <p>Класс чистый (Bukkit-типы вроде {@link EntityType} — обычные enum и их ключи
 * доступны без запущенного сервера), поэтому вся арифметика прогресса
 * проверяется юнит-тестами.</p>
 */
public final class HuntBestiary {

    /** Потолок видов в одном разделе: столько иконок помещается в меню. */
    public static final int MAX_ENTRIES_PER_SECTION = 35;
    /** Потолок разделов (по числу семейств в меню ключей). */
    public static final int MAX_SECTIONS = 14;
    /** Ид разовой награды «собран весь бестиарий» в данных игрока. */
    public static final String FULL_AWARD = "*";

    /** Награда за закрытый раздел: монеты, осколки и ключи семейства. */
    public record Reward(int coins, int shards, int keys) {

        /** Пустая награда — раздел закрывается без подарка. */
        public static final Reward NONE = new Reward(0, 0, 0);

        public Reward {
            coins = Math.clamp(coins, 0, 1_000_000);
            shards = Math.clamp(shards, 0, 10_000);
            keys = Math.clamp(keys, 0, 100);
        }

        /** Награда не выдаёт ничего: раздел считается, но подарка нет. */
        public boolean empty() {
            return coins <= 0 && shards <= 0 && keys <= 0;
        }
    }

    /**
     * Один вид в журнале.
     *
     * @param key      ключ вида в данных игрока (нижний регистр, {@code zombie})
     * @param type     тип сущности
     * @param familyId ид семейства-раздела
     * @param boss     босс: в меню помечается отдельно
     * @param icon     иконка в меню
     */
    public record Entry(String key, EntityType type, String familyId, boolean boss, Material icon) {

        public Entry {
            key = normalize(key);
            familyId = HuntFamily.normalize(familyId);
            icon = HuntMaterials.isItem(icon) ? icon : Material.PAPER;
        }

        /** Тег клиента для локализованного имени: {@code <lang:entity.minecraft.zombie>}. */
        public String translationTag() {
            return "<lang:" + translationKey(type) + ">";
        }
    }

    /**
     * Раздел бестиария — семейство мобов.
     *
     * @param id          ид семейства
     * @param displayName русское имя семейства
     * @param entries     виды раздела в порядке конфига
     * @param reward      награда за закрытие
     */
    public record Section(String id, String displayName, List<Entry> entries, Reward reward) {

        public Section {
            id = HuntFamily.normalize(id);
            displayName = HuntFamily.blankTo(displayName, id);
            entries = entries == null ? List.of() : List.copyOf(new ArrayList<>(entries));
            reward = reward == null ? Reward.NONE : reward;
        }

        /** Видов в разделе. */
        public int size() {
            return entries.size();
        }

        /** Есть ли в разделе хоть один вид: пустой раздел закрывать нечего. */
        public boolean usable() {
            return !entries.isEmpty();
        }

        /** Награда есть. */
        public boolean rewarded() {
            return !reward.empty();
        }
    }

    private final List<Section> sections;
    private final Map<String, Section> byId;
    private final Map<String, Entry> byKey;
    private final Map<EntityType, Entry> byType;
    private final int fullCoins;

    private HuntBestiary(List<Section> sections, int fullCoins) {
        this.sections = List.copyOf(sections);
        Map<String, Section> ids = new LinkedHashMap<>();
        Map<String, Entry> keys = new LinkedHashMap<>();
        Map<EntityType, Entry> types = new LinkedHashMap<>();
        for (Section section : this.sections) {
            ids.putIfAbsent(section.id(), section);
            for (Entry entry : section.entries()) {
                keys.putIfAbsent(entry.key(), entry);
                types.putIfAbsent(entry.type(), entry);
            }
        }
        this.byId = Collections.unmodifiableMap(ids);
        this.byKey = Collections.unmodifiableMap(keys);
        this.byType = Collections.unmodifiableMap(types);
        this.fullCoins = Math.clamp(fullCoins, 0, 1_000_000);
    }

    /**
     * Награды по умолчанию — те же, что в ресурсном {@code config.yml}.
     *
     * <p>Нужны для обновления без правки конфига: админ, оставивший старый
     * файл без секции {@code bestiary}, всё равно получит работающие награды, а
     * не пустые разделы.</p>
     */
    public static Map<String, Reward> defaultRewards() {
        Map<String, Reward> rewards = new LinkedHashMap<>();
        rewards.put("undead", new Reward(150, 10, 1));
        rewards.put("beasts", new Reward(150, 10, 1));
        rewards.put("arthropods", new Reward(100, 8, 1));
        rewards.put("nether", new Reward(200, 12, 1));
        rewards.put("end", new Reward(250, 12, 1));
        return Collections.unmodifiableMap(rewards);
    }

    /** Пустой бестиарий: разделов нет, наград нет, меню показывает заглушку. */
    public static HuntBestiary empty() {
        return new HuntBestiary(List.of(), 0);
    }

    /**
     * Собирает бестиарий из правил мобов: раздел = семейство, вид = правило.
     *
     * <p>Семейство без правил в журнал не попадает: раздел, который нельзя
     * закрыть, только расстраивал бы игрока. Мобы сверх
     * {@link #MAX_ENTRIES_PER_SECTION} отбрасываются — они не помещаются в меню,
     * и раздел стал бы недостижимым.</p>
     */
    public static HuntBestiary of(List<MobRule> rules, Map<String, HuntFamily> families,
                                  Map<String, Reward> rewards, Map<EntityType, Material> icons,
                                  int fullCoins) {
        if (rules == null || rules.isEmpty() || families == null || families.isEmpty()) {
            return empty();
        }
        Map<String, Reward> rewardsById = rewards == null ? Map.of() : rewards;
        List<Section> sections = new ArrayList<>();
        for (HuntFamily family : families.values()) {
            if (family == null) {
                continue;
            }
            List<Entry> entries = new ArrayList<>();
            for (MobRule rule : rules) {
                if (rule == null || !family.id().equals(rule.familyId())) {
                    continue;
                }
                if (entries.size() >= MAX_ENTRIES_PER_SECTION) {
                    break;
                }
                EntityType type = rule.entityType();
                Material icon = icons == null ? null : icons.get(type);
                entries.add(new Entry(keyOf(type), type, family.id(), rule.boss(),
                        icon == null ? defaultIcon(type) : icon));
            }
            if (entries.isEmpty()) {
                continue;
            }
            Reward reward = rewardsById.get(family.id());
            sections.add(new Section(family.id(), family.displayName(), entries,
                    reward == null ? Reward.NONE : reward));
            if (sections.size() >= MAX_SECTIONS) {
                break;
            }
        }
        return new HuntBestiary(sections, fullCoins);
    }

    /** Иконка вида по умолчанию: голова моба, затем яйцо призыва, затем бумага. */
    public static Material defaultIcon(EntityType type) {
        if (type == null) {
            return Material.PAPER;
        }
        String name = type.getKey().getKey().toUpperCase(Locale.ROOT);
        // valueOf, а не matchMaterial: разбор конфига должен работать и в тестах,
        // где реестр материалов ещё не поднят.
        Material head = HuntMaterials.resolve(name + "_HEAD");
        if (HuntMaterials.isItem(head)) {
            return head;
        }
        Material egg = HuntMaterials.resolve(name + "_SPAWN_EGG");
        if (HuntMaterials.isItem(egg)) {
            return egg;
        }
        return Material.PAPER;
    }

    /** Ключ вида для данных игрока: {@code zombie}, {@code wither_skeleton}. */
    public static String keyOf(EntityType type) {
        return type == null ? "" : normalize(type.getKey().getKey());
    }

    /** Ключ клиента для перевода: {@code entity.minecraft.zombie}. */
    public static String translationKey(EntityType type) {
        if (type == null) {
            return "entity.minecraft.pig";
        }
        try {
            String key = type.translationKey();
            if (key != null && !key.isBlank()) {
                return key;
            }
        } catch (RuntimeException | LinkageError error) {
            // Старое API без Translatable — ключ собираем сами.
        }
        return "entity.minecraft." + type.getKey().getKey();
    }

    public static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /* --------------------------------- доступ --------------------------------- */

    /** Все разделы в порядке конфига. */
    public List<Section> sections() {
        return sections;
    }

    /** Раздел по иду семейства или {@code null}. */
    public Section section(String id) {
        String key = normalize(id);
        return key.isEmpty() ? null : byId.get(key);
    }

    /** Вид по ключу ({@code zombie}) или {@code null}. */
    public Entry entry(String key) {
        String normalized = normalize(key);
        return normalized.isEmpty() ? null : byKey.get(normalized);
    }

    /** Вид по типу сущности или {@code null}: моб вне охоты в журнал не входит. */
    public Entry entryFor(EntityType type) {
        return type == null ? null : byType.get(type);
    }

    /** Всего видов во всех разделах. */
    public int totalEntries() {
        int total = 0;
        for (Section section : sections) {
            total += section.size();
        }
        return total;
    }

    /** Сколько разделов всего. */
    public int sectionCount() {
        return sections.size();
    }

    /** Награда за все закрытые разделы — разовая, сверх наград разделов. */
    public int fullCoins() {
        return fullCoins;
    }

    /** Бестиарий пуст: нет ни одного вида. */
    public boolean empty() {
        return byKey.isEmpty();
    }

    /** Все виды одним списком — для проверок и тестов. */
    public List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        for (Section section : sections) {
            out.addAll(section.entries());
        }
        return List.copyOf(out);
    }

    /* -------------------------------- прогресс -------------------------------- */

    /** Прогресс по разделу: сколько видов открыто и сколько всего. */
    public record Progress(String sectionId, int seen, int total, boolean complete) {

        /** Доля закрытия в процентах (0..100). */
        public int percent() {
            return total <= 0 ? 100 : (int) Math.clamp(Math.round(seen * 100.0 / total), 0, 100);
        }
    }

    /**
     * Считает прогресс раздела по журналу игрока.
     *
     * @param kills убийства по ключам видов ({@code zombie → 12})
     */
    public Progress progress(Section section, Map<String, Long> kills) {
        if (section == null) {
            return new Progress("", 0, 0, false);
        }
        Map<String, Long> journal = kills == null ? Map.of() : kills;
        int seen = 0;
        for (Entry entry : section.entries()) {
            Long value = journal.get(entry.key());
            if (value != null && value > 0L) {
                seen++;
            }
        }
        return new Progress(section.id(), seen, section.size(), seen >= section.size());
    }

    /** Сколько видов всех разделов открыто. */
    public int seenTotal(Map<String, Long> kills) {
        int seen = 0;
        for (Section section : sections) {
            seen += progress(section, kills).seen();
        }
        return seen;
    }

    /** Все разделы закрыты и журнал не пуст. */
    public boolean fullyComplete(Map<String, Long> kills) {
        if (empty()) {
            return false;
        }
        for (Section section : sections) {
            if (!progress(section, kills).complete()) {
                return false;
            }
        }
        return true;
    }
}
