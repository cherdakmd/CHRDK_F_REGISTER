package ru.cherdakmd.chrdkpets;

/**
 * Достижения питомца — шесть вех, которые видны в {@code /pet achievements}.
 *
 * <p>Пороги подобраны так, чтобы вехи закрывались обычной игрой, а не
 * гриндом: первый уровень, пятый, максимум кривой, 25 убийств волка, 25
 * пойманных рыб и час, проведённый рядом. Достижения ничего не дают — это
 * память о совместной игре, поэтому их нельзя купить и нельзя ускорить
 * деньгами.</p>
 */
public enum PetAchievement {

    FIRST_LEVEL("first-level"),
    VETERAN("veteran"),
    LEGEND("legend"),
    HUNTER("hunter"),
    FISHER("fisher"),
    COMPANION("companion");

    private final String id;

    PetAchievement(String id) {
        this.id = id;
    }

    /** Идентификатор для хранилища и ключа сообщения {@code achievement-<id>}. */
    public String id() {
        return id;
    }

    /** Снимок прогресса, по которому проверяются вехи. */
    public record Progress(int level, int maxLevel, int kills, int fish, long minutes) {
    }

    /** Закрыта ли веха на этом прогрессе. */
    public boolean unlocked(Progress progress) {
        if (progress == null) {
            return false;
        }
        return switch (this) {
            case FIRST_LEVEL -> progress.level() >= 2;
            case VETERAN -> progress.level() >= 5;
            case LEGEND -> progress.maxLevel() >= 2 && progress.level() >= progress.maxLevel();
            case HUNTER -> progress.kills() >= 25;
            case FISHER -> progress.fish() >= 25;
            case COMPANION -> progress.minutes() >= 60L;
        };
    }
}
