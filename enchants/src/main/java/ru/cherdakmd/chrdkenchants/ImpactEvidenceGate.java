package ru.cherdakmd.chrdkenchants;

import java.util.*;
import java.util.function.BooleanSupplier;

/** One-tick bounded correlation. Collision alone never authorizes an entity impact. */
final class ImpactEvidenceGate<K> {
    interface Handle { void cancel(); }
    interface Scheduler { Handle submit(Runnable task); }
    private static final class Entry {
        final boolean damageRequired; Handle handle; BooleanSupplier damage,collision; Runnable action; boolean ambiguous;
        Entry(boolean damageRequired){this.damageRequired=damageRequired;}
    }
    private final Scheduler scheduler; private final int limit; private final Map<K,Entry> pending=new HashMap<>();
    ImpactEvidenceGate(Scheduler scheduler,int limit){this.scheduler=Objects.requireNonNull(scheduler);if(limit<1)throw new IllegalArgumentException("limit");this.limit=limit;}
    private Entry obtain(K key,boolean damageRequired){
        Objects.requireNonNull(key);var entry=pending.get(key);
        if(entry!=null){if(entry.damageRequired!=damageRequired)entry.ambiguous=true;return entry;}
        if(pending.size()>=limit)return null;
        entry=new Entry(damageRequired);pending.put(key,entry);var ticket=entry;
        try {entry.handle=scheduler.submit(()->finish(key,ticket));}
        catch(RuntimeException|Error error){pending.remove(key,ticket);throw error;}
        return entry;
    }
    boolean damage(K key,BooleanSupplier accepted){
        Objects.requireNonNull(accepted);var e=obtain(key,true);if(e==null)return false;
        if(e.damage!=null)e.ambiguous=true;else e.damage=accepted;return !e.ambiguous;
    }
    boolean collision(K key,boolean damageRequired,BooleanSupplier accepted,Runnable action){
        Objects.requireNonNull(accepted);Objects.requireNonNull(action);var e=obtain(key,damageRequired);if(e==null)return false;
        if(e.collision!=null)e.ambiguous=true;else {e.collision=accepted;e.action=action;}return !e.ambiguous;
    }
    private void finish(K key,Entry ticket){
        if(!pending.remove(key,ticket)||ticket.ambiguous||ticket.collision==null||ticket.action==null)return;
        if(ticket.damageRequired&&(ticket.damage==null||!ticket.damage.getAsBoolean()))return;
        if(ticket.collision.getAsBoolean())ticket.action.run();
    }
    void clear(){var entries=List.copyOf(pending.values());pending.clear();for(var e:entries)if(e.handle!=null)e.handle.cancel();}
    int size(){return pending.size();}
}
