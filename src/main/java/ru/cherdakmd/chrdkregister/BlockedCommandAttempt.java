package ru.cherdakmd.chrdkregister;

import java.util.UUID;

/**
 * Одна разрешённая квотой попытка игрока выполнить корень из
 * {@code security.console-only-commands}. Пароли, коды и аргументы команды
 * сюда не попадают: сохраняется только корень, ник, UUID и адрес.
 *
 * <p>Попытки сверх квоты не создают записей — они отменяются молча и учитываются
 * в сводном счётчике {@code /guard}. Это защищает audit/*.jsonl от флуда,
 * не ослабляя сам запрет.
 */
record BlockedCommandAttempt(String player, UUID uuid, String root, String address) { }
