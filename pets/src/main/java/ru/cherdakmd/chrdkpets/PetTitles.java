package ru.cherdakmd.chrdkpets;

/**
 * Титул питомца по уровню — короткая подпись в карточке и списке.
 *
 * <p>Титул ничего не даёт: это звание, которое видно в интерфейсе и в
 * {@code /pet top}. Ключи текстов лежат в {@code messages.yml}, поэтому сервер
 * может переписать звания под свой лад без правки кода.</p>
 */
public enum PetTitles {

    BABY("title-baby", 1),
    FRIEND("title-friend", 3),
    LOYAL("title-loyal", 5),
    GUARDIAN("title-guardian", 7),
    LEGEND("title-legend", 9);

    private final String messageKey;
    private final int minLevel;

    PetTitles(String messageKey, int minLevel) {
        this.messageKey = messageKey;
        this.minLevel = minLevel;
    }

    public String messageKey() {
        return messageKey;
    }

    public int minLevel() {
        return minLevel;
    }

    /** Титул для уровня; уровни ниже первого получают младший титул. */
    public static PetTitles of(int level) {
        PetTitles result = BABY;
        for (PetTitles title : values()) {
            if (level >= title.minLevel) {
                result = title;
            }
        }
        return result;
    }
}
