package ru.cherdakmd.chrdkclaims;

import java.util.Locale;

/**
 * Уведомление о переходе границы привата: кто владелец, ваша роль и цена,
 * если чанк продаётся. Класс без Bukkit — ключи сообщений собираются здесь
 * и проверяются обычными unit-тестами.
 */
public final class ClaimNotice {

    /** Вход на приват или выход с него. */
    public enum Kind {
        ENTER,
        LEAVE
    }

    /** Кем приходится игрок владельцу привата. */
    public enum Audience {
        /** Это его собственный чанк. */
        OWNER,
        /** Игрок добавлен в участники (роль может быть неполной). */
        MEMBER,
        /** Посторонний — приват чужой. */
        VISITOR
    }

    /**
     * Снимок привата в чанке игрока.
     *
     * <p>{@code x} и {@code z} — координаты <b>чанка</b>: по ним приват ищется
     * в хранилище и сравниваются переходы. Игроку показываются координаты
     * блока в центре этого чанка ({@link #blockX()} / {@link #blockZ()}):
     * они совпадают с F3, с миникартой и с точкой, куда ведёт {@code /claim tp}.</p>
     *
     * @param role {@code null} — игрок не участник (посторонний)
     */
    public record Spot(String world, int x, int z, String owner, int salePrice,
                       Claim.Role role, long paidUntil, String greeting) {

        public Spot {
            world = world == null ? "" : world;
            owner = owner == null ? "" : owner;
            greeting = greeting == null ? "" : greeting;
        }

        public boolean ownedBy(String name) {
            return name != null && !owner.isEmpty() && owner.equalsIgnoreCase(name);
        }

        public boolean forSale() {
            return salePrice > 0;
        }

        /** Кто игрок для этого привата; владелец главнее участника. */
        public Audience audience(String name) {
            if (ownedBy(name)) {
                return Audience.OWNER;
            }
            return role != null ? Audience.MEMBER : Audience.VISITOR;
        }

        /**
         * Координата X блока в центре привата: чанк {@code -139} — это блоки
         * {@code -2224..-2209}, центр {@code -2216}. Именно эти числа видит
         * игрок в уведомлении, а не «номер чанка».
         */
        public int blockX() {
            return ClaimTeleport.centerX(x);
        }

        /** Координата Z блока в центре привата (см. {@link #blockX()}). */
        public int blockZ() {
            return ClaimTeleport.centerZ(z);
        }

        /** Стабильный ключ чанка для кулдауна уведомлений. */
        public String where() {
            return world.toLowerCase(Locale.ROOT) + ':' + x + ':' + z;
        }
    }

    private ClaimNotice() {
    }

    /**
     * Ключ сообщения для перехода.
     *
     * <p>Для посторонних продающийся чанк важнее обычного «чужого»: игрок
     * должен сразу видеть цену и команду покупки. Участник и владелец цену
     * не видят — им важнее роль и дата оплаты.</p>
     */
    public static String key(Kind kind, Audience audience, boolean forSale) {
        String prefix = kind == Kind.ENTER ? "enter-" : "leave-";
        if (audience == Audience.VISITOR && forSale) {
            return prefix + "for-sale";
        }
        return switch (audience) {
            case OWNER -> prefix + "own";
            case MEMBER -> prefix + "member";
            case VISITOR -> prefix + "foreign";
        };
    }
}
