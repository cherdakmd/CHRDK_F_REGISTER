# PvE damage in PvP — work in progress, not a release

Goal: apply the server's player-origin PvE damage mechanics in PvP without bypassing protections or giving PvE rewards for player targets. This is not yet implemented completely or tested on a real server. Do not deploy this branch as a finished PvP update.

## Player's balance decisions (12-question survey)

* Direct skill/build damage bonuses, area ability damage, secondary enchantment damage and lifesteal use 100% of the PvE values. Damaging effects over time keep their PvE strength and duration.
* Apply base strength/cunning in PvP, with neither the mob strong-foe multiplier nor mob-tier pressure.
* Area damage must exclude guildmates and members of the same temporary group. Existing PvP, peace and territory protections remain in force.
* Keep the existing stacking order, defenses, cooldowns and energy costs. Do not add a separate PvP damage cap. This can make high-level PvP damage very high.
* The user reports only CHRDK PvP/territory protections. This is not proof of the complete installed plugin list; other enabled restriction listeners must still be respected.
* Player targets must never grant PvE contribution, combat/archery XP, or mob-specific rewards. Control-only PvE effects are outside the requested damage scope.

These decisions specify the target behavior, not completed implementation or evidence of safety.

## Current partial implementation

`SkillListener` applies the existing combat and archery damage perks, base strength/cunning bonuses and appropriate build-branch multipliers to player targets of player melee and arrows. Players have no mob tier: the strong-mob bonus and tier-pressure penalty are not applied. Existing defenses and the damage event remain in place; these changes do not set or clear cancellation. PvP attacks do not award combat/archery XP. PvP melee lifesteal waits until the next tick and checks the final event cancellation and positive final damage; it still needs real-server verification, including interactions with other plugins and the damage actually accepted by the server. Self attacks, NPCs and worlds with PvP disabled do not receive these skill bonuses.

Cleave, volley and bolt now have a candidate player-damage path: player targets require fresh read-only Claims and Guilds policies, and a real attributed damage event remains visible to other listeners. The new context prevents this ability hit from being mistaken for an ordinary weapon strike by CHRDK Skills and Enchants. Existing mob damage remains unattributed and unchanged. Backstab gives the caster Strength rather than direct area damage. Area lightning, chain lightning and arrow explosions also use collateral checks; for arrows Guilds requires the original projectile's owner and launch-peace markers. Both providers must be present and enabled: missing, unknown, unauthenticated, or unavailable evidence fails closed. Consequently player collateral is unavailable if either updated module is absent or its policy cannot answer. These changes require a coordinated update of Core, Claims, Guilds, Skills and Enchants; do not mix with old JARs. The mob-reward exclusions also require updated BattlePass, Common Goals and Artifacts.

The candidate also excludes player deaths from the BattlePass PvE kill receipt/progress path, Common Goals PvE receipts and artifact mob-drop rolls. This means a custom BattlePass KILL mission explicitly targeting PLAYER will no longer progress; explicitly configured Quests PLAYER targets are not changed. Existing generic kill-trigger enchant/artifact combat effects and the Guilds PvP war score remain separate from mob rewards.

The focused tests use mocks: they check formulas, no combat XP, preflight providers, exclusions, source context and cancellation logic. They are not an integration test of Bukkit/Purpur damage, claims, guilds, other plugins, or client gameplay.

## Known outstanding work and safety gates

* Prove the new attributed ability path on an actual server: defense/retaliation interactions, late event cancellation, PvP world rules, no duplicated modifiers or recursive effects, protection denials and no sanctions for collateral. The capability depends on both updated protection providers. Do not use test hits or cancel-and-undo transactions to query permission: guild listeners can record fines.
* Enchant melee modifiers and bow effects already accept player targets in several paths. Despite the new collateral guard, secondary lightning, chain, explosions and retaliation still need real-server verification of explosion event ordering and knockback, cancellation, attribution, recursion and peaceful-mode penalties. Other plugins' MONITOR handlers may cancel after enchant side effects; a damage event alone is not evidence of safety.
* Mob-tier pressure, mob elite attacks and mob damage mitigation apply to mobs rather than player abilities. Do not treat a player as a tiered mob without an explicit design. Artifact retaliation already accepts player attackers, but its safety and interaction with the new multipliers must be verified.
* The PvE-only effects in `SkillAbilities` and `PveEffectsListener` are documented as control without added damage; they are not part of this damage change. Review other indirect damage sources and independently installed plugins before claiming full coverage.
* Integration tests must cover protected and unprotected claims, peaceful and battle guilds, allies/parties, disabled PvP, NPCs, self-hit, damage cancellation at later priority, ranged and melee sources, secondary/area attacks and no PvE rewards. Include tests for interactions among modules and prevent amplification/recursion. Finally verify with two consenting players on a suitable server before any release claim. Balance may change substantially.
