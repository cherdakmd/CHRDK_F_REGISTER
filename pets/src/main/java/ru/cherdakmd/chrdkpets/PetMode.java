package ru.cherdakmd.chrdkpets;

/**
 * Режим поведения питомца.
 *
 * <p>Ванильные кошки и волки умеют сидеть и идти за хозяином, но выбор
 * сбрасывается после перезахода и не виден в интерфейсе. Модуль хранит режим в
 * {@code pets.json} и восстанавливает посадку при загрузке чанка, поэтому
 * «останься дома» переживает рестарт сервера.</p>
 */
public enum PetMode {

    /** Идёт за хозяином (ванильное поведение). */
    FOLLOW("follow"),
    /** Сидит на месте и ждёт. */
    STAY("stay");

    private final String id;

    PetMode(String id) {
        this.id = id;
    }

    /** Идентификатор для конфига, команд и хранилища. */
    public String id() {
        return id;
    }

    /** Режим по идентификатору; неизвестный — {@link #FOLLOW}. */
    public static PetMode byId(String id) {
        if (id == null) {
            return FOLLOW;
        }
        String normalized = id.trim();
        for (PetMode mode : values()) {
            if (mode.id.equalsIgnoreCase(normalized)) {
                return mode;
            }
        }
        return FOLLOW;
    }
}
