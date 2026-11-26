"""Generate three original sprites for new stock rebirth rewards. Requires Pillow."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_skills'
NAMES = ('heart_of_the_sea', 'nether_star', 'dragon_egg')


def art(name):
    im = Image.new('RGBA', (32, 32)); d = ImageDraw.Draw(im)
    dark, light, gold = '#253349', '#f8eed8', '#e9c170'
    if name == 'heart_of_the_sea':
        d.polygon([(16, 27), (5, 18), (4, 12), (7, 7), (12, 7), (16, 11), (20, 7), (25, 7), (28, 12), (27, 18)], fill='#398b9c', outline=dark)
        d.polygon([(16, 24), (8, 17), (7, 12), (10, 10), (13, 11), (16, 14), (19, 11), (22, 10), (25, 12), (24, 17)], fill='#65ced1')
        d.line((10, 13, 12, 11), fill=light, width=2); d.polygon([(16, 15), (18, 18), (16, 21), (14, 18)], fill=gold)
    elif name == 'nether_star':
        d.polygon([(16, 2), (19, 11), (28, 7), (24, 16), (30, 19), (21, 21), (21, 29), (16, 24), (11, 29), (11, 21), (2, 19), (8, 16), (4, 7), (13, 11)], fill='#b08fcb', outline=dark)
        d.polygon([(16, 7), (18, 14), (24, 11), (21, 17), (25, 19), (19, 19), (19, 24), (16, 21), (13, 24), (13, 19), (7, 19), (11, 17), (8, 11), (14, 14)], fill=light)
        d.ellipse((13, 14, 19, 20), fill=gold, outline=dark)
    elif name == 'dragon_egg':
        d.polygon([(14, 3), (19, 3), (23, 8), (26, 15), (25, 23), (21, 28), (11, 28), (7, 23), (6, 15), (9, 8)], fill='#3b3156', outline=dark)
        d.polygon([(15, 6), (19, 7), (23, 15), (22, 23), (19, 25), (11, 25), (9, 21), (10, 13)], fill='#69518a')
        d.polygon([(14, 8), (17, 10), (15, 13), (12, 11)], fill='#b78cdb'); d.polygon([(19, 14), (22, 16), (20, 19), (17, 17)], fill='#d2a7ed')
        d.polygon([(13, 19), (16, 21), (14, 24), (11, 22)], fill='#a67ac9'); d.line((13, 7, 11, 9), fill=light)
    return im


def save(name):
    path = 'rebirth/' + name
    target = ROOT / 'textures/item' / (path + '.png'); target.parent.mkdir(parents=True, exist_ok=True); art(name).save(target)
    for folder, data in [('models/item', {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'chrdk_skills:item/' + path}}),
                         ('items', {'model': {'type': 'minecraft:model', 'model': 'chrdk_skills:item/' + path}})]:
        target = ROOT / folder / (path + '.json'); target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    for name in NAMES: save(name)
    print('Generated three rebirth reward models')
