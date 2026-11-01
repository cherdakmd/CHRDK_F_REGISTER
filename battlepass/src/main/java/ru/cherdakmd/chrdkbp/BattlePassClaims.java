package ru.cherdakmd.chrdkbp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

/** Один путь GUI/команд: сначала durable receipt Core, затем совместимая отметка задания. */
final class BattlePassClaims {
    private final BattlePassPlugin plugin;
    BattlePassClaims(BattlePassPlugin plugin){this.plugin=plugin;}

    /** Смена ника/цен наград и рестарт не создают новую выдачу. Новый старт сезона — новая область. */
    static String receipt(long seasonStart,UUID player,String mission,String track) {
        try {
            String identity=seasonStart+"\n"+player+"\n"+mission+"\n"+track;
            return "bp:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    int claimAll(Player player){return claim(player,null,null);}
    boolean claimOne(Player player,String id){return claim(player,id,null)>0;}
    boolean claimTrack(Player player,String id,String track) {
        if(!java.util.Set.of("FREE","VIP","PREMIUM").contains(track))return false;
        return claim(player,id,track)>0;
    }
    private int claim(Player player,String id,String selected) {
        if(!plugin.canUse(player)){plugin.messages().send(player,"access-unavailable");return 0;}
        var season=plugin.season();
        if(season==null||season.empty()){plugin.messages().send(player,"no-season");return 0;}
        var found=id==null?null:BattlePassConfig.find(season,id);
        if(id!=null&&found==null){plugin.messages().send(player,"unknown","id",id);return 0;}
        int rewards=0,hours=0,full=0,locked=0;boolean failed=false;
        UUID uuid=player.getUniqueId();long now=System.currentTimeMillis();
        for(int index=0;index<season.weeks().size()&&!failed;index++) {
            if(!BattlePassConfig.isUnlocked(season,index,now))continue;
            for(var mission:season.weeks().get(index).missions()) {
                if(id!=null&&!mission.id().equals(found.mission().id()))continue;
                if(plugin.store().progressOf(uuid,mission.id())<mission.target())continue;
                String[] tracks={"FREE","VIP","PREMIUM"};
                int[] amounts={mission.freeHours(),mission.vipHours(),mission.premiumHours()};
                boolean[] available={true,plugin.vipActive(player),plugin.premiumActive(player)};
                for(int t=0;t<tracks.length;t++) {
                    if(selected!=null&&!selected.equals(tracks[t]))continue;
                    if(amounts[t]<=0||plugin.store().isClaimed(uuid,mission.id(),tracks[t]))continue;
                    if(!available[t]){locked++;continue;}
                    ChrdkRegisterApi.BoostGrant result;
                    try {
                        result=plugin.coreApi().grantRewardBoostOnce(uuid,player.getName(),amounts[t],receipt(season.startEpochMillis(),uuid,mission.id(),tracks[t]));
                    } catch(RuntimeException | LinkageError error) {
                        plugin.getLogger().log(java.util.logging.Level.SEVERE,"Battlepass: выдача не подтверждена; сохраните данные Core/аддона для сверки",error);
                        failed=true;break;
                    }
                    if(result==ChrdkRegisterApi.BoostGrant.FULL){full++;continue;}
                    if(result!=ChrdkRegisterApi.BoostGrant.ADDED&&result!=ChrdkRegisterApi.BoostGrant.ALREADY){failed=true;break;}
                    plugin.store().markClaimed(uuid,mission.id(),tracks[t]);rewards++;
                    if(result==ChrdkRegisterApi.BoostGrant.ADDED)hours+=amounts[t];
                }
                if(failed)break;
            }
        }
        // Если локальная запись не удалась, Core receipt всё равно запрещает повторное пополнение.
        plugin.store().saveIfDirty();
        if(rewards>0){plugin.messages().send(player,"claim-all-summary","count",rewards,"hours",hours);plugin.fx().claimed(player,hours);}
        if(full>0)plugin.messages().send(player,"reward-bank-full","count",full);
        if(failed)plugin.messages().send(player,"reward-retry");
        if(rewards==0&&full==0&&!failed) {
            if(locked>0)plugin.messages().send(player,"reward-locked","count",locked);
            else if(found!=null && plugin.store().progressOf(uuid,found.mission().id())<found.mission().target())
                plugin.messages().send(player,"not-ready","progress",plugin.store().progressOf(uuid,found.mission().id()),"target",found.mission().target(),"id",found.mission().id(),"desc",BattlePassScreen.described(found.mission()));
            else plugin.messages().send(player,"claim-all-empty");
        }
        return rewards;
    }
}
