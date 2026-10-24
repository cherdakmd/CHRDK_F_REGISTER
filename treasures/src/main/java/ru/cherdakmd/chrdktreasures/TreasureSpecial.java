package ru.cherdakmd.chrdktreasures;

import ru.cherdakmd.chrdkregister.Messages;

/**
 * Особая находка клада (1.4.166): артефакт, проклятая реликвия или книга чар.
 *
 * <p>Особые находки приходят из соседних аддонов — CHRDK_Artifacts и
 * CHRDK_Enchants — но клад остаётся единственным местом, где игрок видит их
 * сразу и с подписью. Запись хранит только то, что нужно для сообщения и
 * журнала: вид, имя и уровень книги. Сам предмет лежит в добыче.</p>
 *
 * @param kind  вид находки
 * @param name  имя в разметке аддона-источника (MiniMessage)
 * @param level уровень книги чар (0 — не относится к книгам)
 */
public record TreasureSpecial(TreasureSpecial.Kind kind, String name, int level) {

    public enum Kind {
        /** Светлая реликвия из CHRDK_Artifacts. */
        ARTIFACT("artifact"),
        /** Проклятая реликвия: полезная, но с расплатой. */
        ARTIFACT_CURSED("artifact-cursed"),
        /** Книга кастомной чары из CHRDK_Enchants. */
        ENCHANT("enchant");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        /** Ид для ключей messages.yml ({@code special-<id>}). */
        public String id() {
            return id;
        }

        /** Ключ текста находки. */
        public String textKey() {
            return "special-" + id;
        }
    }

    public TreasureSpecial {
        kind = kind == null ? Kind.ARTIFACT : kind;
        name = name == null ? "" : name;
        level = Math.max(0, level);
    }

    /** Нужно ли печатать уровень (только книги чар). */
    public boolean leveled() {
        return kind == Kind.ENCHANT && level > 0;
    }

    /** Подпись для журнала сервера: имя без разметки и уровень рядом. */
    public String plainName() {
        String clean = Messages.stripFormatting(name);
        if (clean.isBlank()) {
            clean = kind.id();
        }
        return leveled() ? clean + " " + level : clean;
    }
}
