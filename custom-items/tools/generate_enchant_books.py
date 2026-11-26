"""Original book-cover pixel art for CHRDK and snapshot vanilla enchantments.

This only creates assets. Runtime book selection is intentionally not enabled yet.
The individual symbols are hand assigned; tiny corner runes distinguish related
books without writing language-specific text on the covers. Requires Pillow.
"""
import hashlib
import json
import re
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
PACK = ROOT / 'custom-items/resource-pack/assets/chrdk_enchants'
REGISTRY = ROOT / 'enchants/src/main/java/ru/cherdakmd/chrdkenchants/EnchantRegistry.java'
POLICY = ROOT / 'enchants/src/main/java/ru/cherdakmd/chrdkenchants/BookVisualPolicy.java'
# Explicit grouping keeps the motif related to the enchantment rather than its slot.
CUSTOM = {
 'bolt': 'thunderstrike chainspark lightningrod',
 'heart': 'lifesteal vitality',
 'drop': 'bleeding cursed_blood',
 'lock': 'paralyze',
 'sword': 'execution berserk slayer keen_edge frenzy duelist provoking_strike crippling_edge sapping_edge dull_blade clumsy_swing',
 'soul': 'souleater',
 'arrow': 'stormarrow explosive sniper frostarrow tracer headhunter steady_aim quickdraw pinning_shot sapping_shot weak_draw sluggish_shot',
 'shield': 'retribution laststand fortified iceward bulwark second_skin briar_guard faltering_guard signal_guard pve_anchor thin_padding exposed heavy_hull',
 'fire': 'phoenix autosmelt fire_prone',
 'eye': 'truesight seer',
 'boot': 'swiftfeet froggy grounded lightfoot featherstep garden_step quiet_step frost_resistance fragile_bones',
 'pick': 'veinminer excavator midas prospector fortunate careful_work tool_safeguard dull_pick jinxed',
 'chest': 'plunder',
 'book': 'scholar slow_learner',
 'wave': 'air_reserve',
 'leaf': 'harvest_replant',
 'crack': 'brittle',
 'food': 'gluttony',
 'flameboot': 'lavawalker',
}
VANILLA = {
 'wave':'aqua_affinity depth_strider respiration riptide luck_of_the_sea lure',
 'sword':'bane_of_arthropods breach density impaling sharpness smite sweeping_edge',
 'curse':'binding_curse vanishing_curse',
 'shield':'blast_protection fire_protection projectile_protection protection',
 'bolt':'channeling wind_burst',
 'pick':'efficiency fortune silk_touch',
 'boot':'feather_falling frost_walker soul_speed swift_sneak',
 'fire':'fire_aspect flame',
 'arrow':'infinity knockback lunge multishot piercing power punch quick_charge',
 'chest':'looting', 'heart':'loyalty mending unbreaking', 'leaf':'thorns',
}
# 9x9 cover icons, on an intentional common book silhouette.
GLYPHS = {
 'bolt': ['.....##..','....##...','...##....','..######.','.....##..','....##...','...##....','..##.....','.........'],
 'heart':['.##...##.','####.####','#########','#########','.#######.','..#####..','...###...','....#....','.........'],
 'drop':['....#....','...###...','..#####..','..#####..','.#######.','.#######.','..#####..','...###...','.........'],
 'lock':['..#####..','..##.##..','..##.##..','.#######.','.#######.','.###.###.','.###.###.','.#######.','.........'],
 'sword':['......##.','.....###.','....###..','...###...','.######..','...##....','..##.....','.##......','.........'],
 'soul':['...###...','..#####..','.##.#.##.','.#######.','.#######.','..#####..','.##.#.##.','.........','.........'],
 'arrow':['......##.','.....###.','....####.','...##.##.','..##.....','.##......','##.......','.##......','.........'],
 'shield':['.#######.','.#######.','.#######.','.#######.','..#####..','...###...','....#....','.........','.........'],
 'fire':['....#....','...##.#..','..##.##..','.#######.','.#######.','..#####..','...###...','.........','.........'],
 'eye':['.........','...###...','.##...##.','###.#.###','.##...##.','...###...','.........','.........','.........'],
 'boot':['..####...','..####...','..####...','..####...','..######.','..######.','..######.','.........','.........'],
 'pick':['.######..','##..###..','....##...','...##....','..##.....','.##......','##.......','.........','.........'],
 'chest':['.........','.#######.','#########','###...###','#########','#########','.#######.','.........','.........'],
 'book':['.........','.###.###.','.#######.','.#######.','.#######.','.###.###.','.###.###.','.........','.........'],
 'wave':['.........','##..##..#','.####.##.','##..##..#','.####.##.','##..##..#','.####.##.','.........','.........'],
 'leaf':['......##.','....####.','..######.','.######..','.#####...','..###....','.##......','.........','.........'],
 'crack':['...#.....','..##.....','..##.....','...###...','....##...','...##....','..##.....','.........','.........'],
 'food':['..#####..','.#######.','.#######.','..#####..','...###...','...###...','...###...','.........','.........'],
 'flameboot':['..####...','..####.#.','..####.#.','..####.##','..######.','..######.','..######.','.........','.........'],
 'curse':['##.....##','.##...##.','..##.##..','...###...','..##.##..','.##...##.','##.....##','.........','.........'],
 'mix':['.##...##.','####.####','#########','.#######.','..#####..','...###...','....#....','.........','.........'],
}
PALETTE = {
 'bolt':'#ffda67','heart':'#f28d9c','drop':'#ed7781','lock':'#c3a6df','sword':'#e7b5a2','soul':'#96cdda',
 'arrow':'#a8d2ed','shield':'#b7c5ef','fire':'#f5a769','eye':'#ceb7ee','boot':'#a4d6ca','pick':'#d5be92',
 'chest':'#f2cd7c','book':'#e3d5ae','wave':'#83d1e1','leaf':'#a3d18a','crack':'#c9b4ae','food':'#eebf91',
 'flameboot':'#f3a16b','curse':'#e28aa7','mix':'#c9b9ec',
}


def flatten(groups):
    out = {}
    for motif, ids in groups.items():
        for name in ids.split():
            if name in out: raise ValueError('duplicate art assignment: ' + name)
            out[name] = motif
    return out


def render(name, motif, rarity, cursed):
    im = Image.new('RGBA',(32,32)); d = ImageDraw.Draw(im)
    edge = {'COMMON':'#bdb5a9','RARE':'#7cbfd8','EPIC':'#be9dde','LEGENDARY':'#ebc874'}[rarity]
    cover = '#54364e' if cursed else '#304458'
    d.polygon([(5,7),(21,5),(27,9),(27,24),(11,27),(5,23)], fill='#22202d')
    d.polygon([(6,7),(21,5),(26,9),(26,22),(10,25),(6,22)], fill=cover, outline=edge)
    d.polygon([(8,23),(11,26),(25,23),(25,21),(11,24)], fill='#e9d9bb')
    d.line((10,9,10,23),fill=edge,width=1)
    color = PALETTE[motif]
    # Main pictogram and highlight on the book cover.
    for y,row in enumerate(GLYPHS[motif]):
        for x,cell in enumerate(row):
            if cell == '#': d.point((12+x,10+y),fill=color)
    d.line((12,20,21,20),fill=edge,width=1)
    # A deterministic 12-bit ornamental rune, not a classification by hash.
    bits = int.from_bytes(hashlib.sha256(name.encode()).digest()[:2], 'big')
    for i in range(12):
        if bits & (1 << i): d.point((12+i%6*2,22+i//6),fill=color)
    return im


def save(path, image):
    model = 'book/' + path
    target = PACK / 'textures/item' / (model + '.png'); target.parent.mkdir(parents=True,exist_ok=True); image.save(target)
    for folder,data in [('models/item',{'parent':'minecraft:item/generated','textures':{'layer0':'chrdk_enchants:item/'+model}}),
                        ('items',{'model':{'type':'minecraft:model','model':'chrdk_enchants:item/'+model}})]:
        target = PACK / folder / (model+'.json'); target.parent.mkdir(parents=True,exist_ok=True)
        target.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')


custom = flatten(CUSTOM); vanilla = flatten(VANILLA)
entries = re.findall(r'register\("([a-z0-9_]+)",\s*(COMMON|RARE|EPIC|LEGENDARY),\s*\d+,\s*(?:null|"[^"]+"),\s*(LIGHT|CURSE)', REGISTRY.read_text())
assert len(entries) == 75 and set(custom) == {e[0] for e in entries} | {'lavawalker'}
source = POLICY.read_text(); listed = set(re.findall(r'"([a-z_]+)"',source.split('static final Set<String> VANILLA = Set.of(',1)[1].split(');',1)[0]))
assert len(vanilla) == 43 and set(vanilla) == listed
for name, rarity, polarity in entries:
    save('custom/'+name, render('custom/'+name,custom[name],rarity,polarity == 'CURSE'))
save('custom/lavawalker',render('custom/lavawalker',custom['lavawalker'],'LEGENDARY',False))
for name, motif in vanilla.items():
    save('vanilla/'+name,render('vanilla/'+name,motif,'RARE',name.endswith('_curse')))
for name in ('custom','vanilla','both'):
    save('mixed/'+name,render('mixed/'+name,'mix','EPIC',False))
print('Generated 76 custom/legacy, 43 snapshot vanilla and 3 mixed book models')
