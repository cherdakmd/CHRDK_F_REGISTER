package ru.cherdakmd.chrdkclaims;

import java.util.ArrayList;
import java.util.List;

/**
 * Модель меню приватов (1.4.120): какие приваты показывать на вкладке и в каком
 * порядке. Отделена от Bukkit, чтобы страницы и сортировку можно было проверить
 * обычными тестами — {@link ClaimMenuModelTest}.
 *
 * <p>Порядок как в {@code /claim forsale}: сначала приваты текущего мира, затем
 * ближние — по расстоянию в чанках, потом по миру и координатам. Вкладка
 * {@link Tab#MINE} показывает только собственные чанки игрока, {@link Tab#FOR_SALE} —
 * только выставленные на продажу (включая собственные).</p>
 */
public final class ClaimMenuModel {

    /** Сколько приватов помещается в четыре ряда по семь слотов. */
    public static final int PAGE_SIZE = 28;

    /** Вкладка меню. */
    public enum Tab {
        /** Чанки игрока: сюда попадают только владения. */
        MINE,
        /** Витрина: все приваты с ценой продажи. */
        FOR_SALE;

        /** Вторая вкладка — для кнопки-переключателя. */
        public Tab other() {
            return this == MINE ? FOR_SALE : MINE;
        }
    }

    /** Один приват на вкладке: координаты, владелец, оплата и цена. */
    public record Entry(String world, int x, int z, String owner, long paidUntil,
                        int salePrice, boolean mine) {

        static Entry of(Claim claim, String viewer) {
            return new Entry(claim.world, claim.x, claim.z, claim.owner, claim.paidUntil,
                    claim.salePrice, claim.isOwner(viewer));
        }
    }

    /**
     * Приваты выбранной вкладки без сортировки.
     *
     * @param claims  все приваты, которые видит игрок ({@code ownedSorted} или {@code all})
     * @param viewer  ник игрока для определения «моих»
     * @param tab     вкладка меню
     */
    public static List<Entry> view(List<Claim> claims, String viewer, Tab tab) {
        List<Entry> result = new ArrayList<>();
        if (claims == null || tab == null) {
            return result;
        }
        String name = viewer == null ? "" : viewer;
        for (Claim claim : claims) {
            if (claim == null || claim.world == null || claim.world.isBlank()) {
                continue;
            }
            boolean mine = claim.isOwner(name);
            if (tab == Tab.MINE && !mine) {
                continue;
            }
            if (tab == Tab.FOR_SALE && claim.salePrice <= 0) {
                continue;
            }
            result.add(Entry.of(claim, name));
        }
        return result;
    }

    /** Расстояние до привата в чанках; для другого мира — {@link Long#MAX_VALUE}. */
    public static long distance(Entry entry, String world, int chunkX, int chunkZ) {
        if (entry == null || world == null || !world.equals(entry.world())) {
            return Long.MAX_VALUE;
        }
        long dx = (long) entry.x() - chunkX;
        long dz = (long) entry.z() - chunkZ;
        return dx * dx + dz * dz;
    }

    /** Сортировка витрины: свой мир и ближние чанки первыми, затем по миру и координатам. */
    public static List<Entry> sorted(List<Entry> entries, String world, int chunkX, int chunkZ) {
        List<Entry> copy = new ArrayList<>(entries == null ? List.of() : entries);
        copy.sort((left, right) -> {
            int byWorldHere = Boolean.compare(
                    world != null && world.equals(left.world()),
                    world != null && world.equals(right.world()));
            if (byWorldHere != 0) {
                return -byWorldHere; // true (свой мир) — раньше
            }
            int byDistance = Long.compare(distance(left, world, chunkX, chunkZ),
                    distance(right, world, chunkX, chunkZ));
            if (byDistance != 0) {
                return byDistance;
            }
            int byWorld = left.world().compareTo(right.world());
            if (byWorld != 0) {
                return byWorld;
            }
            int byX = Integer.compare(left.x(), right.x());
            return byX != 0 ? byX : Integer.compare(left.z(), right.z());
        });
        return copy;
    }

    /** Подпись вкладки для заголовка окна (без разметки, если сообщения нет). */
    public static String titleKey(Tab tab) {
        return tab == Tab.FOR_SALE ? "menu-tab-sale" : "menu-tab-mine";
    }

    private ClaimMenuModel() {
    }
}
