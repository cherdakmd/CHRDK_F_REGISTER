# Крыло Феникса: ПКМ по блоку — огнестойкость и исцеление.
advancement revoke @s only chrdk_treasure:use_phoenix_wing
execute if predicate chrdk_treasure:holding_wing_main run item modify entity @s[gamemode=!creative] weapon.mainhand chrdk_treasure:consume_one
execute unless predicate chrdk_treasure:holding_wing_main if predicate chrdk_treasure:holding_wing_off run item modify entity @s[gamemode=!creative] weapon.offhand chrdk_treasure:consume_one
effect give @s minecraft:fire_resistance 60 1
effect give @s minecraft:regeneration 10 1
particle minecraft:flame ~ ~1 ~ 0.4 0.5 0.4 0.02 50 force
playsound minecraft:entity.blaze.shoot master @a[distance=..20] ~ ~ ~ 0.7 1.2
title @s actionbar {"text":"Крыло Феникса: пламя больше не страшно.","color":"gold"}
