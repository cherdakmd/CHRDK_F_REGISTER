package ru.cherdakmd.chrdkclaims;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Приват: чанк (world + x/z), владелец, участники с ролями, оплата и выставленная цена. */
public final class Claim {

    /** Максимальная длина приветствия привата (символы). */
    public static final int MAX_GREETING = 80;

    /**
     * Уровень доступа участника. Владелец всегда {@link #BUILD}.
     * Порядок объявления важен: доступ проверяется через {@link #ordinal()}.
     */
    public enum Role {
        /** Двери, кнопки, рычаги, вёрстаки — без контейнеров и стройки. */
        ACCESS,
        /** Всё из {@link #ACCESS} плюс сундуки, печки, воронки, кафедры. */
        CONTAINER,
        /** Полный доступ: стройка, урожай, животные, транспорт. */
        BUILD
    }

    public final String world;
    public final int x;
    public final int z;
    public String owner;
    public final List<String> members = new ArrayList<>();
    /** Роли участников (ник в нижнем регистре); отсутствие записи — {@link Role#BUILD}. */
    private final Map<String, Role> roles = new LinkedHashMap<>();
    /** Момент, до которого оплачена рента (millis). */
    public long paidUntil;
    /** Сколько суток подряд рента не была списана за нехваткой казны. */
    public int debtDays;
    /** Цена продажи игрокам (0 — не продаётся). */
    public int salePrice;
    /** Приветствие для гостей: показывается при входе в приват (пусто — нет). */
    public String greeting = "";
    /** Предложение аренды этого чанка, алмазов/сутки; не цена всей территории. */
    public int rentOffer;
    public String tenant = "";
    public int rentPrice;
    public long rentUntil;
    public boolean rentRenew;

    public boolean rentedAt(long now) { return tenant != null && !tenant.isBlank() && rentUntil > now; }
    public void clearRental() { tenant = ""; rentPrice = 0; rentUntil = 0; rentRenew = false; }
    public String accessDomain(long now) { return rentedAt(now) ? "tenant:" + tenant.toLowerCase(Locale.ROOT) : "owner:" + owner.toLowerCase(Locale.ROOT); }


    public Claim(String world, int x, int z, String owner, long paidUntil) {
        this.world = world;
        this.x = x;
        this.z = z;
        this.owner = owner;
        this.paidUntil = paidUntil;
    }

    public long key() {
        return ClaimMath.chunkKey(x, z);
    }

    public boolean isOwner(String name) {
        return owner != null && owner.equalsIgnoreCase(name);
    }

    public boolean isMember(String name) {
        return members.stream().anyMatch(member -> member.equalsIgnoreCase(name));
    }

    /**
     * Фактическая роль игрока: {@code null} — посторонний, владелец всегда полный.
     * Участник без явной роли сохраняет прежнее поведение — полный доступ.
     */
    public Role roleOf(String name) { return roleAt(name, System.currentTimeMillis()); }

    Role roleAt(String name, long now) {
        if (isOwner(name)) {
            return Role.BUILD;
        }
        if (rentedAt(now)) return tenant.equalsIgnoreCase(name) ? Role.BUILD : null;
        return memberRole(name);
    }

    Role memberRole(String name) {
        if (isOwner(name)) return Role.BUILD;
        if (name == null || !isMember(name)) {
            return null;
        }
        return roles.getOrDefault(name.toLowerCase(Locale.ROOT), Role.BUILD);
    }

    /** Хватает ли роли игрока для действия не ниже {@code required}. */
    public boolean allows(String name, Role required) {
        Role role = roleOf(name);
        return role != null && role.ordinal() >= required.ordinal();
    }

    /** Может ли игрок строить и ломать (полный доступ). */
    public boolean canBuild(String name) {
        return allows(name, Role.BUILD);
    }

    /** Может ли открывать контейнеры (контейнерный доступ и выше). */
    public boolean canOpen(String name) {
        return allows(name, Role.CONTAINER);
    }

    /** Может ли пользоваться дверями и механизмами (любая роль участника). */
    public boolean canUse(String name) {
        return allows(name, Role.ACCESS);
    }

    /** Назначает роль существующему участнику; полный доступ хранится неявно. */
    public void setRole(String name, Role role) {
        if (name == null || role == null || !isMember(name)) {
            return;
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (role == Role.BUILD) {
            roles.remove(key);
        } else {
            roles.put(key, role);
        }
    }

    /** Убирает участника и его роль. @return был ли он участником */
    public boolean removeMember(String name) {
        if (name == null) {
            return false;
        }
        roles.remove(name.toLowerCase(Locale.ROOT));
        return members.removeIf(member -> member.equalsIgnoreCase(name));
    }

    /** Очищает всех участников (передача/продажа привата). */
    public void clearMembers() {
        members.clear();
        roles.clear();
    }

    /** Роли только для тех, кому назначен неполный доступ (сохранение в JSON). */
    public Map<String, Role> explicitRoles() {
        return Map.copyOf(roles);
    }

    /** Восстанавливает роль из файла; проверяет, что игрок действительно участник. */
    public void applyRole(String name, Role role) {
        if (name != null && role != null && role != Role.BUILD && isMember(name)) {
            roles.put(name.toLowerCase(Locale.ROOT), role);
        }
    }

    /** Приветствие без переводов строк и в пределах {@link #MAX_GREETING}. */
    public static String normalizeGreeting(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        if (text.length() > MAX_GREETING) {
            text = text.substring(0, MAX_GREETING).trim();
        }
        return text;
    }

    public boolean hasGreeting() {
        return greeting != null && !greeting.isBlank();
    }

    public String where() {
        return world + " [" + x + ", " + z + "]";
    }
}
