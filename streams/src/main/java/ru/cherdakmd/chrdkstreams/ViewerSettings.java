package ru.cherdakmd.chrdkstreams;

import java.util.UUID;

/**
 * Настройки просмотра у стримера: включён ли он и какой код сейчас показан.
 *
 * <p>Код хранится вместе с настройками, чтобы после перезапуска сервера зрители
 * не теряли текущее окно. Когда окно истекает, {@link ViewerService} выдаёт
 * новый код — старый после этого не принимается.</p>
 *
 * @param streamer     стример
 * @param streamerName ник (для сообщений)
 * @param enabled      награды за просмотр включены
 * @param code         текущий код ({@code WATCH-XXXX}); пусто — ещё не выдан
 * @param codeUntil    до какого времени действует код
 */
record ViewerSettings(UUID streamer, String streamerName, boolean enabled,
                      String code, long codeUntil) {

    ViewerSettings {
        streamerName = streamerName == null ? "" : streamerName.trim();
        code = code == null ? "" : code.trim();
    }

    ViewerSettings withCode(String newCode, long untilMillis) {
        return new ViewerSettings(streamer, streamerName, enabled, newCode, untilMillis);
    }

    ViewerSettings renamed(String newName) {
        return new ViewerSettings(streamer, newName, enabled, code, codeUntil);
    }

    ViewerSettings disabled() {
        return new ViewerSettings(streamer, streamerName, false, "", 0L);
    }
}
