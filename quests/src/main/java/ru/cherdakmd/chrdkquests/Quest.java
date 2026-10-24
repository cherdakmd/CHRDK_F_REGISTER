package ru.cherdakmd.chrdkquests;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Одно задание цепочки (1.4.161).
 *
 * <p>Задание знает свой вид, цели и награду, но не знает игрока: прогресс и
 * сдача живут в {@link QuestService} и {@link QuestStore}. Так один и тот же
 * объект из конфига обслуживает всех, а «выполнено/не выполнено» не может
 * разъехаться между меню, командой и слушателем событий.</p>
 *
 * @param id          уникальный ид во всём конфиге (ключ прогресса)
 * @param npcId       ид персонажа, к которому относится задание
 * @param name        имя задания для чата и меню
 * @param description короткое пояснение, что делать
 * @param type        вид задания
 * @param entities    цели-мобы для {@link QuestType#KILL}
 * @param materials   цели-предметы для {@link QuestType#MINE} и {@link QuestType#GATHER}
 * @param amount      сколько нужно сделать (1..100000)
 * @param reward      опыт, репутация и предметы
 * @param minRank     минимальный номер ранга фракции (0 — без требования)
 */
public record Quest(String id, String npcId, String name, String description, QuestType type,
                    List<EntityType> entities, List<Material> materials, int amount,
                    QuestReward reward, int minRank) {

    /** Потолок цели: больше — уже не задание, а бесконечность. */
    public static final int MAX_AMOUNT = 100_000;
    /** Потолок требования по рангу. */
    public static final int MAX_RANK = 9;

    public Quest {
        id = normalize(id);
        npcId = normalize(npcId);
        name = name == null || name.isBlank() ? id : name.trim();
        description = description == null ? "" : description.trim();
        type = type == null ? QuestType.KILL : type;
        entities = copyEntities(entities);
        materials = copyMaterials(materials);
        amount = Math.clamp(amount, 1, MAX_AMOUNT);
        reward = reward == null ? QuestReward.none() : reward;
        minRank = Math.clamp(minRank, 0, MAX_RANK);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static List<EntityType> copyEntities(List<EntityType> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<EntityType> out = new ArrayList<>();
        for (EntityType type : source) {
            if (type != null && !out.contains(type)) {
                out.add(type);
            }
        }
        return List.copyOf(out);
    }

    private static List<Material> copyMaterials(List<Material> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<Material> out = new ArrayList<>();
        for (Material material : source) {
            if (material != null && material != Material.AIR && !out.contains(material)) {
                out.add(material);
            }
        }
        return List.copyOf(out);
    }

    /** Есть ли у задания цели и награда: пустое задание не попадает в мир. */
    public boolean usable() {
        if (id.isEmpty() || amount <= 0 || reward.empty()) {
            return false;
        }
        return switch (type) {
            case KILL -> !entities.isEmpty();
            case MINE, GATHER -> !materials.isEmpty();
        };
    }

    /** Цели словами — список языковых тегов для сообщений и меню. */
    public List<String> targetTags() {
        List<String> tags = new ArrayList<>();
        if (type == QuestType.KILL) {
            for (EntityType type : entities) {
                tags.add(QuestText.entityTag(type));
            }
        } else {
            for (Material material : materials) {
                tags.add(QuestText.materialTag(material));
            }
        }
        return tags;
    }

    /** Ид персонажа, к которому относится задание. */
    public boolean belongsTo(String otherNpcId) {
        return otherNpcId != null && npcId.equals(otherNpcId.trim().toLowerCase(Locale.ROOT));
    }

    /** Подходит ли убитый моб под цель. */
    public boolean matchesEntity(EntityType type) {
        return type != null && entities.contains(type);
    }

    /** Подходит ли сломанный блок или сданный предмет под цель. */
    public boolean matchesMaterial(Material material) {
        return material != null && materials.contains(material);
    }
}
