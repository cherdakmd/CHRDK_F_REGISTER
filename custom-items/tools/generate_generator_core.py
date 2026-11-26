"""Create an original 32px icon for new generator cores. Requires Pillow."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_generator'


def generate():
    image = Image.new('RGBA', (32, 32)); d = ImageDraw.Draw(image)
    dark, steel, light, teal = '#253342', '#718d9d', '#deeddd', '#54d0c5'
    d.polygon([(16, 2), (24, 6), (29, 14), (28, 22), (22, 29), (10, 29), (4, 22), (3, 14), (8, 6)], fill=dark)
    d.polygon([(16, 5), (23, 8), (26, 14), (25, 21), (21, 26), (11, 26), (7, 21), (6, 14), (9, 8)], fill=steel, outline=light)
    d.polygon([(16, 9), (22, 13), (22, 20), (16, 24), (10, 20), (10, 13)], fill='#225e70', outline=dark)
    d.polygon([(16, 11), (20, 14), (20, 19), (16, 22), (12, 19), (12, 14)], fill=teal)
    d.line([(16, 12), (14, 16), (17, 16), (15, 21)], fill=light, width=2)
    for xy in [(15, 4), (6, 15), (25, 15), (10, 25), (21, 25)]: d.point(xy, fill='#f4bd6c')
    target = ROOT / 'textures/item/core.png'; target.parent.mkdir(parents=True, exist_ok=True); image.save(target)
    for folder, data in [('models/item', {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'chrdk_generator:item/core'}}),
                         ('items', {'model': {'type': 'minecraft:model', 'model': 'chrdk_generator:item/core'}})]:
        target = ROOT / folder / 'core.json'; target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    generate()
    print('Generated generator core model')
