package ru.cherdakmd.chrdkpets;

import org.bukkit.entity.Cat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Wolf;

/**
 * Вид питомца. Модуль работает только с ванильными приручёнными кошками и
 * волками — новых сущностей не спавнит и чужое поголовье не трогает.
 *
 * <p>Вид хранится в данных строкой ({@code wolf}/{@code cat}), поэтому
 * переименование константы не ломает {@code pets.json}. Неизвестный вид при
 * загрузке пропускается с предупреждением в лог.</p>
 */
public enum PetSpecies {

    WOLF("wolf"),
    CAT("cat");

    private final String id;

    PetSpecies(String id) {
        this.id = id;
    }

    /** Идентификатор для конфига и хранилища. */
    public String id() {
        return id;
    }

    /** Вид по сущности; {@code null} — это не кошка и не волк. */
    public static PetSpecies of(Entity entity) {
        if (entity instanceof Wolf) {
            return WOLF;
        }
        if (entity instanceof Cat) {
            return CAT;
        }
        return null;
    }

    /** Вид по идентификатору из конфига/файла; {@code null} — неизвестный. */
    public static PetSpecies byId(String id) {
        if (id == null) {
            return null;
        }
        String normalized = id.trim();
        for (PetSpecies species : values()) {
            if (species.id.equalsIgnoreCase(normalized)) {
                return species;
            }
        }
        return null;
    }

    /** Умеет ли вид бить: волк — да, кошка — нет. */
    public boolean canAttack() {
        return this == WOLF;
    }
}
