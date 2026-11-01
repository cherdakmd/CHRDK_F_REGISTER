package ru.cherdakmd.chrdkguilds;

import java.util.ArrayList;
import java.util.List;

/** Денежный долг, доказательства и клеймо принадлежат UUID, а не составу клана. */
record GuildSanction(long debt, long nextIncidentAt, List<GuildViolation> incidents,
                     long brandUntil, long cycleStartedAt, List<GuildDisciplineAudit> audit) {
    static final long WINDOW = 30 * GuildPeaceTerm.DAY;
    static final long BRAND = 7 * GuildPeaceTerm.DAY;
    static final int LIMIT = 10;
    // Не более одного события в минуту за 30 суток; +2 для точных границ окна.
    static final int MAX_INCIDENTS = 43_202;
    static final int MAX_AUDIT = 64;
    GuildSanction(long debt,long nextIncidentAt) { this(debt,nextIncidentAt,List.of(),0,0,List.of()); }
    GuildSanction {
        incidents=List.copyOf(incidents);audit=List.copyOf(audit);
        if (debt < 0 || nextIncidentAt < 0 || brandUntil < 0 || cycleStartedAt < 0
                || incidents.size()>MAX_INCIDENTS || audit.size()>MAX_AUDIT)
            throw new IllegalArgumentException("sanction");
    }
    boolean branded(long now) { return now < brandUntil; }
    int violations(long now) {
        return (int)incidents.stream().filter(v->v.peaceful()&&!v.forgiven()
                && v.at()>now-WINDOW && v.at()>=cycleStartedAt && v.at()<=now).count();
    }
    boolean seen(java.util.UUID source) { return incidents.stream().anyMatch(v->v.source().equals(source)); }
    GuildSanction debt(long value) { return new GuildSanction(value,nextIncidentAt,incidents,brandUntil,cycleStartedAt,audit); }
    GuildSanction incident(GuildViolation event,long next) {
        var rows=new ArrayList<>(incidents.stream().filter(v->v.at()>event.at()-WINDOW).toList());
        rows.add(event);
        return new GuildSanction(Math.addExact(debt,GuildPeaceService.FINE),next,rows,brandUntil,cycleStartedAt,audit);
    }
    GuildSanction brand(long now,String reason) {
        long until=Math.addExact(now,BRAND);
        return new GuildSanction(debt,nextIncidentAt,incidents,until,until,audit)
                .log(now,"system","brand",reason);
    }
    GuildSanction log(long now,String actor,String action,String reason) {
        var rows=new ArrayList<>(audit);
        if(rows.size()==MAX_AUDIT)rows.removeFirst();
        rows.add(new GuildDisciplineAudit(now,actor,action,reason));
        return new GuildSanction(debt,nextIncidentAt,incidents,brandUntil,cycleStartedAt,rows);
    }
    GuildSanction forgive(java.util.UUID id,long now,String actor,String reason) {
        var rows=incidents.stream().map(v->v.id().equals(id)?v.forgive():v).toList();
        return new GuildSanction(debt,nextIncidentAt,rows,brandUntil,cycleStartedAt,audit)
                .log(now,actor,"forgive",id+": "+reason);
    }
    GuildSanction unbrand(long now,String actor,String reason) {
        // Амнистия начинает новый цикл, но не стирает доказательства и денежный долг.
        return new GuildSanction(debt,nextIncidentAt,incidents,0,now,audit).log(now,actor,"unbrand",reason);
    }
}
