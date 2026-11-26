"""Original transparent item sprites for newly cooked Fishing dishes. Requires Pillow."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

PACK = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_fishing'
RECIPES = ('fried', 'smoked', 'soup')


def art(recipe):
    image = Image.new('RGBA', (32, 32)); d = ImageDraw.Draw(image)
    dark, light = '#302c38', '#fae7c6'
    if recipe == 'fried':
        d.ellipse((3, 14, 26, 25), fill='#d7e2d9', outline=dark, width=2)
        d.ellipse((6, 16, 23, 22), fill='#7caba6')
        d.polygon([(7, 18), (11, 14), (19, 13), (24, 16), (24, 19), (19, 22), (11, 22)], fill='#d58a4a', outline=dark)
        d.polygon([(8, 18), (4, 15), (4, 21)], fill='#d58a4a', outline=dark)
        d.line((12, 17, 19, 17), fill='#ffe0a4', width=2); d.point((21, 16), fill=dark)
    elif recipe == 'smoked':
        d.polygon([(5, 19), (10, 13), (19, 11), (25, 15), (25, 19), (19, 23), (10, 23)], fill='#a95f47', outline=dark, width=2)
        d.polygon([(7, 19), (3, 15), (3, 24)], fill='#a95f47', outline=dark)
        d.line((12, 15, 11, 20), fill='#e7ad78', width=2); d.line((16, 14, 15, 21), fill='#e7ad78', width=2)
        d.point((22, 16), fill=light); d.line([(12, 10), (11, 7), (13, 4)], fill='#b1c9c9', width=2)
        d.line([(20, 9), (19, 6), (21, 3)], fill='#b1c9c9', width=2)
    elif recipe == 'soup':
        d.polygon([(3, 15), (29, 15), (26, 25), (22, 28), (10, 28), (6, 25)], fill='#a36d4e', outline=dark, width=2)
        d.ellipse((4, 11, 28, 20), fill='#edbd74', outline=dark, width=2)
        d.rectangle((9, 14, 12, 16), fill='#79a967'); d.rectangle((17, 15, 20, 17), fill='#e38665')
        d.line((11, 7, 11, 10), fill='#e2e8dc', width=2); d.line((20, 5, 20, 10), fill='#e2e8dc', width=2)
    else:
        raise ValueError(recipe)
    return image


def save(recipe):
    path = 'dish/' + recipe
    target = PACK / 'textures/item' / (path + '.png'); target.parent.mkdir(parents=True, exist_ok=True); art(recipe).save(target)
    for folder, data in [('models/item', {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'chrdk_fishing:item/' + path}}),
                         ('items', {'model': {'type': 'minecraft:model', 'model': 'chrdk_fishing:item/' + path}})]:
        target = PACK / folder / (path + '.json'); target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    for name in RECIPES: save(name)
    print('Generated three standalone cooked dish textures')
