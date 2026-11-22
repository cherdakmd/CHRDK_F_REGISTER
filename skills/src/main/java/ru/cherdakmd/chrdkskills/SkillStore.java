package ru.cherdakmd.chrdkskills;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

/**
 * Данные навыков (`skills.json`).
 *
 * <p>Формат: {@code {"version":1,"players":{"<uuid>":{"name":"Steve",
 * "xp":{"mining":1234},"milestones":[10,25]}}}}. Хранится опыт, ник для топа и
 * выданные вехи — уровень всегда выводится из опыта по {@link SkillCurve},
 * поэтому смена кривой в конфиге не ломает данные и не требует миграции.</p>
 *
 * <p>Паттерн записи общий для репозитория: мутации на главном потоке,
 * {@link #snapshotJson()} и асинхронная запись {@link #saveNow()}; синхронизация
 * на хранилище, потому что запись идёт с другого потока.</p>
 *
 * <p>Класс чистый (Gson + NIO) — проверяется юнит-тестами без Bukkit.</p>
 */
public final class SkillStore {

    /** Прогресс одного игрока. */
    public static final class PlayerData {
        public final Map<SkillType, Long> xp = new EnumMap<>(SkillType.class);
        public final Set<Integer> milestones = new TreeSet<>();
        /** Ступени престижа по каждому навыку; сброс добровольный, опыт не теряется молча. */
        public final Map<SkillType, Integer> prestige = new EnumMap<>(SkillType.class);
        /** Пороги званий, за которые уже выдали разовые алмазы. */
        public final Set<Integer> claimedTiers = new TreeSet<>();
        /** Дневной бюджет физических алмазов: ключ дня и выпавшее сегодня. */
        public String diamondDay = "";
        public int diamondToday = 0;
        /** Последний известный ник — чтобы топ не ходил за профилями. */
        public String name = "";
        /** Распределённые очки атрибутов: сила, ловкость, стойкость, мудрость, хитрость. */
        public int strength = 0;
        public int agility = 0;
        public int stamina = 0;
        public int wisdom = 0;
        public int cunning = 0;
        /** Уровни веток билда: ветки и атрибуты делят один общий пул очков. */
        public final Map<BuildBranch, Integer> branches = new EnumMap<>(BuildBranch.class);
        /** Бонусные очки общего пула за честный PvE-вклад. */
        public int bonusPoints = 0;
        /** Журнал PvE-вклада: суммарные единицы честного вклада. */
        public long pve = 0L;
        /** Метка конвертации классового прогресса (п.29): очки уже выданы. */
        public boolean classConverted = false;
        /** Перерождения (п.30): полные сбросы персонажа за постоянные награды. */
        public int rebirths = 0;
        /** Метка конвертации старого престижа (п.30): ступени уже переведены. */
        public boolean rebirthConverted = false;
        /** Пороги вех перерождений, за которые уже выдали особые награды. */
        public final Set<Integer> claimedRebirthMilestones = new TreeSet<>();
        /** Уникальные предметы пула, уже выданные за перерождения (без повторов). */
        public final List<String> rebirthUniques = new ArrayList<>();

        public long xp(SkillType skill) {
            return xp.getOrDefault(skill, 0L);
        }

        /** Вложенные очки атрибута. */
        public int attribute(PlayerAttribute attribute) {
            return switch (attribute) {
                case STRENGTH -> strength;
                case AGILITY -> agility;
                case STAMINA -> stamina;
                case WISDOM -> wisdom;
                case CUNNING -> cunning;
            };
        }

        /** Кладёт очки атрибута; отрицательный итог зажимается к нулю. */
        public void addAttribute(PlayerAttribute attribute, int points) {
            int next = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, (long) attribute(attribute) + points));
            switch (attribute) {
                case STRENGTH -> strength = next;
                case AGILITY -> agility = next;
                case STAMINA -> stamina = next;
                case WISDOM -> wisdom = next;
                case CUNNING -> cunning = next;
            }
        }

        /** Суммарно вложенные очки всех атрибутов. */
        public long spentAttributesLong() {
            return (long) strength + agility + stamina + wisdom + cunning;
        }

        public int spentAttributes() {
            return (int) Math.min(Integer.MAX_VALUE, spentAttributesLong());
        }

        /** Уровень ветки билда. */
        public int branch(BuildBranch branch) {
            return branch == null ? 0 : branches.getOrDefault(branch, 0);
        }

        /** Ставит уровень ветки; итог зажимается в [0, {@link BuildBranch#MAX_LEVEL}]. */
        public void setBranch(BuildBranch branch, int level) {
            if (branch == null) return;
            int next = Math.max(0, Math.min(BuildBranch.MAX_LEVEL, level));
            if (next == 0) branches.remove(branch);
            else branches.put(branch, next);
        }

        /** Суммарно потрачено очков общего пула на ветки. */
        public int spentBranches() {
            int total = 0;
            for (int level : branches.values()) total += level;
            return total;
        }

        public long totalXp() {
            long total = 0L;
            for (long value : xp.values()) {
                total += value;
            }
            return total;
        }

        public int prestige(SkillType skill) {
            return skill == null ? 0 : prestige.getOrDefault(skill, 0);
        }

        public int totalPrestige() {
            int total = 0;
            for (int value : prestige.values()) {
                total += value;
            }
            return total;
        }
    }

    private static final Gson GSON = new GsonBuilder().setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.BIG_DECIMAL).setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {
    }.getType();

    private final Path file;
    private final Logger logger;
    private final Map<String, PlayerData> players = new LinkedHashMap<>();
    private boolean dirty;

    public SkillStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("skills.json");
        this.logger = logger;
    }

    public Path file() {
        return file;
    }

    public synchronized void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Object> data = GSON.fromJson(reader, DATA_TYPE);
            if (data == null || !(data.get("players") instanceof Map<?, ?> rows)) {
                return;
            }
            for (Map.Entry<?, ?> entry : rows.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> row)) {
                    continue;
                }
                PlayerData player = new PlayerData();
                if (row.get("xp") instanceof Map<?, ?> xpRows) {
                    for (Map.Entry<?, ?> xp : xpRows.entrySet()) {
                        SkillType skill = SkillType.byId(String.valueOf(xp.getKey()));
                        if (skill != null && xp.getValue() instanceof Number number) {
                            long value = number.longValue();
                            if (value > 0L) {
                                player.xp.put(skill, value);
                            }
                        }
                    }
                }
                Object name = row.get("name");
                player.name = name == null ? "" : String.valueOf(name).trim();
                if (row.get("milestones") instanceof List<?> milestones) {
                    for (Object element : milestones) {
                        if (element instanceof Number number) {
                            player.milestones.add(Math.max(1, number.intValue()));
                        }
                    }
                }
                if (row.get("prestige") instanceof Map<?, ?> prestigeRows) {
                    for (Map.Entry<?, ?> prestige : prestigeRows.entrySet()) {
                        SkillType skill = SkillType.byId(String.valueOf(prestige.getKey()));
                        if (skill != null && prestige.getValue() instanceof Number number
                                && number.intValue() > 0) {
                            // Старые ступени (потолок 10 на навык) сохраняем как
                            // историю: из них один раз считается конвертация.
                            player.prestige.put(skill, Math.min(10, number.intValue()));
                        }
                    }
                }
                if (row.get("claimedTiers") instanceof List<?> tiers) {
                    for (Object element : tiers) {
                        if (element instanceof Number number && number.intValue() > 0) {
                            player.claimedTiers.add(number.intValue());
                        }
                    }
                }
                if (row.get("diamondDay") != null) {
                    player.diamondDay = String.valueOf(row.get("diamondDay")).trim();
                }
                if (row.get("diamondToday") instanceof Number number) {
                    player.diamondToday = Math.max(0, Math.min(1_000_000, number.intValue()));
                }
                if (row.get("attributes") instanceof Map<?, ?> attributeRows) {
                    for (PlayerAttribute attribute : PlayerAttribute.values()) {
                        Object value = attributeRows.get(attribute.id());
                        if (value instanceof Number number) {
                            player.addAttribute(attribute, (int) Math.max(0L, Math.min(Integer.MAX_VALUE, number.longValue())));
                        }
                    }
                }
                if (row.get("branches") instanceof Map<?, ?> branchRows) {
                    for (Map.Entry<?, ?> branch : branchRows.entrySet()) {
                        BuildBranch target = BuildBranch.byId(String.valueOf(branch.getKey()));
                        if (target != null && branch.getValue() instanceof Number number) {
                            player.setBranch(target, Math.max(0, Math.min(BuildBranch.MAX_LEVEL, number.intValue())));
                        }
                    }
                }
                if (row.get("bonusPoints") instanceof Number number) {
                    player.bonusPoints = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, number.longValue()));
                }
                if (row.get("pve") instanceof Number number) {
                    player.pve = Math.max(0L, number.longValue());
                }
                if (row.get("classConverted") instanceof Boolean flag) {
                    player.classConverted = flag;
                }
                if (row.get("rebirths") instanceof Number number) {
                    player.rebirths = Math.max(0, Math.min(1_000_000, number.intValue()));
                }
                if (row.get("rebirthConverted") instanceof Boolean flag) {
                    player.rebirthConverted = flag;
                }
                if (row.get("claimedRebirthMilestones") instanceof List<?> marks) {
                    for (Object element : marks) {
                        if (element instanceof Number number && number.intValue() > 0) {
                            player.claimedRebirthMilestones.add(number.intValue());
                        }
                    }
                }
                if (row.get("rebirthUniques") instanceof List<?> uniques) {
                    for (Object element : uniques) {
                        String id = String.valueOf(element).trim();
                        if (!id.isEmpty()) {
                            player.rebirthUniques.add(id);
                        }
                    }
                }
                // Конвертация престижа (п.30): каждые 10 старых ступеней суммарно
                // по навыкам — одно перерождение. Вехи до полученного счётчика
                // помечаем выданными: ветеран не получает награды задним числом.
                // Грязным хранилище становится только при реальном изменении —
                // само чтение файла не обязано провоцировать запись.
                if (!player.rebirthConverted) {
                    int converted = Math.max(player.rebirths,
                            RebirthMath.fromLegacySteps(player.totalPrestige()));
                    if (converted != player.rebirths) {
                        player.rebirths = converted;
                        dirty = true;
                    }
                    for (int mark : RebirthMath.milestoneThresholds()) {
                        if (mark <= player.rebirths) {
                            player.claimedRebirthMilestones.add(mark);
                        }
                    }
                    player.rebirthConverted = true;
                }
                players.put(String.valueOf(entry.getKey()), player);
            }
            logger.info("[CHRDK_Skills] Загружено профилей: " + players.size() + ".");
        } catch (Exception exception) {
            logger.severe("[CHRDK_Skills] Не удалось прочитать " + file + ": "
                    + exception.getMessage());
        }
    }

    public synchronized long xp(String playerKey, SkillType skill) {
        PlayerData data = players.get(playerKey);
        return data == null || skill == null ? 0L : data.xp(skill);
    }

    /** Начисляет опыт; отрицательные приращения игнорируются. */
    public synchronized long addXp(String playerKey, SkillType skill, long amount) {
        if (playerKey == null || skill == null || amount <= 0L) {
            return xp(playerKey, skill);
        }
        PlayerData data = players.computeIfAbsent(playerKey, key -> new PlayerData());
        long next = data.xp(skill) + amount;
        data.xp.put(skill, next);
        dirty = true;
        return next;
    }

    /** Прямая установка опыта (админская команда). */
    public synchronized void setXp(String playerKey, SkillType skill, long amount) {
        if (playerKey == null || skill == null) {
            return;
        }
        PlayerData data = players.computeIfAbsent(playerKey, key -> new PlayerData());
        data.xp.put(skill, Math.max(0L, amount));
        dirty = true;
    }

    /** Согласованный снимок развития; без записи оценка неизвестна, а не равна нулю. */
    public synchronized java.util.Optional<ProgressSnapshot> progressSnapshot(String playerKey) {
        PlayerData data = players.get(playerKey);
        if (data == null) return java.util.Optional.empty();
        Map<SkillType, Long> xp = new EnumMap<>(SkillType.class);
        for (SkillType skill : SkillType.values()) xp.put(skill, data.xp(skill));
        return java.util.Optional.of(new ProgressSnapshot(xp, data.rebirths, data.bonusPoints));
    }

    public record ProgressSnapshot(Map<SkillType, Long> xp, int rebirths, int bonusPoints) {
        public ProgressSnapshot { xp = Map.copyOf(xp); }
    }

    /** Снимок сохранённого билда; отсутствие записи отличается от нулевого билда. */
    public synchronized java.util.Optional<BuildSnapshot> buildSnapshot(String playerKey) {
        PlayerData data = players.get(playerKey);
        if (data == null) return java.util.Optional.empty();
        Map<PlayerAttribute, Integer> attributes = new EnumMap<>(PlayerAttribute.class);
        for (PlayerAttribute attribute : PlayerAttribute.values()) {
            attributes.put(attribute, data.attribute(attribute));
        }
        Map<BuildBranch, Integer> branches = new EnumMap<>(BuildBranch.class);
        for (BuildBranch branch : BuildBranch.values()) {
            branches.put(branch, data.branch(branch));
        }
        return java.util.Optional.of(new BuildSnapshot(attributes, branches));
    }

    public record BuildSnapshot(Map<PlayerAttribute, Integer> attributes,
                                Map<BuildBranch, Integer> branches) {
        public BuildSnapshot {
            attributes = Map.copyOf(attributes);
            branches = Map.copyOf(branches);
        }
    }

    /** Вложенные очки атрибута; нет профиля — нули. */
    public synchronized int attribute(String playerKey, PlayerAttribute attribute) {
        PlayerData data = players.get(playerKey);
        return data == null || attribute == null ? 0 : data.attribute(attribute);
    }

    /** Суммарно вложенные очки атрибутов; нет профиля — ноль. */
    public synchronized int spentAttributes(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null ? 0 : data.spentAttributes();
    }

    /** Полная сумма для расчёта остатка без переполнения int. */
    public synchronized long spentAttributesLong(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null ? 0 : data.spentAttributesLong();
    }

    /** Вкладывает очки в атрибут; {@code false} — нет игрока, атрибута или очков. */
    public synchronized boolean addAttribute(String playerKey, PlayerAttribute attribute, int points) {
        if (playerKey == null || attribute == null || points <= 0) {
            return false;
        }
        PlayerData data = players.computeIfAbsent(playerKey, key -> new PlayerData());
        if ((long) data.attribute(attribute) + points > Integer.MAX_VALUE) return false;
        data.addAttribute(attribute, points);
        dirty = true;
        return true;
    }

    /** Сбрасывает все атрибуты в ноль; очки возвращаются в свободный пул. */
    public synchronized void resetAttributes(String playerKey) {
        PlayerData data = players.get(playerKey);
        if (data == null) {
            return;
        }
        for (PlayerAttribute attribute : PlayerAttribute.values()) {
            data.addAttribute(attribute, -data.attribute(attribute));
        }
        dirty = true;
    }

    /** Уровень ветки билда; нет профиля — ноль. */
    public synchronized int branch(String playerKey, BuildBranch branch) {
        PlayerData data = players.get(playerKey);
        return data == null ? 0 : data.branch(branch);
    }

    /** Суммарно потрачено очков общего пула на ветки. */
    public synchronized int spentBranches(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null ? 0 : data.spentBranches();
    }

    /** Ставит уровень ветки; {@code false} — нет ветки или уровень вне диапазона. */
    public synchronized boolean setBranch(String playerKey, BuildBranch branch, int level) {
        if (playerKey == null || branch == null || level < 0 || level > BuildBranch.MAX_LEVEL) {
            return false;
        }
        PlayerData data = players.computeIfAbsent(playerKey, key -> new PlayerData());
        data.setBranch(branch, level);
        dirty = true;
        return true;
    }

    /** Сбрасывает все ветки билда; очки возвращаются в общий пул. */
    public synchronized void resetBranches(String playerKey) {
        PlayerData data = players.get(playerKey);
        if (data == null) {
            return;
        }
        for (BuildBranch branch : BuildBranch.values()) {
            data.setBranch(branch, 0);
        }
        dirty = true;
    }

    /** Бонусные очки общего пула за честный PvE-вклад. */
    public synchronized int bonusPoints(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null ? 0 : data.bonusPoints;
    }

    /** Кладёт бонусные очки; итог не уходит в минус. */
    public synchronized void addBonusPoints(String playerKey, int points) {
        if (playerKey == null) return;
        PlayerData data = players.computeIfAbsent(playerKey, key -> new PlayerData());
        data.bonusPoints = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, (long) data.bonusPoints + points));
        dirty = true;
    }

    /** Суммарный честный PvE-вклад (журнал). */
    public synchronized long pve(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null ? 0L : data.pve;
    }

    /** Прибавляет единицы вклада; итог не уходит в минус. */
    public synchronized long addPve(String playerKey, long units) {
        if (playerKey == null) return 0L;
        PlayerData data = players.computeIfAbsent(playerKey, key -> new PlayerData());
        if (units > 0 && units > Long.MAX_VALUE - data.pve) data.pve = Long.MAX_VALUE;
        else if (units < 0 && units < -data.pve) data.pve = 0;
        else data.pve += units;
        dirty = true;
        return data.pve;
    }

    /** Вклад и заработанные очки обновляются вместе относительно фонового сохранения. */
    public synchronized int awardPve(String playerKey, long units) {
        if (playerKey == null || units <= 0) return 0;
        PlayerData data = players.computeIfAbsent(playerKey, key -> new PlayerData());
        long before = data.pve;
        long after = units > Long.MAX_VALUE - before ? Long.MAX_VALUE : before + units;
        long due = after / BranchMath.PVE_PER_POINT - before / BranchMath.PVE_PER_POINT;
        int awarded = (int) Math.min(due, (long) Integer.MAX_VALUE - data.bonusPoints);
        data.pve = after;
        data.bonusPoints += awarded;
        dirty = true;
        return awarded;
    }

    /**
     * Одноразовая конвертация классового прогресса в очки пула;
     * {@code false} — игрок уже конвертирован или очков нет.
     */
    public synchronized boolean migrateClassPoints(String playerKey, int points) {
        if (playerKey == null || points <= 0) {
            return false;
        }
        PlayerData data = players.computeIfAbsent(playerKey, key -> new PlayerData());
        if (data.classConverted) {
            return false;
        }
        data.classConverted = true;
        data.bonusPoints = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, (long) data.bonusPoints + points));
        dirty = true;
        return true;
    }

    /** Опыт по всем навыкам разом. */
    public synchronized Map<SkillType, Long> allXp(String playerKey) {
        PlayerData data = players.get(playerKey);
        Map<SkillType, Long> out = new EnumMap<>(SkillType.class);
        for (SkillType skill : SkillType.values()) {
            out.put(skill, data == null ? 0L : data.xp(skill));
        }
        return out;
    }

    /** Последний известный ник игрока (пустая строка — не записан). */
    public synchronized String name(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null ? "" : data.name;
    }

    /** Обновляет ник, если он изменился. */
    public synchronized void setName(String playerKey, String name) {
        if (playerKey == null || name == null || name.isBlank()) {
            return;
        }
        PlayerData data = players.computeIfAbsent(playerKey, key -> new PlayerData());
        if (name.equals(data.name)) {
            return;
        }
        data.name = name.trim();
        dirty = true;
    }

    /** Топ по суммарному опыту всех навыков. */
    public synchronized List<Map.Entry<String, Long>> topByTotalXp(int limit) {
        List<Map.Entry<String, Long>> ranked = new ArrayList<>();
        for (Map.Entry<String, PlayerData> entry : players.entrySet()) {
            ranked.add(Map.entry(entry.getKey(), entry.getValue().totalXp()));
        }
        ranked.sort((left, right) -> Long.compare(right.getValue(), left.getValue()));
        return List.copyOf(ranked.subList(0, Math.min(Math.max(0, limit), ranked.size())));
    }

    public synchronized Set<Integer> milestones(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null ? Set.of() : Set.copyOf(data.milestones);
    }

    /** Отмечает веху выданной. {@code true} — веха новая. */
    public synchronized boolean markMilestone(String playerKey, int level) {
        PlayerData data = players.computeIfAbsent(playerKey, key -> new PlayerData());
        if (!data.milestones.add(level)) {
            return false;
        }
        dirty = true;
        return true;
    }

    /* ------------------------ перерождения и дневной лут --------------------- */

    /** Сколько перерождений сделал игрок. */
    public synchronized int rebirths(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null ? 0 : data.rebirths;
    }

    /** Добавляет перерождение. */
    public synchronized void addRebirth(String playerKey) {
        if (playerKey == null) {
            return;
        }
        PlayerData data = players.computeIfAbsent(playerKey, key -> new PlayerData());
        data.rebirths = Math.min(1_000_000, data.rebirths + 1);
        dirty = true;
    }

    /** Полный сброс персонажа: уровни навыков, атрибуты и ветки — под ноль. */
    public synchronized void resetForRebirth(String playerKey) {
        PlayerData data = players.get(playerKey);
        if (data == null) {
            return;
        }
        data.xp.clear();
        data.strength = 0;
        data.agility = 0;
        data.stamina = 0;
        data.wisdom = 0;
        data.cunning = 0;
        data.branches.clear();
        dirty = true;
    }

    /** Пороги вех перерождений, за которые награда уже выдана. */
    public synchronized Set<Integer> claimedRebirthMilestones(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null ? Set.of() : Set.copyOf(data.claimedRebirthMilestones);
    }

    /** Отмечает веху перерождения выданной; {@code true} — порог новый. */
    public synchronized boolean markRebirthMilestone(String playerKey, int threshold) {
        if (playerKey == null || threshold <= 0) {
            return false;
        }
        PlayerData data = players.computeIfAbsent(playerKey, key -> new PlayerData());
        if (!data.claimedRebirthMilestones.add(threshold)) {
            return false;
        }
        dirty = true;
        return true;
    }

    /** Уникальные предметы пула, уже выданные игроку за перерождения. */
    public synchronized List<String> rebirthUniques(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null ? List.of() : List.copyOf(data.rebirthUniques);
    }

    /** Выдаёт уникальный предмет из пула; {@code false} — уже был. */
    public synchronized boolean grantRebirthUnique(String playerKey, String itemId) {
        if (playerKey == null || itemId == null || itemId.isBlank()) {
            return false;
        }
        PlayerData data = players.computeIfAbsent(playerKey, key -> new PlayerData());
        if (data.rebirthUniques.contains(itemId.trim())) {
            return false;
        }
        data.rebirthUniques.add(itemId.trim());
        dirty = true;
        return true;
    }

    /** Топ по числу перерождений (только игроки с хотя бы одним). */
    public synchronized List<Map.Entry<String, Integer>> rebirthTop(int limit) {
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>();
        for (Map.Entry<String, PlayerData> entry : players.entrySet()) {
            int count = entry.getValue().rebirths;
            if (count > 0) {
                ranked.add(Map.entry(entry.getKey(), count));
            }
        }
        ranked.sort((left, right) -> Integer.compare(right.getValue(), left.getValue()));
        return List.copyOf(ranked.subList(0, Math.min(Math.max(0, limit), ranked.size())));
    }

    /** Сумма старых ступеней престижа — история и конвертация, механики не питает. */
    public synchronized int totalPrestige(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null ? 0 : data.totalPrestige();
    }

    /** Сколько физических алмазов выпало игроку в указанный день. */
    public synchronized int diamondDropsToday(String playerKey, String day) {
        PlayerData data = players.get(playerKey);
        if (data == null || day == null || !day.equals(data.diamondDay)) {
            return 0;
        }
        return data.diamondToday;
    }

    /** Учитывает выпавшие алмазы; смена дня обнуляет счётчик. */
    public synchronized void noteDiamondDrops(String playerKey, String day, int amount) {
        if (playerKey == null || day == null || amount <= 0) {
            return;
        }
        PlayerData data = players.computeIfAbsent(playerKey, key -> new PlayerData());
        if (!day.equals(data.diamondDay)) {
            data.diamondDay = day;
            data.diamondToday = 0;
        }
        data.diamondToday = Math.min(1_000_000, data.diamondToday + amount);
        dirty = true;
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    /** Снимок для асинхронной записи; сбрасывает флаг «грязно». */
    public synchronized String snapshotJson() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", 1);
        Map<String, Object> rows = new LinkedHashMap<>();
        players.forEach((key, player) -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            Map<String, Object> xpRows = new LinkedHashMap<>();
            player.xp.forEach((skill, value) -> {
                if (value > 0L) {
                    xpRows.put(skill.id(), value);
                }
            });
            Map<String, Object> prestigeRows = new LinkedHashMap<>();
            player.prestige.forEach((skill, value) -> {
                if (value > 0) {
                    prestigeRows.put(skill.id(), value);
                }
            });
            fields.put("name", player.name);
            fields.put("xp", xpRows);
            fields.put("milestones", new ArrayList<>(player.milestones));
            // version остаётся 1: MilestoneMigration читает этот файл и требует
            // ровно эту версию. Новые поля совместимы со старым загрузчиком.
            fields.put("prestige", prestigeRows);
            fields.put("claimedTiers", new ArrayList<>(player.claimedTiers));
            fields.put("diamondDay", player.diamondDay);
            fields.put("diamondToday", player.diamondToday);
            Map<String, Object> attributeRows = new LinkedHashMap<>();
            for (PlayerAttribute attribute : PlayerAttribute.values()) {
                int value = player.attribute(attribute);
                if (value > 0) {
                    attributeRows.put(attribute.id(), value);
                }
            }
            fields.put("attributes", attributeRows);
            Map<String, Object> branchRows = new LinkedHashMap<>();
            for (BuildBranch branch : BuildBranch.values()) {
                int value = player.branch(branch);
                if (value > 0) {
                    branchRows.put(branch.id(), value);
                }
            }
            fields.put("branches", branchRows);
            if (player.bonusPoints > 0) fields.put("bonusPoints", player.bonusPoints);
            if (player.pve > 0) fields.put("pve", player.pve);
            if (player.classConverted) fields.put("classConverted", true);
            if (player.rebirths > 0) fields.put("rebirths", player.rebirths);
            if (player.rebirthConverted) fields.put("rebirthConverted", true);
            if (!player.claimedRebirthMilestones.isEmpty()) {
                fields.put("claimedRebirthMilestones", new ArrayList<>(player.claimedRebirthMilestones));
            }
            if (!player.rebirthUniques.isEmpty()) {
                fields.put("rebirthUniques", new ArrayList<>(player.rebirthUniques));
            }
            rows.put(key, fields);
        });
        data.put("players", rows);
        dirty = false;
        return GSON.toJson(data);
    }

    /** Пишет файл, если есть изменения (или его ещё нет). */
    public void saveNow() {
        String json;
        synchronized (this) {
            if (!dirty && Files.isRegularFile(file)) {
                return;
            }
            json = snapshotJson();
        }
        write(json);
    }

    /** Немедленная запись без проверки флага (выключение сервера, тесты). */
    public void flush() {
        write(snapshotJson());
    }

    private void write(String json) {
        if (!SafeJsonWriter.write(file, json, logger, "[CHRDK_Skills]")) {
            // Диск не принял данные — помечаем состояние изменённым, иначе
            // следующее автосохранение решит, что писать нечего, и прогресс
            // потеряется молча.
            synchronized (this) {
                dirty = true;
            }
        }
    }


}
