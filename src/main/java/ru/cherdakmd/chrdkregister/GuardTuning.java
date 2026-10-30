package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.FileConfiguration;

/** Дополнительные ограничения не меняют пароль, права и обязательность второго фактора. */
record GuardTuning(boolean automatic, int attackRejects, int attackAddresses, int attackWindows,
                   int quietSeconds, int attackBurst, int attackPerSecond, int recoveryBurst,
                   int recoveryPerSecond, int reservedSlots, int recentSeconds, int strikeSeconds,
                   int strikes, int cooldownSeconds, int maxCooldownSeconds) {
    static GuardTuning parse(FileConfiguration c) {
        int initial=n(c,"cooldown-seconds",30,1,300);
        return new GuardTuning(c.getBoolean("security.defense.automatic",true),
                n(c,"attack-rejects",30,2,10000),n(c,"attack-addresses",8,2,256),
                n(c,"attack-windows",2,2,6),n(c,"quiet-seconds",60,30,600),
                n(c,"attack-burst",12,1,1000),n(c,"attack-per-second",3,1,100),
                n(c,"recovery-burst",4,1,100),n(c,"recovery-per-second",2,1,20),
                n(c,"reserved-slots",4,0,32),n(c,"recent-seconds",1800,60,3600),
                n(c,"strike-seconds",600,60,3600),n(c,"strikes",3,2,10),initial,
                Math.max(initial,n(c,"max-cooldown-seconds",300,1,1800)));
    }
    private static int n(FileConfiguration c,String key,int value,int min,int max) {
        return Math.clamp(c.getInt("security.defense."+key,value),min,max);
    }
}
