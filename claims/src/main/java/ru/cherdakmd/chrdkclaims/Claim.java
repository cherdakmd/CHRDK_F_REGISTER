package ru.cherdakmd.chrdkclaims;

import java.util.ArrayList;
import java.util.List;

/** Приват: чанк (world + x/z), владелец, участники, оплата и выставленная цена. */
public final class Claim {

    public final String world;
    public final int x;
    public final int z;
    public String owner;
    public final List<String> members = new ArrayList<>();
    /** Момент, до которого оплачена рента (millis). */
    public long paidUntil;
    /** Сколько суток подряд рента не была списана за нехваткой казны. */
    public int debtDays;
    /** Цена продажи игрокам (0 — не продаётся). */
    public int salePrice;

    public Claim(String world, int x, int z, String owner, long paidUntil) {
        this.world = world;
        this.x = x;
        this.z = z;
        this.owner = owner;
        this.paidUntil = paidUntil;
    }

    public long key() {
        return ClaimMath.chunkKey(x, z);
    }

    public boolean isOwner(String name) {
        return owner.equalsIgnoreCase(name);
    }

    public boolean canBuild(String name) {
        return isOwner(name) || members.stream().anyMatch(m -> m.equalsIgnoreCase(name));
    }

    public String where() {
        return world + " [" + x + ", " + z + "]";
    }
}
