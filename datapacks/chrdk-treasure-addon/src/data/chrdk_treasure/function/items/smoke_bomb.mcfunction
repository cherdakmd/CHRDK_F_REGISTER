# Дымовая шашка: ПКМ по блоку — ядовитое облако вокруг.
advancement revoke @s only chrdk_treasure:use_smoke_bomb
execute if predicate chrdk_treasure:holding_smoke_main run item modify entity @s[gamemode=!creative] weapon.mainhand chrdk_treasure:consume_one
execute unless predicate chrdk_treasure:holding_smoke_main if predicate chrdk_treasure:holding_smoke_off run item modify entity @s[gamemode=!creative] weapon.offhand chrdk_treasure:consume_one
effect give @e[distance=..4,type=!minecraft:player] minecraft:poison 5 1
effect give @a[distance=0.5..4] minecraft:poison 5 1
particle minecraft:cloud ~ ~1 ~ 1.5 1 1.5 0.02 60 force
playsound minecraft:entity.witch.throw master @a[distance=..20] ~ ~ ~ 0.8 1.1
title @s actionbar {"text":"Дымовая шашка: ядовитое облако!","color":"aqua"}
