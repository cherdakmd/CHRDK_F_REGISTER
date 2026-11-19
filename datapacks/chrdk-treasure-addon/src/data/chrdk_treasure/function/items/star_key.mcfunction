# Звёздный ключ: ПКМ по блоку — тайник с ресурсами и редким сокровищем.
advancement revoke @s only chrdk_treasure:use_star_key
execute if predicate chrdk_treasure:holding_key_main run item modify entity @s[gamemode=!creative] weapon.mainhand chrdk_treasure:consume_one
execute unless predicate chrdk_treasure:holding_key_main if predicate chrdk_treasure:holding_key_off run item modify entity @s[gamemode=!creative] weapon.offhand chrdk_treasure:consume_one
loot give @s loot chrdk_treasure:star_key_cache
playsound minecraft:block.chest.open master @a[distance=..15] ~ ~ ~ 0.8 1.1
playsound minecraft:entity.firework_rocket.large_blast master @a[distance=..25] ~ ~ ~ 0.5 1.67 1
title @s actionbar {"text":"Звёздный ключ открыл тайник!","color":"light_purple"}
