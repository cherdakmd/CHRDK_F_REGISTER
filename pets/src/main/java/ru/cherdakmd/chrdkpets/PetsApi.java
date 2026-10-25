package ru.cherdakmd.chrdkpets;

import java.util.List;
import java.util.UUID;

/**
 * Публичный API питомцев для соседних аддонов.
 *
 * <p>Нужен, чтобы достижения, стримы или батлпасс могли спросить уровень
 * питомца по UUID, не завися от внутренних классов модуля. Интерфейс
 * регистрируется в {@code ServicesManager} и намеренно не содержит Bukkit-типов,
 * кроме UUID.</p>
 */
public interface PetsApi {

    /** Является ли сущность питомцем этого модуля. */
    boolean isPet(UUID petId);

    /** Уровень питомца; 0 — если записи нет. */
    int levelOf(UUID petId);

    /** Накопленный опыт; 0 — если записи нет. */
    long xpOf(UUID petId);

    /** Имя для показа (кличка или видовое название); пустая строка — если записи нет. */
    String displayNameOf(UUID petId);

    /** Все питомцы игрока, включая погибших. */
    List<UUID> petsOf(UUID ownerId);

    /** Бонус скорости ауры, который питомец даёт хозяину (0.027 = +2.7 %). */
    double ownerSpeedBonusOf(UUID petId);

    /** Бонус удачи ауры, который питомец даёт хозяину. */
    double ownerLuckBonusOf(UUID petId);
}
