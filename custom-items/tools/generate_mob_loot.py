"""Generate original 32px sprites for newly dropped mob loot. Requires Pillow."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

PACK = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_mobs'
KINDS = ('essence-combat', 'essence-gather', 'essence-craft', 'variant-cursed',
         'variant-smoldering', 'variant-gross', 'variant-cringe')
COLORS = ('#e36d61', '#73c7a0', '#ddb66e', '#a989d4', '#ed8c48', '#87bd64', '#6ccddc')


def art(name, color):
    im = Image.new('RGBA', (32, 32)); d = ImageDraw.Draw(im)
    dark, shine = '#2b3040', '#f9eed5'
    if name.startswith('essence-'):
        d.rectangle((12, 4, 20, 8), fill='#bd9c68', outline=dark)
        d.polygon([(11, 8), (21, 8), (21, 11), (24, 14), (24, 25), (21, 28), (11, 28), (8, 25), (8, 14), (11, 11)], fill='#b7d8d3', outline=dark)
        d.rectangle((10, 15, 22, 24), fill=color, outline=dark)
        d.line((11, 11, 11, 14), fill=shine, width=2)
        if name == 'essence-combat':
            d.line((13, 22, 19, 16), fill=shine, width=2); d.line((13, 16, 19, 22), fill=shine, width=2)
        elif name == 'essence-gather':
            d.polygon([(16, 15), (20, 18), (16, 23), (12, 20)], fill=shine); d.line((14, 21, 19, 16), fill=dark)
        else:
            d.polygon([(16, 15), (17, 18), (20, 19), (17, 20), (16, 23), (15, 20), (12, 19), (15, 18)], fill=shine)
    elif name == 'variant-cursed':
        d.polygon([(16, 3), (24, 11), (23, 22), (16, 29), (9, 22), (8, 11)], fill=color, outline=dark)
        d.line([(12, 11), (20, 20), (13, 24)], fill=dark, width=2); d.ellipse((15, 10, 20, 15), fill=shine, outline=dark)
    elif name == 'variant-smoldering':
        d.polygon([(16, 3), (20, 10), (24, 8), (25, 19), (22, 26), (16, 29), (9, 26), (6, 20), (10, 11), (12, 15)], fill=color, outline=dark)
        d.polygon([(16, 14), (20, 21), (18, 25), (13, 25), (11, 21)], fill='#f9d66f')
    elif name == 'variant-gross':
        d.polygon([(5, 18), (8, 11), (14, 10), (16, 6), (20, 11), (25, 13), (27, 20), (24, 26), (8, 26), (5, 23)], fill=color, outline=dark)
        d.ellipse((10, 15, 13, 18), fill=shine); d.ellipse((19, 15, 22, 18), fill=shine)
        d.line([(13, 21), (16, 23), (20, 21)], fill=dark, width=2)
    elif name == 'variant-cringe':
        d.polygon([(16, 3), (19, 10), (26, 8), (23, 15), (29, 19), (22, 21), (22, 28), (16, 24), (10, 28), (10, 21), (3, 19), (9, 15), (6, 8), (13, 10)], fill=color, outline=dark)
        d.ellipse((12, 13, 20, 21), fill=shine, outline=dark); d.point((14, 16), fill=dark); d.point((18, 16), fill=dark)
    return im


def save(name, color):
    path = 'loot/' + name
    target = PACK / 'textures/item' / (path + '.png'); target.parent.mkdir(parents=True, exist_ok=True); art(name, color).save(target)
    for folder, data in [('models/item', {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'chrdk_mobs:item/' + path}}),
                         ('items', {'model': {'type': 'minecraft:model', 'model': 'chrdk_mobs:item/' + path}})]:
        target = PACK / folder / (path + '.json'); target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    for name, color in zip(KINDS, COLORS): save(name, color)
    print('Generated seven mob loot models')
