package ru.cherdakmd.chrdkguilds;

/** Долг и граница инцидента остаются у UUID после выхода/роспуска клана. */
record GuildSanction(long debt, long nextIncidentAt) {
    GuildSanction { if (debt < 0 || nextIncidentAt < 0) throw new IllegalArgumentException("sanction"); }
}
