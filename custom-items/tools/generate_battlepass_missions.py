"""Generate virtual BattlePass mission list tiles. Requires Pillow 11.3.0."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_battlepass'
TYPES = ('mine', 'kill', 'craft', 'fish', 'place', 'smelt', 'enchant', 'breed')
STATES = ('progress', 'ready', 'vip_locked', 'premium_locked', 'claimed')
CONTROLS = ('empty', 'prev', 'next', 'claim_all', 'back', 'summary', 'refresh', 'premium', 'close')
FILTERS = ('all', 'active', 'ready', 'future', 'done')
NAMES = tuple(f'mission_{kind}_{state}' for kind in TYPES for state in STATES) + ('mission_future',) + CONTROLS + tuple('filter_' + f for f in FILTERS)


def art(name):
    ink, light = '#1b2330', '#fff1d4'
    colors = {'progress':'#85aec9', 'ready':'#8cdb9d', 'vip_locked':'#b999e1', 'premium_locked':'#e5be6b', 'claimed':'#98a1a9'}
    image = Image.new('RGBA', (32, 32)); d = ImageDraw.Draw(image)
    d.rounded_rectangle((2, 2, 29, 29), radius=5, fill='#282f3a', outline='#111a24')
    d.rounded_rectangle((4, 4, 27, 27), radius=4, fill='#495461', outline='#caa66c', width=2)
    if name.startswith('mission_') and name != 'mission_future':
        rest = name[len('mission_'):]; kind, state = rest.split('_', 1); color = colors[state]
        if kind == 'mine':
            d.line((10,23,21,10), fill='#d1a47a', width=3); d.polygon([(8,10),(13,7),(23,8),(24,12),(17,11),(12,13)], fill='#afcfdb', outline=ink)
        elif kind == 'kill':
            d.polygon([(20,7),(24,7),(22,15),(14,22),(11,19),(19,11)], fill='#b3cdd7', outline=ink); d.line((9,19,15,25), fill='#d4a272', width=3)
        elif kind == 'craft':
            d.rectangle((8,10,24,23), fill='#b88857', outline=ink); d.line((8,15,24,15), fill=light, width=2); d.line((13,10,13,23), fill=ink); d.line((19,10,19,23), fill=ink)
        elif kind == 'fish':
            d.polygon([(8,16),(13,11),(20,11),(24,15),(20,20),(13,20)], fill='#90c9df', outline=ink); d.polygon([(9,15),(6,12),(6,20),(9,18)], fill='#90c9df', outline=ink); d.point((20,14),fill=ink)
        elif kind == 'place':
            d.polygon([(8,12),(16,8),(24,12),(24,21),(16,25),(8,21)], fill='#b69279', outline=ink); d.line((8,12,16,16,24,12), fill=light); d.line((16,16,16,25), fill=ink)
        elif kind == 'smelt':
            d.rectangle((8,8,24,24), fill='#aeb4bb', outline=ink); d.rectangle((11,11,21,15), fill=ink); d.polygon([(12,21),(14,17),(16,19),(18,16),(21,21)], fill='#f2a65e')
        elif kind == 'enchant':
            d.polygon([(8,9),(15,11),(16,23),(8,21)], fill='#ba9cdb', outline=ink); d.polygon([(16,11),(23,9),(23,21),(16,23)], fill='#d5bfed', outline=ink); d.line((12,13,13,19), fill=light)
        elif kind == 'breed':
            d.polygon([(16,23),(9,17),(8,13),(10,10),(13,10),(16,13),(19,10),(22,10),(24,13),(23,17)], fill='#e99196', outline=ink)
        d.ellipse((20,20,27,27), fill=color, outline=ink)
        if state == 'ready': d.line((22,23,23,25,26,21), fill=light, width=1)
        elif state in ('vip_locked','premium_locked'): d.rectangle((22,23,25,26),fill=ink); d.arc((22,20,25,25),180,360,fill=ink)
        elif state == 'claimed': d.line((22,23,25,25),fill=ink); d.line((25,23,22,25),fill=ink)
        else: d.line((22,24,25,24),fill=ink)
    elif name == 'mission_future':
        d.ellipse((8,8,24,24),fill='#aab2bb',outline=ink); d.line((16,10,16,16,20,19),fill=ink,width=2)
    elif name.startswith('filter_'):
        f=name[7:]; color={'all':'#b8c6ce','active':'#85aec9','ready':'#8cdb9d','future':'#aeb0bd','done':'#d6bd7e'}[f]
        d.polygon([(8,9),(24,9),(19,16),(19,22),(13,25),(13,16)],fill=color,outline=ink)
        d.rectangle((11,11,21,12),fill=light)
    elif name in ('prev','next','back'):
        p=[(8,16),(16,9),(16,13),(24,13),(24,19),(16,19),(16,23)]
        if name=='next': p=[(32-x,y) for x,y in p]
        d.polygon(p,fill='#90c8d9',outline=ink)
        if name=='back': d.ellipse((21,21,25,25),fill='#e5be6b',outline=ink)
    elif name == 'close':
        d.line((10,10,22,22),fill='#e68c83',width=4); d.line((22,10,10,22),fill='#e68c83',width=4)
    elif name == 'claim_all':
        d.rectangle((8,12,24,23),fill='#bd9060',outline=ink); d.rectangle((8,10,24,14),fill='#e2c17c',outline=ink); d.rectangle((14,14,18,18),fill=light,outline=ink)
    elif name == 'summary':
        d.polygon([(16,7),(18,13),(24,13),(20,17),(22,23),(16,20),(10,23),(12,17),(8,13),(14,13)],fill='#e5c875',outline=ink)
    elif name == 'premium':
        d.polygon([(8,12),(12,17),(16,9),(20,17),(24,12),(22,23),(10,23)],fill='#e5be6b',outline=ink)
    elif name == 'refresh':
        d.arc((9,9,23,23),30,315,fill='#9ed3d5',width=3); d.polygon([(20,8),(24,10),(21,14)],fill='#9ed3d5')
    else:
        d.rectangle((10,8,22,24),fill='#b9c4c8',outline=ink); d.line((13,13,19,13),fill=light,width=2); d.line((13,17,19,17),fill=light,width=2)
    return image


def save(name):
    path = 'menu/' + name; ref = 'chrdk_battlepass:item/' + path
    texture = ROOT / 'textures/item' / (path + '.png'); texture.parent.mkdir(parents=True, exist_ok=True); art(name).save(texture)
    for folder, data in [('models/item', {'parent':'minecraft:item/generated','textures':{'layer0':ref}}),
                         ('items', {'model':{'type':'minecraft:model','model':ref}})]:
        target = ROOT / folder / (path + '.json'); target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    for name in NAMES: save(name)
    print(f'Generated {len(NAMES)} BattlePass mission-menu models')
