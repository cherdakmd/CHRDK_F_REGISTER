# Сокровищный ролл: advancement добычи камня вызывает эту функцию игроком.
advancement revoke @s only chrdk_treasure:roll_overworld
advancement revoke @s only chrdk_treasure:roll_nether
advancement revoke @s only chrdk_treasure:roll_end
execute if predicate chrdk_treasure:chance_common run function chrdk_treasure:give_common
execute if predicate chrdk_treasure:chance_rare run function chrdk_treasure:give_rare
execute if predicate chrdk_treasure:chance_legendary run function chrdk_treasure:give_legendary
