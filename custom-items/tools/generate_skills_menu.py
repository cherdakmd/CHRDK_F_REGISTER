"""Generate opt-in Skills GUI navigation/state icons, not player items. Requires Pillow."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_skills'
TABS = ('skills', 'attributes', 'top', 'rebirth', 'potions', 'builds')
CONTROLS = ('guide', 'back', 'close', 'steps', 'summary', 'perk_active', 'perk_locked',
            'reset_attributes', 'confirm_attributes', 'reset_builds', 'confirm_builds',
            'rank_entry', 'rank_empty', 'rebirth_title', 'rebirth_top', 'shop_open', 'shop_closed')
SKILLS = ('mining', 'woodcutting', 'digging', 'farming', 'fishing', 'combat', 'archery', 'defense', 'alchemy', 'enchanting')
ATTRIBUTES = ('strength', 'agility', 'stamina', 'wisdom', 'cunning')
BRANCHES = ('warrior', 'archer', 'mage', 'cleric', 'priest', 'rogue')
PALETTE = {'skills': '#70b9d9', 'attributes': '#dd8573', 'top': '#e5c36d',
           'rebirth': '#be99df', 'potions': '#76d4bf', 'builds': '#daa56c'}


def plaque(accent, selected=False):
    im = Image.new('RGBA', (32, 32)); d = ImageDraw.Draw(im)
    d.rounded_rectangle((2, 2, 29, 29), radius=6, fill='#282b3b', outline='#151926')
    d.rounded_rectangle((4, 4, 27, 27), radius=4, fill='#444458', outline=accent, width=2 if selected else 1)
    if selected:
        d.line((8, 26, 23, 26), fill='#fff4be', width=2)
        d.polygon([(16, 3), (13, 6), (19, 6)], fill='#fff4be')
    return im, d


def tab_art(name, selected):
    im, d = plaque(PALETTE[name], selected); c = PALETTE[name]; light = '#f7ebd0'; ink = '#293248'
    if name == 'skills':
        d.line((10, 22, 20, 9), fill='#cea174', width=3)
        d.line((8, 12, 11, 9, 19, 8, 22, 11), fill=c, width=3)
    elif name == 'attributes':
        d.polygon([(16, 8), (24, 12), (22, 21), (16, 24), (10, 21), (8, 12)], fill=c, outline=ink)
        d.line((16, 11, 16, 21), fill=light, width=2); d.line((12, 16, 20, 16), fill=light, width=2)
    elif name == 'top':
        d.rectangle((12, 17, 20, 22), fill=c); d.polygon([(9, 9), (23, 9), (21, 17), (16, 20), (11, 17)], fill=c, outline=ink)
        d.line((9, 11, 7, 11, 8, 16, 11, 17), fill=light); d.line((23, 11, 25, 11, 24, 16, 21, 17), fill=light)
    elif name == 'rebirth':
        d.polygon([(16, 7), (18, 13), (24, 11), (21, 16), (25, 19), (19, 19), (16, 25), (13, 19), (7, 19), (11, 16), (8, 11), (14, 13)], fill=c, outline=ink)
        d.ellipse((14, 14, 18, 18), fill=light)
    elif name == 'potions':
        d.polygon([(16, 7), (22, 13), (19, 23), (13, 23), (10, 13)], fill=c, outline=ink)
        d.line((12, 13, 16, 9, 17, 19), fill=light, width=1)
    else:
        d.polygon([(16, 7), (24, 11), (23, 20), (16, 24), (9, 20), (8, 11)], fill=c, outline=ink)
        d.line((16, 10, 16, 21), fill=light, width=2); d.line((16, 15, 11, 11), fill=light, width=2); d.line((16, 18, 21, 13), fill=light, width=2)
    return im


def control_art(name):
    accent = '#91bcd5'
    if name.startswith(('confirm_', 'reset_')): accent = '#ef8c77' if name.endswith('builds') else '#e5bd6d'
    if name == 'perk_active': accent = '#83d394'
    if name == 'perk_locked': accent = '#9993a8'
    im, d = plaque(accent, name.startswith('confirm_')); light = '#f8eed8'; ink = '#2d3140'
    if name in ('guide', 'steps', 'summary'):
        d.rectangle((9, 7, 22, 24), fill='#e9d4a3', outline=ink)
        d.line((12, 12, 19, 12), fill='#805b4b', width=2)
        d.line((12, 16, 19, 16), fill='#805b4b', width=2)
        if name == 'guide': d.ellipse((13, 18, 17, 22), fill=accent)
        elif name == 'steps': d.line((12, 21, 14, 19, 16, 21, 19, 18), fill=accent, width=2)
        else: d.line((12, 20, 19, 20), fill='#805b4b', width=2)
    elif name == 'back':
        d.polygon([(7, 16), (15, 9), (15, 13), (24, 13), (24, 19), (15, 19), (15, 23)], fill=accent, outline=ink)
    elif name == 'close':
        d.line((10, 10, 22, 22), fill='#ed8279', width=4); d.line((22, 10, 10, 22), fill='#ed8279', width=4)
    elif name in ('rank_entry', 'rank_empty', 'rebirth_title', 'rebirth_top'):
        d.polygon([(10, 9), (22, 9), (21, 17), (16, 20), (11, 17)], fill='#dfb968', outline=ink)
        d.rectangle((14, 20, 18, 23), fill='#dfb968', outline=ink)
        if name == 'rank_empty':
            d.line((11, 11, 21, 21), fill='#ddd5d0', width=2); d.line((21, 11, 11, 21), fill='#ddd5d0', width=2)
        elif name == 'rank_entry': d.ellipse((14, 12, 18, 16), fill=light)
        elif name == 'rebirth_title':
            d.polygon([(16, 6), (17, 10), (20, 11), (17, 12), (16, 15), (15, 12), (12, 11), (15, 10)], fill='#e9d7ff')
        else: d.line((11, 23, 21, 23), fill='#c6a1e2', width=2)
    elif name in ('shop_open', 'shop_closed'):
        d.polygon([(16, 7), (22, 12), (19, 22), (13, 22), (10, 12)], fill='#75d2b8', outline=ink)
        if name == 'shop_open': d.line((11, 17, 14, 20, 21, 12), fill=light, width=2)
        else:
            d.line((11, 11, 21, 21), fill='#f2988c', width=2); d.line((21, 11, 11, 21), fill='#f2988c', width=2)
    elif name.startswith('perk_'):
        d.polygon([(16, 7), (23, 11), (22, 20), (16, 24), (10, 20), (9, 11)], fill=accent, outline=ink)
        if name == 'perk_active': d.line((11, 16, 15, 20, 21, 12), fill=light, width=2)
        else:
            d.arc((13, 9, 19, 18), 180, 360, fill=light, width=2); d.rectangle((12, 15, 20, 21), outline=light)
    else:
        d.arc((8, 8, 23, 23), 45, 310, fill=accent, width=3)
        d.polygon([(19, 8), (25, 9), (22, 14)], fill=accent)
        if name.startswith('confirm_'):
            d.line((11, 17, 14, 20, 21, 12), fill=light, width=2)
        elif name.endswith('builds'):
            d.line((16, 11, 16, 21), fill=light, width=2); d.line((13, 14, 19, 14), fill=light, width=2)
        else: d.line((12, 16, 20, 16), fill=light, width=2)
    return im


def card_art(group, name, rebirth=None):
    colors = {'skill': '#77bad8', 'attribute': '#e49a80', 'branch': '#d6aa6d'}
    accent = colors[group]
    im, d = plaque(accent)
    light, dark, gold, wood = '#f6e6c3', '#252c3b', '#f3c76c', '#b27b50'
    # Each card uses a recognizable symbol, not only a colour or label.
    if name in ('mining', 'woodcutting', 'digging'):
        d.line((10, 23, 19, 9), fill=wood, width=3)
        if name == 'mining':
            d.line((8, 12, 11, 8, 19, 8, 23, 11), fill=light, width=3)
        elif name == 'woodcutting':
            d.polygon([(16, 8), (22, 8), (23, 12), (18, 15), (16, 13)], fill=light, outline=dark)
        else:
            d.polygon([(18, 7), (22, 7), (23, 11), (19, 15), (16, 13)], fill=light, outline=dark)
    elif name in ('farming',):
        d.line((16, 23, 16, 8), fill=wood, width=2)
        for y in (9, 13, 17):
            d.polygon([(15, y+2), (10, y), (11, y+4)], fill=gold)
            d.polygon([(17, y+2), (22, y), (21, y+4)], fill=gold)
    elif name in ('fishing',):
        d.polygon([(9, 16), (14, 11), (20, 12), (23, 16), (20, 20), (14, 21)], fill=accent, outline=dark)
        d.polygon([(10, 16), (6, 12), (7, 20)], fill=light); d.point((19, 15), fill=dark)
    elif name in ('combat', 'strength', 'warrior'):
        d.polygon([(10, 23), (12, 17), (20, 8), (23, 7), (22, 11), (14, 20)], fill=light, outline=dark)
        d.line((10, 16, 16, 21), fill=gold if group != 'skill' else accent, width=2)
    elif name in ('archery', 'archer'):
        d.arc((7, 7, 23, 24), -80, 80, fill=light, width=2)
        d.line((17, 8, 17, 23), fill=wood); d.line((9, 16, 23, 16), fill=gold, width=2); d.polygon([(24, 16), (20, 13), (20, 19)], fill=gold)
    elif name in ('defense', 'stamina', 'cleric'):
        d.polygon([(9, 9), (16, 7), (23, 9), (22, 18), (16, 24), (10, 18)], fill=accent, outline=dark)
        d.line((16, 10, 16, 21), fill=light, width=2)
        if name == 'cleric': d.line((12, 15, 20, 15), fill=light, width=2)
        elif name == 'stamina': d.ellipse((13, 13, 19, 19), outline=gold, width=2)
    elif name == 'alchemy':
        d.rectangle((14, 7, 18, 10), fill=gold); d.polygon([(13, 10), (19, 10), (19, 13), (23, 18), (22, 23), (10, 23), (9, 18), (13, 13)], fill=accent, outline=dark)
        d.line((12, 19, 20, 19), fill=light, width=2)
    elif name in ('enchanting', 'wisdom'):
        d.polygon([(8, 10), (15, 11), (16, 13), (17, 11), (24, 10), (24, 21), (17, 21), (16, 23), (15, 21), (8, 21)], fill=light, outline=dark)
        d.line((16, 13, 16, 21), fill=wood); d.line((10, 14, 14, 15), fill=accent)
        if name == 'wisdom': d.point((20, 17), fill=gold)
        else: d.polygon([(20, 7), (21, 9), (23, 10), (21, 11), (20, 13), (19, 11), (17, 10), (19, 9)], fill=gold)
    elif name == 'agility':
        d.polygon([(9, 22), (11, 14), (15, 9), (22, 8), (20, 15), (14, 21)], fill=light, outline=dark)
        d.line((10, 22, 19, 11), fill=accent, width=2)
    elif name in ('cunning', 'rogue'):
        d.polygon([(7, 16), (11, 11), (16, 9), (21, 11), (25, 16), (21, 20), (16, 22), (11, 20)], fill=light, outline=dark)
        d.ellipse((13, 12, 19, 19), fill=accent, outline=dark); d.line((16, 14, 16, 18), fill=dark, width=2)
        if name == 'rogue': d.line((10, 21, 22, 21), fill=gold, width=2)
    elif name == 'mage':
        d.line((10, 23, 20, 9), fill=wood, width=3)
        d.polygon([(20, 6), (21, 9), (25, 10), (21, 11), (20, 15), (18, 11), (15, 10), (18, 9)], fill=light, outline=gold)
    elif name == 'priest':
        d.ellipse((10, 8, 22, 20), fill=light, outline=gold, width=2)
        d.line((16, 10, 16, 22), fill=gold, width=2); d.line((12, 14, 20, 14), fill=gold, width=2)
    if rebirth is not None:
        d.ellipse((19, 19, 31, 31), fill=dark)
        d.ellipse((20, 20, 30, 30), fill='#68c985' if rebirth else '#978ca7')
        if rebirth: d.line((22, 25, 24, 27, 28, 22), fill=light, width=2)
        else:
            d.arc((23, 21, 27, 27), 180, 360, fill=light); d.rectangle((22, 25, 28, 29), outline=light)
    return im

def ability_art(name):
    im = card_art('skill', name)
    d = ImageDraw.Draw(im)
    d.ellipse((19, 19, 31, 31), fill='#292b37')
    d.ellipse((20, 20, 30, 30), fill='#e9b65b')
    d.polygon([(26, 21), (22, 26), (25, 26), (24, 29), (28, 24), (25, 24)], fill='#fff2cb')
    return im


def save(path, image):
    texture = ROOT / 'textures/item' / (path + '.png'); texture.parent.mkdir(parents=True, exist_ok=True); image.save(texture)
    ref = 'chrdk_skills:item/' + path
    for directory, obj in [('models/item', {'parent': 'minecraft:item/generated', 'textures': {'layer0': ref}}),
                           ('items', {'model': {'type': 'minecraft:model', 'model': ref}})]:
        file = ROOT / directory / (path + '.json'); file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(json.dumps(obj, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    for name in TABS:
        for selected in (False, True): save('menu/tab/' + name + ('_selected' if selected else ''), tab_art(name, selected))
    for name in CONTROLS: save('menu/' + name, control_art(name))
    for name in SKILLS:
        save('menu/skill/' + name, card_art('skill', name))
        save('menu/ability/' + name, ability_art(name))
        for state, ready in (('waiting', False), ('ready', True)):
            save('menu/rebirth/' + name + '_' + state, card_art('skill', name, ready))
    for name in ATTRIBUTES: save('menu/attribute/' + name, card_art('attribute', name))
    for name in BRANCHES: save('menu/branch/' + name, card_art('branch', name))
    print('Generated 80 Skills navigation, state and card icons')
