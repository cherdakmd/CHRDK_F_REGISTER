package ru.cherdakmd.chrdkguilds;

/** Единая граница закрытого чата, приглашений и GUI; не заменяет клановую должность. */
final class GuildVisibility {
    static boolean allowed(boolean enabled, boolean authenticated, boolean permission) {
        return enabled && authenticated && permission;
    }
    private GuildVisibility() { }
}
