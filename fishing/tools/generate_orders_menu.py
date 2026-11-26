"""Generate original 32px virtual order controls; requires Pillow."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_fishing'
ICONS = ('order_available', 'order_selected', 'order_done', 'help', 'confirm', 'back', 'close')


def art(name):
    image = Image.new('RGBA', (32, 32)); d = ImageDraw.Draw(image)
    light, navy, gold, aqua = '#f7e9c5', '#20374a', '#f4c46c', '#8ee0e4'
    d.rounded_rectangle((3, 3, 28, 28), radius=5, fill='#172d42', outline='#0c1a2b')
    edge = gold if name == 'order_selected' else '#74838b' if name == 'order_done' else '#8dd3df'
    d.rounded_rectangle((5, 5, 26, 26), radius=4, fill='#285770' if name != 'order_done' else '#44515c', outline=edge, width=2)
    if name.startswith('order_'):
        paper = '#c5c0ad' if name == 'order_done' else '#f4ddb0'
        d.polygon([(10, 7), (20, 7), (23, 10), (23, 24), (10, 24)], fill=paper, outline=navy)
        d.line((12, 12, 20, 12), fill=navy); d.line((12, 15, 18, 15), fill=navy)
        d.polygon([(12, 20), (15, 17), (19, 17), (21, 20), (19, 22), (15, 22)], fill='#53b5c2', outline=navy)
        d.point((19, 19), fill=light)
        if name != 'order_available':
            d.ellipse((20, 20, 28, 28), fill='#55a67b' if name == 'order_selected' else '#677581', outline=light)
            d.line([(22, 24), (24, 26), (27, 22)], fill=light, width=2)
    elif name == 'confirm':
        d.ellipse((8, 8, 23, 23), fill='#6aad84', outline=light, width=2)
        d.line([(11, 15), (15, 19), (21, 12)], fill=light, width=3)
    elif name == 'back':
        d.line([(11, 15), (17, 15), (21, 17), (21, 21), (17, 23), (12, 23)], fill=light, width=2)
        d.polygon([(7, 15), (13, 10), (13, 20)], fill=aqua)
    elif name == 'close':
        d.ellipse((8, 8, 23, 23), fill='#ac5964', outline=light, width=2)
        d.line((12, 12, 19, 19), fill=light, width=2); d.line((19, 12, 12, 19), fill=light, width=2)
    else:
        d.polygon([(8, 9), (14, 8), (16, 10), (18, 8), (24, 9), (24, 23), (18, 22), (16, 24), (14, 22), (8, 23)], fill=light)
        d.line((16, 10, 16, 22), fill=navy); d.line((10, 13, 14, 13), fill=navy); d.line((18, 13, 22, 13), fill=navy)
    return image


def generate():
    for name in ICONS:
        path = 'menu/orders/' + name
        target = ROOT / 'textures/item' / (path + '.png'); target.parent.mkdir(parents=True, exist_ok=True); art(name).save(target)
        for folder, payload in [('models/item', {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'chrdk_fishing:item/' + path}}),
                                ('items', {'model': {'type': 'minecraft:model', 'model': 'chrdk_fishing:item/' + path}})]:
            target = ROOT / folder / (path + '.json'); target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(payload, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    generate()
    print('Generated seven virtual order menu icons')
