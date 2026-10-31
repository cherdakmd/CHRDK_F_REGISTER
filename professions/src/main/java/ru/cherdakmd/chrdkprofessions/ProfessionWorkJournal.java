package ru.cherdakmd.chrdkprofessions;

import com.google.gson.Gson;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkregister.api.ChargeInventory;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;
import java.nio.file.*;
import java.io.IOException;
import java.util.UUID;

/** WAL одного приёма: план → playerdata → прогресс → удаление. Неизвестный рюкзак не перезаписывается. */
final class ProfessionWorkJournal {
    private static final Gson JSON=new Gson();
    record Entry(int version,String id,String player,String profession,long until,String before,String after){}
    private final ProfessionsPlugin plugin;
    private final Path directory;
    ProfessionWorkJournal(ProfessionsPlugin plugin){this.plugin=plugin;directory=plugin.getDataFolder().toPath().resolve("work-journal");}
    private Path file(UUID id){return directory.resolve(id+".json");}
    boolean pending(UUID id){return Files.exists(file(id));}
    Entry read(UUID id)throws IOException{
        Path file=file(id);if(Files.size(file)>8_000_000)throw new IOException("journal capacity");
        try{
            Entry e=JSON.fromJson(Files.readString(file),Entry.class);
            if(e==null||e.version()!=1||!id.toString().equals(e.player())||Profession.parse(e.profession())==null||e.until()<=0
                    ||e.before()==null||e.after()==null||e.before().equals(e.after()))throw new IllegalArgumentException("journal fields");
            UUID.fromString(e.id());ChargeInventory.decode(e.before());ChargeInventory.decode(e.after());return e;
        }catch(RuntimeException bad){throw new IOException("invalid work journal",bad);}
    }
    boolean apply(Player player,Profession profession,long until,ItemStack[] before,ItemStack[] after){
        UUID id=player.getUniqueId();if(pending(id))return false;
        Entry entry=new Entry(1,UUID.randomUUID().toString(),id.toString(),profession.id(),until,ChargeInventory.encode(before),ChargeInventory.encode(after));
        if(!SafeJsonWriter.write(file(id),JSON.toJson(entry),plugin.getLogger(),"[Profession work]"))return false;
        return recover(player);
    }
    boolean recover(Player player){
        UUID id=player.getUniqueId();if(!pending(id))return true;
        if(!plugin.authenticated(player)||player.getGameMode()!=org.bukkit.GameMode.SURVIVAL)return false;
        try{
            Entry e=read(id);ItemStack[] current=player.getInventory().getStorageContents();
            if(!ChargeInventory.matches(current,e.after())){
                if(plugin.store().receipt(id).equals(e.id())||!ChargeInventory.matches(current,e.before()))return false;
                player.getInventory().setStorageContents(ChargeInventory.decode(e.after()));
            }
            player.saveData();
            plugin.store().completeWork(id,Profession.parse(e.profession()),e.until(),e.id());
            if(!plugin.store().flushDurably())return false;
            Files.delete(file(id));return true;
        }catch(IOException|RuntimeException bad){plugin.getLogger().severe("Мастерская "+id+": "+bad.getMessage());return false;}
    }
    boolean abort(Player player){
        try{
            UUID id=player.getUniqueId();Entry e=read(id);
            if(plugin.store().receipt(id).equals(e.id())||!ChargeInventory.matches(player.getInventory().getStorageContents(),e.before()))return false;
            Files.delete(file(id));return true;
        }catch(IOException|RuntimeException bad){return false;}
    }
    String status(Player player){
        if(!pending(player.getUniqueId()))return "нет незавершённого приёма";
        try{Entry e=read(player.getUniqueId());return "receipt="+e.id()+", profession="+e.profession()+", before="+ChargeInventory.matches(player.getInventory().getStorageContents(),e.before())+", after="+ChargeInventory.matches(player.getInventory().getStorageContents(),e.after())+", committed="+plugin.store().receipt(player.getUniqueId()).equals(e.id());}
        catch(IOException|RuntimeException bad){return "повреждён журнал; файл сохранён: "+bad.getMessage();}
    }
}
