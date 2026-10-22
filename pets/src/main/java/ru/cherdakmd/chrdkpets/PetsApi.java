package ru.cherdakmd.chrdkpets;

import java.util.List;
import java.util.UUID;

/**
 * Публичный API питомцев для других аддонов.
 *
 * <p>Интерфейс намеренно не тянет Bukkit-типы: достижения или стримы могут
 * спросить уровень питомца по UUID, не завися от сущностей и мира. Регистрация —
 * через {@code ServicesManager}, как у остальных модулей репозитория.</p>
 */
public interface PetsApi {

    /** Зарегистрирован ли питомец (в том числе погибший). */
    boolean isPet(UUID petId);

    /** Уровень питомца; 0 — запись не найдена. */
    int levelOf(UUID petId);

    /** Накопленный опыт; 0 — запись не найдена. */
    long xpOf(UUID petId);

    /** Кличка (пустая строка — не назван); {@code null} — запись не найдена. */
    String nameOf(UUID petId);

    /** Все питомцы игрока, включая погибших. */
    List<UUID> petsOf(UUID ownerId);
}
