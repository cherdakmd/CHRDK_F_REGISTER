package ru.cherdakmd.chrdkanticheat;

/**
 * Одна запись журнала доказательств античита ({@code flags.jsonl}).
 *
 * @param id      сквозной идентификатор, назначает журнал при записи
 * @param time    момент срабатывания, миллисекунды UNIX
 * @param player  ник нарушителя на момент срабатывания
 * @param uuid    UUID нарушителя, если он был онлайн
 * @param check   имя проверки детектора, как её прислал мост
 * @param vl      уровень нарушения (violation level)
 * @param ping    пинг игрока, мс; −1 если офлайн
 * @param tps     TPS сервера в момент срабатывания
 * @param world   мир; пусто, если игрок был офлайн
 * @param x       координата X
 * @param y       координата Y
 * @param z       координата Z
 * @param verbose детали детектора (хвост команды), может быть пустым
 */
record FlagRecord(long id, long time, String player, String uuid, String check,
                  int vl, int ping, double tps, String world,
                  double x, double y, double z, String verbose) {

    /** Копия записи с назначенным идентификатором. */
    FlagRecord withId(long newId) {
        return new FlagRecord(newId, time, player, uuid, check, vl, ping, tps,
                world, x, y, z, verbose);
    }
}
