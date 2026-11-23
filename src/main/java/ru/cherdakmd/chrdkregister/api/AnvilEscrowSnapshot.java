package ru.cherdakmd.chrdkregister.api;

import org.bukkit.inventory.ItemStack;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, bounded journal payload for one partial anvil operation. Item
 * bytes are Paper's full item serialization, including PDC and metadata; the
 * journal must not reconstruct items from enchantment names. This snapshot is
 * not proof of escrow or payment and never authorizes delivery by itself.
 *
 * Player state is deliberately opaque to this codec: an integration MUST use a
 * versioned adapter that covers its entire destination inventory and witness.
 * Cursor and window slots are not part of that snapshot automatically.
 */
public final class AnvilEscrowSnapshot {
    private static final int VERSION = 1, MAX_ITEM = 256 * 1024, MAX_STATE = 1024 * 1024;
    private static final int MAX_PAYLOAD_BYTES = 3 * 1024 * 1024 - 32;
    private static final int DIGEST = 32;
    public record Experience(int level, int total, float progress) {
        public Experience {
            if (level < 0 || total < 0 || !Float.isFinite(progress) || progress < 0 || progress >= 1)
                throw new IllegalArgumentException("experience snapshot");
        }
    }
    private final UUID operation, owner;
    private final int levelCost;
    private final Experience beforeXp, afterXp;
    private final byte[] left, book, result, remainder, beforeState, afterState;

    public AnvilEscrowSnapshot(UUID operation, UUID owner, int levelCost, Experience beforeXp, Experience afterXp,
            byte[] left, byte[] book, byte[] result, byte[] remainder, byte[] beforeState, byte[] afterState) {
        this.operation=Objects.requireNonNull(operation); this.owner=Objects.requireNonNull(owner);
        if (levelCost < 0) throw new IllegalArgumentException("negative level cost");
        this.levelCost=levelCost; this.beforeXp=Objects.requireNonNull(beforeXp); this.afterXp=Objects.requireNonNull(afterXp);
        this.left=copy(left,MAX_ITEM); this.book=copy(book,MAX_ITEM); this.result=copy(result,MAX_ITEM);
        this.remainder=copy(remainder,MAX_ITEM); this.beforeState=copy(beforeState,MAX_STATE); this.afterState=copy(afterState,MAX_STATE);
        if (Arrays.equals(this.beforeState,this.afterState) && beforeXp.equals(afterXp))
            throw new IllegalArgumentException("unchanged player state");
    }
    public UUID operation() { return operation; }
    public UUID owner() { return owner; }
    public int levelCost() { return levelCost; }
    public Experience beforeXp() { return beforeXp; }
    public Experience afterXp() { return afterXp; }
    public byte[] left() { return left.clone(); }
    public byte[] book() { return book.clone(); }
    public byte[] result() { return result.clone(); }
    public byte[] remainder() { return remainder.clone(); }
    public byte[] beforeState() { return beforeState.clone(); }
    public byte[] afterState() { return afterState.clone(); }
    private static byte[] copy(byte[] value, int max) {
        if(value==null||value.length==0||value.length>max) throw new IllegalArgumentException("anvil snapshot field");
        return value.clone();
    }
    /** Serialize one complete stack; the partial operation only accepts single items. */
    public static byte[] itemBytes(ItemStack item) {
        if (item==null || item.isEmpty() || item.getAmount()!=1) throw new IllegalArgumentException("anvil item");
        return copy(item.serializeAsBytes(),MAX_ITEM);
    }
    /** Deserialize for a recovery adapter; still requires transaction proof before issuing. */
    public static ItemStack item(byte[] data) {
        var item=ItemStack.deserializeBytes(copy(data,MAX_ITEM));
        if(item==null||item.isEmpty()||item.getAmount()!=1)throw new IllegalArgumentException("invalid anvil item");
        return item;
    }
    public String encode() {
        try {
            var buffer=new ByteArrayOutputStream();
            try(var out=new DataOutputStream(buffer)) {
                out.writeInt(VERSION); writeUuid(out,operation); writeUuid(out,owner); out.writeInt(levelCost);
                writeXp(out,beforeXp);writeXp(out,afterXp);
                writeField(out,left);writeField(out,book);writeField(out,result);writeField(out,remainder);
                writeField(out,beforeState);writeField(out,afterState);
            }
            byte[] body=buffer.toByteArray();
            if(body.length>MAX_PAYLOAD_BYTES)throw new IllegalArgumentException("anvil snapshot capacity");
            buffer.write(hash(body));
            return Base64.getEncoder().encodeToString(buffer.toByteArray());
        } catch(IOException impossible) { throw new IllegalStateException(impossible); }
    }
    public static AnvilEscrowSnapshot decode(String encoded) {
        if(encoded==null||encoded.length()>4*1024*1024)throw new IllegalArgumentException("anvil snapshot capacity");
        try {
            byte[] all=Base64.getDecoder().decode(encoded);
            if(all.length<=DIGEST || all.length>MAX_PAYLOAD_BYTES+DIGEST)throw new IllegalArgumentException("anvil snapshot length");
            byte[] body=Arrays.copyOf(all,all.length-DIGEST), digest=Arrays.copyOfRange(all,all.length-DIGEST,all.length);
            if(!MessageDigest.isEqual(hash(body),digest))throw new IllegalArgumentException("anvil snapshot checksum");
            try(var in=new DataInputStream(new ByteArrayInputStream(body))) {
                if(in.readInt()!=VERSION)throw new IllegalArgumentException("anvil snapshot version");
                UUID operation=readUuid(in),owner=readUuid(in);int cost=in.readInt();
                Experience before=readXp(in),after=readXp(in);
                var value=new AnvilEscrowSnapshot(operation,owner,cost,before,after,readField(in,MAX_ITEM),readField(in,MAX_ITEM),
                        readField(in,MAX_ITEM),readField(in,MAX_ITEM),readField(in,MAX_STATE),readField(in,MAX_STATE));
                if(in.read()!=-1)throw new IllegalArgumentException("trailing anvil snapshot data");
                return value;
            }
        } catch(IOException failure) { throw new IllegalArgumentException("invalid anvil snapshot",failure); }
    }
    private static void writeUuid(DataOutputStream out,UUID id)throws IOException {out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());}
    private static UUID readUuid(DataInputStream in)throws IOException {return new UUID(in.readLong(),in.readLong());}
    private static void writeXp(DataOutputStream out,Experience xp)throws IOException {out.writeInt(xp.level());out.writeInt(xp.total());out.writeFloat(xp.progress());}
    private static Experience readXp(DataInputStream in)throws IOException {return new Experience(in.readInt(),in.readInt(),in.readFloat());}
    private static void writeField(DataOutputStream out,byte[] value)throws IOException {out.writeInt(value.length);out.write(value);}
    private static byte[] readField(DataInputStream in,int max)throws IOException {
        int size=in.readInt();if(size<=0||size>max||size>in.available())throw new IllegalArgumentException("invalid anvil snapshot field");
        return in.readNBytes(size);
    }
    private static byte[] hash(byte[] value) {
        try {return MessageDigest.getInstance("SHA-256").digest(value);}
        catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
}
