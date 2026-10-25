package ru.cherdakmd.chrdktreasures;

import java.util.UUID;

/**
 * Кодек индекса бочек (1.4.166): одна бочка — одна строка TSV.
 *
 * <p>Индекс нужен, чтобы срок жизни и лимит бочек переживали перезапуск:
 * содержимое лежит в самой бочке, а здесь хранятся только владелец, редкость,
 * время появления и координаты. Формат нарочно простой — его можно прочитать
 * глазами и поправить руками, а битая строка не ломает остальные: она
 * пропускается с предупреждением.</p>
 *
 * <p>Класс чистый (без Bukkit), поэтому разбор строк проверяется тестами.</p>
 */
final class TreasureBarrelIndex {

    /** Заголовок файла: версия формата, чтобы будущая миграция была явной. */
    static final String HEADER = "# CHRDK_Treasures barrels v1";
    private static final int FIELDS = 8;

    private TreasureBarrelIndex() {
    }

    /** Одна запись индекса: где стоит бочка и чья она. */
    record Entry(UUID world, int x, int y, int z, TreasureBarrel barrel) {
    }

    /** Строка файла: мир, координаты, владелец, ник, редкость и время. */
    static String encode(Entry entry) {
        TreasureBarrel barrel = entry.barrel();
        return entry.world() + "\t" + entry.x() + "\t" + entry.y() + "\t" + entry.z()
                + "\t" + barrel.owner() + "\t" + clean(barrel.ownerName())
                + "\t" + barrel.tier().id() + "\t" + barrel.createdAt();
    }

    /**
     * Разбирает строку файла.
     *
     * @return запись или {@code null}, если строка битая или это комментарий
     */
    static Entry decode(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return null;
        }
        String[] parts = trimmed.split("\t", -1);
        if (parts.length != FIELDS) {
            return null;
        }
        try {
            UUID world = UUID.fromString(parts[0]);
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            UUID owner = UUID.fromString(parts[4]);
            String name = parts[5];
            TreasureTier tier = TreasureTier.byId(parts[6]);
            long created = Long.parseLong(parts[7]);
            return new Entry(world, x, y, z, new TreasureBarrel(owner, name, tier, created));
        } catch (RuntimeException broken) {
            return null;
        }
    }

    /** Табуляция и перевод строки в нике сломали бы формат — заменяем. */
    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replace('\t', '_').replace('\n', '_').replace('\r', '_');
    }
}
