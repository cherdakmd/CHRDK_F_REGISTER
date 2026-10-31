package ru.cherdakmd.chrdkprofessions;

import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Ageable;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import java.util.BitSet;
import java.util.UUID;

/** Происхождение хранится в chunk/PDC, не исчезает по TTL, смене игрока или рестарту. */
final class ProfessionOrigins implements Listener {
    private final NamespacedKey placed,owner,material,budget,outputOwner,outputType,outputCount;
    ProfessionOrigins(ProfessionsPlugin plugin){
        placed=new NamespacedKey(plugin,"placed-v1");owner=new NamespacedKey(plugin,"station-owner");
        material=new NamespacedKey(plugin,"station-material");budget=new NamespacedKey(plugin,"station-budget");
        outputOwner=new NamespacedKey(plugin,"output-owner");outputType=new NamespacedKey(plugin,"output-type");outputCount=new NamespacedKey(plugin,"output-count");
    }
    private int position(Block b){return (b.getY()-b.getWorld().getMinHeight())*256+(b.getZ()&15)*16+(b.getX()&15);}
    boolean placed(Block b){var data=b.getChunk().getPersistentDataContainer().get(placed,PersistentDataType.BYTE_ARRAY);return data!=null&&BitSet.valueOf(data).get(position(b));}
    void mark(Block b,boolean value){
        var pdc=b.getChunk().getPersistentDataContainer();byte[] data=pdc.get(placed,PersistentDataType.BYTE_ARRAY);
        if(data==null&&!value)return;
        var bits=data==null?new BitSet():BitSet.valueOf(data);bits.set(position(b),value);
        if(bits.isEmpty())pdc.remove(placed);else pdc.set(placed,PersistentDataType.BYTE_ARRAY,bits.toByteArray());
    }
    static boolean mature(Block b){return !(b.getBlockData() instanceof Ageable age)||age.getAge()==age.getMaximumAge();}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void place(BlockPlaceEvent e){mark(e.getBlockPlaced(),true);if(e.getBlockPlaced().getState() instanceof TileState)clear(e.getBlockPlaced());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void multi(BlockMultiPlaceEvent e){for(var b:e.getReplacedBlockStates())mark(b.getBlock(),true);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void form(BlockFormEvent e){mark(e.getBlock(),true);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void extend(BlockPistonExtendEvent e){for(Block b:e.getBlocks()){mark(b,true);mark(b.getRelative(e.getDirection()),true);}}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void retract(BlockPistonRetractEvent e){for(Block b:e.getBlocks()){mark(b,true);mark(b.getRelative(e.getDirection().getOppositeFace()),true);}}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void grow(BlockGrowEvent e){if(e.getNewState().getBlockData() instanceof Ageable age&&age.getAge()==age.getMaximumAge())mark(e.getBlock(),false);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void fertilize(BlockFertilizeEvent e){for(var b:e.getBlocks())if(b.getBlockData() instanceof Ageable age&&age.getAge()==age.getMaximumAge())mark(b.getBlock(),false);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void tree(StructureGrowEvent e){for(var b:e.getBlocks())mark(b.getBlock(),false);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void hopper(InventoryMoveItemEvent e){invalidate(e.getSource());invalidate(e.getDestination());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void falling(org.bukkit.event.entity.EntityChangeBlockEvent e){if(e.getEntity() instanceof org.bukkit.entity.FallingBlock)mark(e.getBlock(),true);}
    private void invalidate(Inventory inventory){if(inventory.getHolder() instanceof TileState state)clear(state.getBlock());}
    void clear(Block block){if(block.getState() instanceof TileState state){var p=state.getPersistentDataContainer();p.remove(owner);p.remove(material);p.remove(budget);p.remove(outputOwner);p.remove(outputType);p.remove(outputCount);state.update(false,false);}}
    /** Только фактическое ручное увеличение входа, не открытие контейнера/добыча результата. */
    void load(Block block,UUID actor,ItemStack before,ItemStack after){
        if(after==null||after.getType().isAir()){clear(block);return;}
        int old=before!=null&&before.isSimilar(after)?before.getAmount():0;
        int added=after.getAmount()-old;
        if(added<=0){if(added<0)clear(block);return;}
        if(!ProfessionWorkshop.ordinary(after)){clear(block);return;}
        if(!(block.getState() instanceof TileState state))return;
        var p=state.getPersistentDataContainer();String previous=p.get(owner,PersistentDataType.STRING);
        if(old>0&&!actor.toString().equals(previous)){clear(block);return;}
        int n=old==0?0:p.getOrDefault(budget,PersistentDataType.INTEGER,0);
        p.set(owner,PersistentDataType.STRING,actor.toString());p.set(material,PersistentDataType.STRING,after.getType().name());
        p.set(budget,PersistentDataType.INTEGER,Math.min(64,n+added));state.update(false,false);
    }
    /** Выход нужен только для прежнего бонуса на ручное извлечение, не на каждую плавку. */
    void output(Block block,UUID actor,org.bukkit.Material type){
        if(!(block.getState() instanceof TileState state))return;var p=state.getPersistentDataContainer();
        boolean same=actor.toString().equals(p.get(outputOwner,PersistentDataType.STRING))&&type.name().equals(p.get(outputType,PersistentDataType.STRING));
        int n=same?p.getOrDefault(outputCount,PersistentDataType.INTEGER,0):0;
        p.set(outputOwner,PersistentDataType.STRING,actor.toString());p.set(outputType,PersistentDataType.STRING,type.name());
        p.set(outputCount,PersistentDataType.INTEGER,Math.min(64,n+1));state.update(false,false);
    }
    boolean extract(Block block,UUID actor,org.bukkit.Material type,int amount){
        if(!(block.getState() instanceof TileState state)||amount<=0)return false;var p=state.getPersistentDataContainer();
        int n=p.getOrDefault(outputCount,PersistentDataType.INTEGER,0);
        boolean own=actor.toString().equals(p.get(outputOwner,PersistentDataType.STRING))&&type.name().equals(p.get(outputType,PersistentDataType.STRING));
        p.set(outputCount,PersistentDataType.INTEGER,Math.max(0,n-amount));state.update(false,false);
        return own&&n>0;
    }
    UUID peek(Block block,ItemStack input){
        if(input==null||!(block.getState() instanceof TileState state))return null;var p=state.getPersistentDataContainer();
        if(p.getOrDefault(budget,PersistentDataType.INTEGER,0)<=0||!input.getType().name().equals(p.get(material,PersistentDataType.STRING)))return null;
        try{String actor=p.get(owner,PersistentDataType.STRING);return actor==null?null:UUID.fromString(actor);}catch(IllegalArgumentException bad){return null;}
    }
    UUID take(Block block,ItemStack input){
        if(input==null||!(block.getState() instanceof TileState state))return null;
        var p=state.getPersistentDataContainer();String who=p.get(owner,PersistentDataType.STRING);
        int left=p.getOrDefault(budget,PersistentDataType.INTEGER,0);
        if(who==null||left<=0||!input.getType().name().equals(p.get(material,PersistentDataType.STRING)))return null;
        try{UUID id=UUID.fromString(who);p.set(budget,PersistentDataType.INTEGER,left-1);state.update(false,false);return id;}
        catch(IllegalArgumentException bad){clear(block);return null;}
    }
}
