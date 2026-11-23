package ru.cherdakmd.chrdkenchants;

/** Never multiply player inventories, metadata-bearing rewards, or malformed stacks. */
final class PlunderPolicy {
    private PlunderPolicy() { }
    static boolean eligible(boolean playerVictim, boolean foreign, int amount, int max) {
        return !playerVictim && !foreign && max > 0 && amount > 0 && amount <= max;
    }
}
