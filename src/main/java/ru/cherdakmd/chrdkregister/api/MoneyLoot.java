package ru.cherdakmd.chrdkregister.api;

import java.util.*;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.Messages;

/** API 25: custom personal money loot, never physical diamonds. Event IDs are immutable award keys. */
public final class MoneyLoot implements AutoCloseable {
    private final MilestoneAwards awards;
    public MoneyLoot(JavaPlugin plugin,Supplier<ChrdkRegisterApi> api,Supplier<Messages> messages,String source) {
        try{awards=new MilestoneAwards(plugin,api,messages,source,Map.of());}catch(Exception e){throw new IllegalStateException("Денежный источник "+source+" недоступен; не удаляйте milestone-awards.json",e);}
    }
    public static int unit(String material){return switch(material){case "DIAMOND"->1;case "DIAMOND_BLOCK"->9;default->0;};}
    public boolean accept(Player owner,String event,long amount){if(amount==0)return true;if(amount<0||!awards.reserve(owner,event,amount))return false;awards.tick();return true;}
    @Override public void close(){awards.close();}
}
