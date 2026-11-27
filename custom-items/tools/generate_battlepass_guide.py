"""Generate BattlePass guide and reward-track tiles. Requires Pillow 11.3.0."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_battlepass'
BUTTONS = ('season', 'tasks', 'rewards', 'help', 'claim_all', 'premium', 'atlas', 'expeditions', 'bank',
           'recommended', 'close', 'reward_page', 'free', 'vip', 'premium_label', 'mission', 'back', 'prev',
           'next', 'claim_one', 'fair', 'targets')
TRACKS = ('free', 'vip', 'premium')
STATES = ('no_reward', 'received', 'future', 'access_locked', 'in_progress', 'ready')
NAMES = BUTTONS + tuple(f'track_{track}_{state}' for track in TRACKS for state in STATES)


def art(name):
    ink, light = '#1b2330', '#fff1d4'
    image = Image.new('RGBA', (32, 32)); d = ImageDraw.Draw(image)
    d.rounded_rectangle((2, 2, 29, 29), radius=5, fill='#282f3a', outline='#111a24')
    d.rounded_rectangle((4, 4, 27, 27), radius=4, fill='#495461', outline='#caa66c', width=2)
    if name.startswith('track_'):
        rest = name[6:]; track, state = rest.split('_', 1)
        color = {'free':'#88cda1', 'vip':'#b79be0', 'premium':'#e3c16e'}[track]
        d.rectangle((8,10,23,23),fill=color,outline=ink); d.rectangle((8,10,23,14),fill=light,outline=ink)
        d.rectangle((14,14,17,18),fill=ink)
        badge={'no_reward':'#64717e','received':'#aeb6bb','future':'#8bb7cf','access_locked':'#d88983','in_progress':'#e1bd71','ready':'#8ce1a3'}[state]
        d.ellipse((20,20,28,28),fill=badge,outline=ink)
        if state == 'ready': d.line((22,24,23,26,27,21),fill=ink,width=1)
        elif state == 'received': d.line((22,24,26,24),fill=ink,width=2)
        elif state == 'no_reward': d.line((22,22,26,26),fill=light); d.line((26,22,22,26),fill=light)
        elif state == 'future': d.line((24,22,24,24,26,25),fill=ink)
        elif state == 'access_locked': d.rectangle((22,24,26,27),fill=ink); d.arc((22,21,26,26),180,360,fill=ink)
        else: d.line((22,26,24,22,26,26),fill=ink)
    elif name in ('back','prev','next'):
        points = [(8,16),(16,9),(16,13),(24,13),(24,19),(16,19),(16,23)]
        if name == 'next': points=[(32-x,y) for x,y in points]
        d.polygon(points,fill='#90c8d9',outline=ink)
        if name == 'back': d.ellipse((21,21,26,26),fill='#e4bf73',outline=ink)
    elif name == 'close':
        d.line((10,10,22,22),fill='#e98d83',width=4); d.line((22,10,10,22),fill='#e98d83',width=4)
    elif name in ('season','premium','premium_label','free','vip'):
        color={'season':'#e2c77e','premium':'#e5b85f','premium_label':'#e5b85f','free':'#88cda1','vip':'#b79be0'}[name]
        if name in ('premium','premium_label'):
            d.polygon([(8,12),(12,17),(16,8),(20,17),(24,12),(22,23),(10,23)],fill=color,outline=ink)
            if name == 'premium_label': d.rectangle((13,25,19,26),fill=light)
        elif name == 'season': d.polygon([(16,7),(18,13),(24,13),(20,17),(22,23),(16,20),(10,23),(12,17),(8,13),(14,13)],fill=color,outline=ink)
        else:
            d.ellipse((8,8,24,24),fill=color,outline=ink)
            if name == 'free': d.line((11,16,15,20,21,11),fill=light,width=2)
            else: d.polygon([(12,11),(16,20),(20,11),(18,11),(16,16),(14,11)],fill=light)
    elif name in ('claim_all','claim_one','rewards'):
        d.rectangle((8,12,24,23),fill='#bf9262',outline=ink); d.rectangle((8,9,24,14),fill='#e6c887',outline=ink); d.rectangle((14,14,18,18),fill=light,outline=ink)
        if name == 'claim_all': d.polygon([(20,5),(24,5),(24,9),(20,9)],fill='#91dfa4',outline=ink)
        if name == 'claim_one': d.line((21,6,21,10),fill='#91dfa4',width=2)
    elif name in ('tasks','help','mission','reward_page'):
        color={'tasks':'#99c7d8','help':'#adbed6','mission':'#d9c497','reward_page':'#d5ad80'}[name]
        d.polygon([(8,9),(15,11),(16,23),(8,21)],fill=color,outline=ink); d.polygon([(16,11),(23,9),(23,21),(16,23)],fill=light,outline=ink)
        d.line((10,13,13,14),fill=ink); d.line((18,13,21,12),fill=ink)
        if name=='help': d.ellipse((19,19,26,26),fill='#87bcd7',outline=ink); d.point((22,23),fill=light)
        if name=='mission': d.ellipse((19,19,26,26),fill='#bcd88d',outline=ink)
        if name=='reward_page': d.rectangle((20,19,26,25),fill='#ebc56e',outline=ink)
    elif name in ('atlas','expeditions','targets','recommended'):
        d.ellipse((8,8,24,24),fill='#93c7d3',outline=ink)
        if name=='atlas': d.arc((11,9,21,23),80,280,fill=light,width=2); d.line((9,16,23,16),fill=light)
        elif name=='expeditions': d.polygon([(16,9),(19,19),(16,17),(13,21)],fill='#e8817a',outline=ink)
        elif name=='recommended': d.ellipse((11,11,21,21),outline=light,width=2); d.ellipse((14,14,18,18),fill=ink)
        else: d.line((10,22,22,10),fill=light,width=2); d.ellipse((18,8,25,15),outline=ink,width=2)
    elif name == 'bank':
        d.rectangle((12,9,20,12),fill=light,outline=ink); d.polygon([(12,12),(20,12),(22,20),(20,24),(12,24),(10,20)],fill='#9ed5c8',outline=ink); d.rectangle((13,17,19,21),fill='#ecda86')
    elif name == 'fair':
        d.polygon([(9,9),(16,7),(23,9),(22,18),(16,24),(10,18)],fill='#9bb5d3',outline=ink); d.line((12,16,15,19,21,12),fill=light,width=2)
    return image


def save(name):
    path = 'menu/guide/' + name; ref = 'chrdk_battlepass:item/' + path
    texture = ROOT / 'textures/item' / (path + '.png'); texture.parent.mkdir(parents=True, exist_ok=True); art(name).save(texture)
    for folder, data in [('models/item', {'parent':'minecraft:item/generated','textures':{'layer0':ref}}),
                         ('items', {'model':{'type':'minecraft:model','model':ref}})]:
        target = ROOT / folder / (path + '.json'); target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    for name in NAMES: save(name)
    print(f'Generated {len(NAMES)} BattlePass guide models')
