package ru.cherdakmd.chrdkclaims;

/** Изменение на отделённом финансовом снимке: только собственник и вся его территория. */
final class ClaimClanSharing {
    static boolean set(ClaimStore store, String world, int x, int z, String owner, String id, Claim.Role role) {
        Claim target = store.claimAt(world, x, z);
        if (owner == null || target == null || !target.isOwner(owner)) return false;
        if (role != null && (id == null || id.isBlank() || id.length() > 128)) return false;
        for (Claim part : store.territory(target)) {
            if (role == null) part.clearClanSharing(); else part.shareClan(id, role);
        }
        store.markDirty();
        return true;
    }
    private ClaimClanSharing() { }
}
