package ru.cherdakmd.chrdkregister.api;

/** API 28: только чтение текущего членства, без выдачи должностей, денег или прав.
 * Вызывается на серверном потоке. Провайдер не делает сетевой поиск игрока.
 * null означает отсутствие подтверждённого членства; потребитель отказывает в производном доступе. */
@FunctionalInterface
public interface ClanMembershipIndex {
    Membership membership(String playerName);

    /** Стабильный ID, не имя/тег клана. Должность не определяет права на чужую личную землю. */
    record Membership(String id, String name, String tag, String role) {
        public Membership {
            if (id == null || id.isBlank() || id.length() > 128) throw new IllegalArgumentException("clan id");
            name = name == null ? "" : name;
            tag = tag == null ? "" : tag;
            role = role == null ? "" : role;
        }
    }
}
