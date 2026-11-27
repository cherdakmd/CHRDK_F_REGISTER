"""Deterministically draw virtual catalog navigation icons. Requires Pillow 11.3.0."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_items'
CATEGORIES = ('all', 'weapon', 'armor', 'tool', 'consumable')
NAMES = tuple(f'{name}_{state}' for name in CATEGORIES for state in ('normal', 'selected')) + ('prev', 'next', 'close')
COLORS = {'all': '#e4c06d', 'weapon': '#e98276', 'armor': '#8eb7dc', 'tool': '#c7a978', 'consumable': '#adce91', 'prev': '#8fc5d1', 'next': '#8fc5d1', 'close': '#d7837c'}


def art(name):
    kind = name.removesuffix('_selected').removesuffix('_normal')
    selected = name.endswith('_selected')
    image = Image.new('RGBA', (32, 32)); d = ImageDraw.Draw(image)
    d.rounded_rectangle((2, 2, 29, 29), radius=5, fill='#242735', outline='#141922')
    d.rounded_rectangle((4, 4, 27, 27), radius=4, fill='#414454', outline='#fff0ac' if selected else COLORS[kind], width=2)
    ink = '#171c29'; color = COLORS[kind]; light = '#fff2d4'
    if kind == 'all':
        d.polygon([(16,7),(18,13),(24,13),(19,17),(21,24),(16,20),(11,24),(13,17),(8,13),(14,13)], fill=color, outline=ink)
    elif kind == 'weapon':
        d.polygon([(21,7),(24,8),(23,12),(15,20),(12,17),(20,9)], fill=color, outline=ink)
        d.line((11,16,17,22), fill=light, width=2); d.line((13,20,9,24), fill=color, width=3)
    elif kind == 'armor':
        d.polygon([(10,8),(13,7),(16,10),(19,7),(22,8),(25,13),(22,15),(21,23),(11,23),(10,15),(7,13)], fill=color, outline=ink)
        d.line((13,14,19,14), fill=light)
    elif kind == 'tool':
        d.line((10,24,21,10), fill='#d9b178', width=3)
        d.polygon([(8,12),(13,8),(20,8),(24,12),(22,14),(18,11),(13,11)], fill=color, outline=ink)
    elif kind == 'consumable':
        d.rounded_rectangle((10,10,22,24), radius=2, fill=color, outline=ink)
        d.rectangle((13,7,19,10), fill=light, outline=ink); d.line((12,17,20,17), fill=light, width=2)
    elif kind in ('prev', 'next'):
        points = [(8,16),(16,9),(16,13),(24,13),(24,19),(16,19),(16,23)]
        if kind == 'next': points = [(32-x,y) for x,y in points]
        d.polygon(points, fill=color, outline=ink)
    else:
        d.line((10,10,22,22), fill=color, width=4); d.line((22,10,10,22), fill=color, width=4)
    if selected:
        d.line((9,26,23,26), fill=light, width=2)
    return image


def save(name):
    path = 'menu/catalog/' + name; ref = 'chrdk_items:item/' + path
    target = ROOT / 'textures/item' / (path + '.png'); target.parent.mkdir(parents=True, exist_ok=True); art(name).save(target)
    for folder, data in [('models/item', {'parent': 'minecraft:item/generated', 'textures': {'layer0': ref}}),
                         ('items', {'model': {'type': 'minecraft:model', 'model': ref}})]:
        target = ROOT / folder / (path + '.json'); target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    for name in NAMES: save(name)
    print(f'Generated {len(NAMES)} catalog navigation icons')
