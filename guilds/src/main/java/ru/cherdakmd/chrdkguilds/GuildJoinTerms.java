package ru.cherdakmd.chrdkguilds;

import java.util.Objects;
import java.util.UUID;

/** Только условия конкретного вступления: чужая казна/тик взносов не сбрасывают согласие. */
record GuildJoinTerms(UUID player, String guild, String name, String tag, int dues, GuildMode mode,
                      long duesChanged, long modeChanged, boolean open, long revision, GuildInvite invitation) {
    static GuildJoinTerms capture(GuildStore store, UUID player, String guildId, long now) {
        Guild guild = store.guild(guildId);
        if (guild == null) return null;
        GuildInvite invite = store.inviteOf(player);
        if (invite == null || !invite.guildId().equals(guildId) || invite.expired(now)) invite = null;
        if (!guild.recruitmentOpen() && invite == null) return null;
        return new GuildJoinTerms(player,guild.id(),guild.name(),guild.tag(),guild.duesRate(),guild.mode(),
                guild.duesChangedAt(),guild.modeChangedAt(),guild.recruitmentOpen(),guild.recruitmentRevision(),invite);
    }
    long fee() { return (long) dues + (mode == GuildMode.PEACEFUL ? GuildPeaceService.DAILY : 0); }
    boolean matches(GuildStore store, UUID player, long now) {
        return Objects.equals(this, capture(store,player,guild,now));
    }
}
