"""Recreate the species pixel art and item definitions. Requires Pillow.

The catalog remains authoritative. A fixed digest seeds decorative details, so
running the script twice yields identical PNGs, regardless of Python hash seed.
The six original rarity models are intentionally retained for old fish items.
"""
import colorsys
import hashlib
import json
import random
import re
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'resource-pack/assets/chrdk_fishing'
source = (ROOT / 'src/main/java/ru/cherdakmd/chrdkfishing/FishCatalog.java').read_text(encoding='utf-8')
base = re.findall(r'fish\("([a-z_]+)","[^"]+",Rarity\.([A-Z_]+),(true|false),', source)
premium = re.findall(r'new Premium\("([a-z_]+)", "[^"]+", "([a-z_]+)"\)', source)
assert len(base) == 45 and len(premium) == 47

# Hand-chosen body family and colour (hue, saturation, value); variations
# include differently shaped fins, scales, stripes, spots and highlights.
COLORS = {
'ruffe': (0.15,.48,.55), 'bleak': (.55,.16,.76), 'roach':(.57,.18,.69),
'perch':(.24,.55,.61), 'silver_bream':(.56,.15,.77), 'crucian_carp':(.14,.42,.67),
'gudgeon':(.11,.37,.60), 'bream':(.12,.34,.67), 'ide':(.10,.23,.72),
'pike':(.26,.48,.50), 'burbot':(.10,.44,.45), 'vendace':(.55,.21,.74),
'smelt':(.48,.20,.70), 'dace':(.58,.20,.68), 'tench':(.23,.49,.48),
'whitefish':(.56,.19,.79), 'grayling':(.65,.32,.70), 'trout':(.10,.34,.66),
'brown_trout':(.08,.49,.60), 'palia':(.07,.55,.65), 'zander':(.19,.37,.65),
'moon_perch':(.70,.50,.67), 'ladoga_lanternfish':(.48,.63,.65),
'lake_salmon':(.05,.42,.72), 'atlantic_salmon':(.04,.49,.77), 'arctic_char':(.03,.59,.73),
'sturgeon':(.58,.30,.48), 'northern_crystal':(.52,.62,.79), 'shadow_burbot':(.72,.48,.40),
'willow_spirit':(.34,.50,.67), 'rudd':(.08,.38,.73), 'minnow':(.20,.29,.59),
'rotan':(.10,.37,.46), 'carp':(.12,.45,.62), 'chub':(.20,.25,.66),
'sichel':(.56,.18,.82), 'asp':(.59,.23,.68), 'eel':(.13,.46,.40),
'sterlet':(.52,.32,.55), 'arctic_cisco':(.58,.22,.76), 'taimen':(.06,.42,.60),
'nelma':(.52,.21,.76), 'arctic_whitefish':(.55,.14,.80),
'golden_tench':(.12,.70,.84), 'aurora_salmon':(.48,.68,.78),
}
assert set(COLORS) == {v[0] for v in base}
# Bespoke palettes and motifs for the additional expensive fish. Terraria-inspired
# names are rendered as original pixel art; no Terraria image assets are copied.
SPECIAL = {
    'diamond_fish': (.52, .75, .98, 'gem'),
    'gold_fish': (.12, .88, .98, 'metal'),
    'silver_fish': (.56, .12, .88, 'metal'),
    'platinum_fish': (.57, .18, .98, 'metal'),
    'palladium_fish': (.09, .62, .85, 'metal'),
    'rhodium_fish': (.76, .27, .92, 'metal'),
    'terraria_armored_cavefish': (.57, .28, .50, 'armor'),
    'terraria_chaos_fish': (.83, .75, .85, 'gem'),
    'terraria_ebonkoi': (.72, .70, .43, 'stripe'),
    'terraria_hemopiranha': (.99, .87, .78, 'stripe'),
    'terraria_frost_minnow': (.52, .52, .90, 'gem'),
    'terraria_prismite': (.80, .56, .93, 'gem'),
    'terraria_neon_tetra': (.55, .90, .90, 'stripe'),
    'terraria_obsidifish': (.74, .35, .30, 'armor'),
    'terraria_flarefin_koi': (.04, .93, .96, 'stripe'),
    'terraria_princess_fish': (.90, .57, .96, 'crown'),
    'terraria_crimson_tigerfish': (.01, .83, .72, 'stripe'),
    'terraria_specular_fish': (.56, .13, .98, 'gem'),
    'terraria_variegated_lardfish': (.25, .72, .72, 'stripe'),
    'terraria_honeyfin': (.12, .92, .96, 'stripe'),
    'terraria_damselfish': (.58, .68, .95, 'gem'),
    'terraria_double_cod': (.08, .50, .72, 'double'),
}
assert set(SPECIAL) <= {v[0] for v in premium}
THIN = {'pike', 'eel', 'burbot', 'shadow_burbot', 'sichel', 'smelt', 'dace', 'gudgeon', 'minnow'}
DEEP = {'crucian_carp', 'carp', 'bream', 'silver_bream', 'ide', 'tench', 'golden_tench'}
SPIKY = {'perch', 'ruffe', 'zander', 'moon_perch'}
SPOTTED = {'trout', 'brown_trout', 'palia', 'lake_salmon', 'atlantic_salmon', 'arctic_char', 'taimen', 'burbot', 'shadow_burbot'}
ARMORED = {'sturgeon', 'sterlet'}
FANTASY = {ident for ident, _, fictional in base if fictional == 'true'}

def rgb(h, s, v):
    return tuple(round(255 * x) for x in colorsys.hsv_to_rgb(h % 1, min(1,max(0,s)), min(1,max(0,v))))

def save_json(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')

def draw_fish(ident, ref=None):
    seed = int.from_bytes(hashlib.sha256(ident.encode()).digest()[:8], 'big')
    rng = random.Random(seed)
    original = ref or ident
    h, s, v = COLORS[original]
    fantasy = bool(ref) or ident in FANTASY
    if ref:
        # Spectral premium colour, still retaining recognisable reference shape.
        h = (h + .18 + rng.randrange(10) / 100) % 1
        s, v = .50 + rng.randrange(20)/100, .72 + rng.randrange(14)/100
    if ident in SPECIAL:
        h, s, v, _ = SPECIAL[ident]
    body = rgb(h,s,v); edge = rgb(h, min(1,s+.18), v*.38)
    fin = rgb(h + (.06 if fantasy else -.025), min(1,s+.12), v*.68)
    belly = rgb(h, s*.46, min(1,v+.22)); shine = rgb(h, s*.25, min(1,v+.38))
    mark = rgb(h + (.13 if fantasy else 0), min(1,s+.10), v*.55)
    im = Image.new('RGBA',(32,32)); d = ImageDraw.Draw(im)
    upper, lower = (13,19) if original in THIN else (9,23) if original in DEEP else (11,21)
    # Tail and fins are behind the main body.
    d.polygon([(9,15),(3,upper-2),(4,16),(3,lower+2),(9,18)], fill=fin, outline=edge)
    if original in SPIKY:
        d.polygon([(11,upper+1),(12,upper-3),(14,upper),(16,upper-4),(18,upper),(20,upper-2),(22,upper+2)],fill=fin,outline=edge)
    elif original == 'grayling':
        d.polygon([(11,upper+1),(12,upper-5),(19,upper-5),(22,upper+2)],fill=fin,outline=edge)
    else:
        d.polygon([(12,upper+2),(15,upper-3),(20,upper+1)],fill=fin,outline=edge)
    d.polygon([(13,lower-1),(16,lower+4),(20,lower)],fill=fin,outline=edge)
    # Individual fish silhouette: slim, deep-bodied, pointed sturgeon or standard.
    if original in ARMORED:
        points=[(7,15),(11,upper+1),(19,upper),(24,upper+2),(30,15),(24,lower-2),(18,lower),(11,lower-1),(7,18)]
    else:
        points=[(7,15),(11,upper+1),(16,upper),(22,upper+1),(27,upper+3),(29,15),(28,19),(24,lower-1),(17,lower),(11,lower-1),(7,18)]
    d.polygon(points, fill=body, outline=edge)
    # Clip the scales and belly to the body, with no anti-aliased edges.
    mask=Image.new('1',(32,32)); ImageDraw.Draw(mask).polygon(points,fill=1)
    for y in range(upper+1,lower):
        for x in range(9,26):
            if not mask.getpixel((x,y)): continue
            if y >= lower-3: im.putpixel((x,y),belly+(255,))
            elif original in SPIKY and (x + rng.randrange(2)) % 5 in (0,1) and y < lower-3:
                im.putpixel((x,y),mark+(255,))
            elif original in SPOTTED and (x*7+y*11+seed)%13 < 2:
                im.putpixel((x,y),mark+(255,))
            elif original in ARMORED and y in (upper+2, lower-4) and x%4==0:
                im.putpixel((x,y),shine+(255,))
            elif (x + 2*y + seed)%11 == 0:
                im.putpixel((x,y),belly+(255,))
    d.line([(12,upper+2),(20,upper+2)],fill=shine,width=1)
    d.polygon([(19,16),(15,20),(19,19)],fill=fin,outline=edge)
    d.point((25,14),fill=(18,25,30)); d.point((26,14),fill=(255,255,235))
    d.line([(27,19),(29,18)],fill=edge)
    if fantasy:
        accent = rgb(h+.17,.35,1)
        x = 9 + seed % 8
        d.line([(x,5),(x,9)],fill=accent); d.line([(x-2,7),(x+2,7)],fill=accent)
        d.point((25,7),fill=accent)
        if ref:
            d.polygon([(17,upper),(18,upper-5),(20,upper)],fill=shine,outline=fin)
            d.point((10,lower+4),fill=accent)
    if ident in SPECIAL:
        motif = SPECIAL[ident][3]
        if motif in ('gem', 'metal'):
            # Faceted body and specular glints make gem/metal fish legible at 32px.
            d.polygon([(15,14),(18,11),(22,14),(19,18)], fill=shine, outline=edge)
            d.line([(16,14),(19,16),(21,14)], fill=belly)
            d.point((11,16), fill=shine)
        elif motif == 'armor':
            for x in (12, 16, 20, 24):
                d.line([(x,12),(x-1,17)], fill=shine)
                d.point((x-1,18), fill=edge)
        elif motif == 'stripe':
            for x in (13, 18, 23):
                d.line([(x,12),(x-2,19)], fill=mark, width=2)
        elif motif == 'crown':
            d.polygon([(19,11),(19,6),(21,8),(23,5),(25,8),(27,6),(26,11)], fill=(255,215,72), outline=edge)
        elif motif == 'double':
            d.point((23,17), fill=(18,25,30)); d.point((24,17), fill=(255,255,235))
            d.line([(25,21),(28,20)], fill=edge)
    path=ASSETS / 'textures/item/fish/species' / (ident+'.png'); path.parent.mkdir(parents=True,exist_ok=True); im.save(path)
    name='fish/species/'+ident
    save_json(ASSETS / 'models/item' / (name+'.json'), {'parent':'minecraft:item/generated','textures':{'layer0':'chrdk_fishing:item/'+name}})
    save_json(ASSETS / 'items' / (name+'.json'), {'model':{'type':'minecraft:model','model':'chrdk_fishing:item/'+name}})

for ident, _, _ in base: draw_fish(ident)
for ident, ref in premium: draw_fish(ident,ref)
print(f'Generated {len(base)+len(premium)} individual fish textures')
