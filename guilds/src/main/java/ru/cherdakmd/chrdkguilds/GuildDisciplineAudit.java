package ru.cherdakmd.chrdkguilds;

/** Ограниченный журнал наказаний и адресных административных исправлений. */
record GuildDisciplineAudit(long at, String actor, String action, String reason) {
    GuildDisciplineAudit {
        if (at < 0 || actor == null || actor.isBlank() || actor.length() > 64
                || action == null || action.isBlank() || action.length() > 32
                || reason == null || reason.isBlank() || reason.length() > 256
                || actor.chars().anyMatch(Character::isISOControl) || reason.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("discipline audit");
    }
}
